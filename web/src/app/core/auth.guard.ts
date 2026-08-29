import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { SessionStore } from './session.store';

/**
 * Waits for the one-time session restore before deciding, so a reload on a
 * protected route does not flash the login page for an already-signed-in user.
 */
export const authGuard: CanActivateFn = async (_route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);

  if (!session.restored()) {
    await session.restore();
  }
  if (session.isAuthenticated()) {
    return true;
  }
  // Where they were going, so signing in resumes it instead of dumping everyone
  // on the trip list. Invite links make this load-bearing rather than a nicety:
  // the whole point of one is that its holder has no account yet, so the journey
  // is always link -> register -> back to the link.
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};
