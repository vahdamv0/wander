import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStore } from './session.store';

/**
 * The administration route, for administrators.
 *
 * Runs after `authGuard` on the same route, so the session is already restored
 * by the time this reads it — the ordering matters, because an unrestored
 * session has no role and would bounce a signed-in admin to the trip list on
 * every reload.
 *
 * This is a **courtesy, not a control**. `AdminController` is annotated
 * `@PreAuthorize("hasRole('ADMIN')")` and refuses the requests whatever the
 * browser thinks; all this does is keep an ordinary account from reaching a page
 * that would render nothing but errors. Anything that relies on it alone is a
 * bug, since the role travels in a signal the client could be made to lie about.
 */
export const adminGuard: CanActivateFn = async () => {
  const session = inject(SessionStore);
  const router = inject(Router);

  if (!session.restored()) {
    await session.restore();
  }
  return session.user()?.role === 'ADMIN' ? true : router.createUrlTree(['/trips']);
};
