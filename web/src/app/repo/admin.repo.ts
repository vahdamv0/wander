import { Injectable, effect, inject, signal } from '@angular/core';
import {
  AdminUserView,
  Api,
  CreatedResetView,
  PasswordResetView,
  ResetPreview,
  createReset,
  listAccounts,
  listResets,
  previewReset,
  redeemReset,
  revokeReset,
  setAccountDisabled,
  setAccountRole,
} from '../api';
import { Connectivity } from '../core/connectivity';
import { OfflineError } from '../core/errors';
import { SessionStore } from '../core/session.store';

/**
 * Administering the instance's accounts.
 *
 * **The second repo with no offline cache**, after `InviteRepo`, and for the
 * same two reasons sharpened: a minted reset token would be written to IndexedDB
 * on this device, which is the one thing the server refuses to store in the
 * clear — and it sets a password rather than joining a trip. A cached list would
 * be worse still, since it would show a disabled account as active and a revoked
 * link as outstanding, which is exactly backwards for controls whose purpose is
 * taking access away.
 *
 * The two redeem calls are here rather than in a repo of their own even though
 * they are anonymous: they are the other end of the same feature, and splitting
 * them would put the token's two halves in two files.
 */
@Injectable({ providedIn: 'root' })
export class AdminRepo {
  private readonly api = inject(Api);
  private readonly connectivity = inject(Connectivity);
  private readonly session = inject(SessionStore);

  private readonly _accounts = signal<AdminUserView[]>([]);
  private readonly _resets = signal<PasswordResetView[]>([]);
  private readonly _loading = signal(false);
  private readonly _saving = signal(false);
  /** The link just minted, readable once. Never persisted anywhere. */
  private readonly _created = signal<CreatedResetView | null>(null);
  private _resetsForUserId: number | null = null;

  readonly accounts = this._accounts.asReadonly();
  readonly resets = this._resets.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly saving = this._saving.asReadonly();
  readonly created = this._created.asReadonly();

  constructor() {
    // Signing out drops the minted token along with the lists. This service is
    // application-scoped, so without it a link created before signing out is
    // still in memory for the next person at this browser — and that link sets
    // somebody's password.
    let lastUserId: number | null = null;
    effect(() => {
      const userId = this.session.user()?.id ?? null;
      if (userId !== lastUserId) {
        lastUserId = userId;
        this.clear();
      }
    });
  }

  async load(): Promise<void> {
    this.requireOnline();
    this._loading.set(true);
    try {
      this._accounts.set(await this.api.invoke(listAccounts));
    } finally {
      this._loading.set(false);
    }
  }

  /**
   * Take an account out of service, or put it back.
   *
   * Re-reads rather than patching the row locally: disabling has effects the
   * client cannot see — sessions ended, sockets closed — and the server's answer
   * is the honest account of what happened.
   */
  async setDisabled(userId: number, disabled: boolean): Promise<void> {
    this.requireOnline();
    this._saving.set(true);
    try {
      await this.api.invoke(setAccountDisabled, { userId, body: { disabled } });
      await this.load();
    } finally {
      this._saving.set(false);
    }
  }

  /**
   * Promote an account to administrator, or demote one back.
   *
   * Re-reads like the others, *except* when the caller has just stepped down.
   * The server ends every session for the account it changed — the caller's own
   * included — so re-reading the list would be a 401 the moment after a call
   * that succeeded, and the page would report a failure for something that
   * worked. `reload: false` is how the page says "I know I have just signed
   * myself out"; it then clears the session and goes to the login form.
   */
  async setRole(userId: number, role: 'USER' | 'ADMIN', reload = true): Promise<void> {
    this.requireOnline();
    this._saving.set(true);
    try {
      await this.api.invoke(setAccountRole, { userId, body: { role } });
      if (reload) {
        await this.load();
      }
    } finally {
      this._saving.set(false);
    }
  }

  /** Mints a link and keeps it in memory for the administrator to copy. */
  async createReset(userId: number, expiresInDays?: number): Promise<CreatedResetView> {
    this.requireOnline();
    this._saving.set(true);
    try {
      const created = await this.api.invoke(createReset, { userId, body: { expiresInDays } });
      this._created.set(created);
      await this.loadResets(userId);
      return created;
    } finally {
      this._saving.set(false);
    }
  }

  /** Forgets the one-time token. Called when the administrator dismisses it. */
  clearCreated(): void {
    this._created.set(null);
  }

  async loadResets(userId: number): Promise<void> {
    this.requireOnline();
    this._resets.set(await this.api.invoke(listResets, { userId }));
    this._resetsForUserId = userId;
  }

  /** Closes the links panel without leaving the last account's list on screen. */
  closeResets(): void {
    this._resets.set([]);
    this._resetsForUserId = null;
    this._created.set(null);
  }

  async revokeReset(userId: number, resetId: number): Promise<void> {
    this.requireOnline();
    this._saving.set(true);
    try {
      await this.api.invoke(revokeReset, { userId, resetId });
      await this.loadResets(userId);
    } finally {
      this._saving.set(false);
    }
  }

  /**
   * What a reset link says before it is used. Anonymous — its whole audience is
   * people who cannot sign in — so it deliberately does not check connectivity
   * against a session that does not exist.
   */
  previewReset(token: string): Promise<ResetPreview> {
    return this.api.invoke(previewReset, { token });
  }

  /** Sets the password and burns the link. Does not sign anybody in. */
  redeemReset(token: string, newPassword: string): Promise<void> {
    return this.api.invoke(redeemReset, { token, body: { newPassword } });
  }

  clear(): void {
    this._accounts.set([]);
    this._resets.set([]);
    this._created.set(null);
    this._resetsForUserId = null;
  }

  private requireOnline(): void {
    if (!this.connectivity.online()) {
      throw new OfflineError();
    }
  }
}
