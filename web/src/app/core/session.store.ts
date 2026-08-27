import { Injectable, computed, inject, signal } from '@angular/core';
import { Api, SessionUser, login, logout, me, register } from '../api';

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

  private readonly _user = signal<SessionUser | null>(null);
  private readonly _restored = signal(false);

  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null);
  /** False until the first /me call settles, so guards do not bounce too early. */
  readonly restored = this._restored.asReadonly();

  async restore(): Promise<void> {
    try {
      this._user.set(await this.api.invoke(me));
    } catch {
      // 401 is the expected answer for a visitor with no cookie.
      this._user.set(null);
    } finally {
      this._restored.set(true);
    }
  }

  async login(email: string, password: string): Promise<void> {
    this._user.set(await this.api.invoke(login, { body: { email, password } }));
  }

  async register(email: string, displayName: string, password: string): Promise<void> {
    this._user.set(await this.api.invoke(register, { body: { email, displayName, password } }));
  }

  async logout(): Promise<void> {
    try {
      await this.api.invoke(logout);
    } finally {
      // Local state clears either way: if the call failed the cookie may still
      // be live, but leaving a stale user on screen is worse.
      this._user.set(null);
    }
  }
}
