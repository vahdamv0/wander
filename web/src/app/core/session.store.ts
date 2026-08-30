import { Injectable, computed, inject, signal } from '@angular/core';
import { Api, SessionUser, changePassword, login, logout, me, register } from '../api';
import { Connectivity } from './connectivity';
import { isNetworkError, isUnauthorized } from './errors';
import { OfflineCache, cacheKeys } from './offline-cache';

/**
 * Who is signed in, as signals.
 *
 * The session lives in an httpOnly cookie, so this store never holds a
 * credential — only the identity the server told us about. `restore()` is how a
 * page reload finds out whether the cookie is still good.
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly api = inject(Api);
  private readonly cache = inject(OfflineCache);
  private readonly connectivity = inject(Connectivity);

  private readonly _user = signal<SessionUser | null>(null);
  private readonly _restored = signal(false);

  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null);
  /** False until the first /me call settles, so guards do not bounce too early. */
  readonly restored = this._restored.asReadonly();

  /**
   * Who is signed in — and, offline, who *was*.
   *
   * This method is why offline reads are possible at all. It runs before the
   * first route renders (`provideAppInitializer`) and `authGuard` gates every
   * page on its answer, so treating an unreachable server as "signed out" would
   * bounce somebody with a full cache to a login form they cannot submit.
   *
   * The two failures are therefore not the same thing:
   *
   *   - **401** — genuinely signed out. Forget the identity *and* the cached
   *     trips: the cookie is gone, so the next person at this browser must not
   *     inherit the last one's data.
   *   - **Unreachable** — not unauthenticated. Keep the last known identity so
   *     the cached pages can be read. Note this is status 0 *or* 504: a service
   *     worker synthesises a gateway timeout for what it cannot fetch, so with
   *     the worker installed an offline server does not look like a network
   *     error at all. See `isNetworkError`.
   *   - **Anything else** — sign out for this session but keep the cache, since
   *     the answer is unknown and unknown is not grounds to delete somebody's
   *     trips.
   */
  async restore(): Promise<void> {
    try {
      const user = await this.api.invoke(me);
      this.connectivity.markReachable();
      await this.remember(user);
    } catch (err) {
      if (isNetworkError(err)) {
        this.connectivity.markFailure(err);
        this._user.set(await this.rememberedUser());
      } else {
        // 401 is the expected answer for a visitor with no cookie.
        this._user.set(null);
        // Only a 401 clears the cache. Anything else — a 500, a proxy having a
        // bad day — means the answer is unknown, and throwing away every trip on
        // the device over an unknown is how a bug becomes data loss.
        if (isUnauthorized(err)) {
          await this.forget();
        }
      }
    } finally {
      this._restored.set(true);
    }
  }

  /**
   * The identity is cached under a fixed key rather than a per-user one, because
   * it is what *tells* us the user id — the chicken and egg the rest of the cache
   * does not have.
   */
  private async rememberedUser(): Promise<SessionUser | null> {
    const cached = await this.cache.get<SessionUser>(0, cacheKeys.session());
    return cached?.body ?? null;
  }

  private async remember(user: SessionUser): Promise<void> {
    const previous = this._user();
    this._user.set(user);
    // A different person at the same browser: their predecessor's trips go, and
    // go before anything of the new user's is written.
    if (previous && previous.id !== user.id) {
      await this.cache.clearAll();
    }
    await this.cache.put(0, cacheKeys.session(), user);
  }

  private async forget(): Promise<void> {
    await this.cache.clearAll();
  }

  async login(email: string, password: string): Promise<void> {
    await this.remember(await this.api.invoke(login, { body: { email, password } }));
  }

  /**
   * A new account. `inviteToken` is what gets somebody in on an instance with
   * self-signup switched off — it is checked, not spent, so the invitation is
   * still there to be accepted a moment later.
   */
  async register(
    email: string,
    displayName: string,
    password: string,
    inviteToken?: string | null,
  ): Promise<void> {
    await this.remember(
      await this.api.invoke(register, {
        body: { email, displayName, password, inviteToken: inviteToken ?? undefined },
      }),
    );
  }

  /**
   * Change the signed-in user's own password.
   *
   * Nothing local changes: the identity is the same person and the session
   * cookie survives, because the server ends every session for this account
   * *except* the one that made the call. So there is no signal to update and
   * nothing to re-read — which is why this returns void and touches no state.
   */
  async changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await this.api.invoke(changePassword, { body: { currentPassword, newPassword } });
  }

  async logout(): Promise<void> {
    try {
      await this.api.invoke(logout);
    } finally {
      // Local state clears either way: if the call failed the cookie may still
      // be live, but leaving a stale user on screen is worse. The cached trips go
      // with it — signing out has to mean the next person at this browser cannot
      // read this one's itineraries out of IndexedDB.
      this._user.set(null);
      await this.forget();
    }
  }
}
