import { Component, computed, inject, signal } from '@angular/core';
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
 * Deliberately small. It lists accounts, takes one out of service, and mints a
 * password reset link. It is not a window into anybody's trips: an administrator
 * of this instance has authority over accounts, and trip access still goes
 * through the same membership check it does for everybody.
 */
@Component({
  selector: 'app-admin',
  templateUrl: './admin.html',
})
export class AdminPage {
  private readonly admin = inject(AdminRepo);
  private readonly session = inject(SessionStore);

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

  protected async revokeReset(userId: number, resetId: number): Promise<void> {
    await this.guard(() => this.admin.revokeReset(userId, resetId));
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
