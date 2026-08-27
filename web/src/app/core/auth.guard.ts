import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStore } from './session.store';

/**
 * Waits for the one-time session restore before deciding, so a reload on a
 * protected route does not flash the login page for an already-signed-in user.
 */
export const authGuard: CanActivateFn = async () => {
  const session = inject(SessionStore);
  const router = inject(Router);

  if (!session.restored()) {
    await session.restore();
  }
  return session.isAuthenticated() ? true : router.createUrlTree(['/login']);
};
