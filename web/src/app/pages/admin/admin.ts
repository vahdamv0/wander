import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AdminUserView } from '../../api';
import { messageOf } from '../../core/errors';
import { SessionStore } from '../../core/session.store';
import { AdminRepo } from '../../repo/admin.repo';

/**
 * Administering the instance's accounts.
 *
 * Reached from the account menu, and only by an administrator — `adminGuard`
 * keeps the route shut and `AdminController` refuses the requests regardless,
 * because a guard is a courtesy to the user and not a security control.
 *
 * Deliberately small. It lists accounts, takes one out of service, makes
 * somebody else an administrator, and mints a password reset link. It is not a
 * window into anybody's trips: an administrator of this instance has authority
 * over accounts, and trip access still goes through the same membership check it
 * does for everybody.
 */
@Component({
  selector: 'app-admin',
  templateUrl: './admin.html',
})
export class AdminPage {
  private readonly admin = inject(AdminRepo);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly accounts = this.admin.accounts;
  protected readonly resets = this.admin.resets;
  protected readonly loading = this.admin.loading;
  protected readonly saving = this.admin.saving;
  protected readonly created = this.admin.created;

  protected readonly error = signal<string | null>(null);
  protected readonly copied = signal(false);
  /** Which account's links panel is open, or null. */
  protected readonly openFor = signal<number | null>(null);
  /** Which account is being asked about before it is disabled. */
  protected readonly confirmingDisable = signal<number | null>(null);
  /** And before its role changes. Separate, so one row cannot be asked two questions. */
  protected readonly confirmingRole = signal<number | null>(null);

  protected readonly me = computed(() => this.session.user()?.id ?? null);

  constructor() {
    queueMicrotask(() => void this.guard(() => this.admin.load()));
  }

  /**
   * The full URL to send somebody.
   *
   * Built here rather than on the server, which knows only the path: behind the
   * reverse proxy it sees an internal hostname, so a link it composed would
   * point at something unreachable.
   */
  protected resetUrl(path: string): string {
    return new URL(path, window.location.origin).toString();
  }

  protected async toggleResets(userId: number): Promise<void> {
    if (this.openFor() === userId) {
      this.openFor.set(null);
      this.admin.closeResets();
      return;
    }
    this.copied.set(false);
    await this.guard(async () => {
      await this.admin.loadResets(userId);
      this.openFor.set(userId);
    });
  }

  protected async createReset(userId: number): Promise<void> {
    this.copied.set(false);
    await this.guard(async () => {
      await this.admin.createReset(userId);
      this.openFor.set(userId);
    });
  }

  protected async copyReset(path: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.resetUrl(path));
      this.copied.set(true);
    } catch {
      // Clipboard access can be refused, and the link is on screen to be
      // selected by hand — so this is not worth an error banner.
      this.copied.set(false);
    }
  }

  protected dismissCreated(): void {
    this.admin.clearCreated();
    this.copied.set(false);
  }

  /**
   * Which reset link's Revoke has been pressed once and is waiting to be meant.
   *
   * Revoking is the safe direction — the cost of a mistake is minting another
   * — but the person holding the link is by definition somebody who cannot
   * sign in, and they are not told it stopped working. It asks for the same
   * reason disabling an account does, two rows above it.
   */
  protected readonly confirmingRevoke = signal<number | null>(null);

  protected askRevokeReset(resetId: number): void {
    this.confirmingRevoke.set(resetId);
  }

  protected cancelRevokeReset(): void {
    this.confirmingRevoke.set(null);
  }

  protected async revokeReset(userId: number, resetId: number): Promise<void> {
    this.confirmingRevoke.set(null);
    await this.guard(() => this.admin.revokeReset(userId, resetId));
  }

  /**
   * Promoting and demoting both ask first, unlike enabling.
   *
   * Not caution for its own sake: either direction ends that person's sessions,
   * so somebody working in another window is signed out by a click made here.
   * And stepping down signs *you* out, which is worth being asked about once.
   */
  protected askRole(userId: number): void {
    this.confirmingRole.set(userId);
  }

  protected cancelRole(): void {
    this.confirmingRole.set(null);
  }

  protected roleQuestion(account: AdminUserView): string {
    if (account.id === this.me()) {
      return 'Step down and sign yourself out?';
    }
    return account.role === 'ADMIN'
      ? 'Remove admin and sign them out?'
      : 'Make admin and sign them out?';
  }

  protected roleAction(account: AdminUserView): string {
    if (account.id === this.me()) {
      return 'Step down';
    }
    return account.role === 'ADMIN' ? 'Remove admin' : 'Make admin';
  }

  protected async setRole(account: AdminUserView): Promise<void> {
    const next = account.role === 'ADMIN' ? 'USER' : 'ADMIN';
    // Stepping down ends the session making the request, so the list must not
    // be re-read afterwards and the client must stop believing it is signed in.
    const steppingDown = account.id === this.me() && next === 'USER';
    this.confirmingRole.set(null);
    await this.guard(async () => {
      await this.admin.setRole(account.id, next, !steppingDown);
      if (steppingDown) {
        // The POST *will* fail with a 401, because the server has already ended
        // this session — that is the whole point of the call that just
        // succeeded. `logout` clears the local identity and the cached trips in
        // its `finally` and then rethrows, so the failure has to be swallowed
        // here or a successful step-down reports an error and never redirects.
        await this.session.logout().catch(() => undefined);
        await this.router.navigate(['/login']);
      }
    });
  }

  /**
   * Disabling asks first; enabling does not.
   *
   * Not symmetry for its own sake — the two are not the same act. Disabling
   * signs somebody out of a session they may be in the middle of using and stops
   * their next sign-in, and the confirmation is inline where the button was,
   * like removing a place: there is no backdrop to dismiss by accident. Putting
   * an account back is undoing that, and asking before undoing something is
   * noise.
   */
  protected askDisable(userId: number): void {
    this.confirmingDisable.set(userId);
  }

  protected cancelDisable(): void {
    this.confirmingDisable.set(null);
  }

  protected async setDisabled(userId: number, disabled: boolean): Promise<void> {
    this.confirmingDisable.set(null);
    await this.guard(() => this.admin.setDisabled(userId, disabled));
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'PENDING':
        return 'Live';
      case 'USED':
        return 'Used';
      case 'REVOKED':
        return 'Revoked';
      case 'EXPIRED':
        return 'Expired';
      default:
        return status;
    }
  }

  private async guard(work: () => Promise<unknown>): Promise<void> {
    this.error.set(null);
    try {
      await work();
    } catch (err: unknown) {
      this.error.set(messageOf(err));
    }
  }
}
