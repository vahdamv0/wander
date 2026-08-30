import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ResetPreview } from '../../api';
import { messageOf } from '../../core/errors';
import { AdminRepo } from '../../repo/admin.repo';

/**
 * Setting a password from a link an administrator sent.
 *
 * **The one page in this application that is outside the shell and outside
 * `authGuard`**, and it has to be: everybody who needs it is somebody who cannot
 * sign in. That is the difference from the invitation page, which sits behind
 * the guard precisely because its holder *can* register first — an invitation
 * needs an account, and this exists because an account has become unreachable.
 *
 * The token is not validated in the browser. A spent or expired link gets the
 * server's own answer with its own reason, which is better than a page that
 * decided for itself and left the holder nothing to act on.
 */
@Component({
  selector: 'app-reset',
  imports: [FormsModule],
  templateUrl: './reset.html',
})
export class ResetPage {
  private readonly admin = inject(AdminRepo);
  private readonly router = inject(Router);

  /** From the route — withComponentInputBinding is on. */
  readonly token = input.required<string>();

  protected readonly preview = signal<ResetPreview | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly done = signal(false);

  protected readonly newPassword = signal('');
  protected readonly confirmPassword = signal('');

  /**
   * Checked here and nowhere else — the server has no business knowing a form
   * typed the password twice. Length and guessability belong to the server,
   * which is the only side that can be sure of either.
   */
  protected readonly mismatch = computed(
    () => this.confirmPassword().length > 0 && this.newPassword() !== this.confirmPassword(),
  );

  protected readonly canSubmit = computed(
    () =>
      !this.saving() &&
      !!this.preview()?.usable &&
      this.newPassword().length >= 10 &&
      this.newPassword() === this.confirmPassword(),
  );

  constructor() {
    // Not an effect on the input: this runs once, and re-running it on any
    // signal read would re-fetch a link that is single-use.
    queueMicrotask(() => void this.loadPreview());
  }

  private async loadPreview(): Promise<void> {
    this.loading.set(true);
    try {
      this.preview.set(await this.admin.previewReset(this.token()));
    } catch (err: unknown) {
      // A token that does not exist is a 404, deliberately the same answer
      // whatever is wrong with it — so this message cannot be more specific
      // than the server was willing to be.
      this.error.set(messageOf(err, 'This reset link is not valid.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected async submit(): Promise<void> {
    if (!this.canSubmit()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    try {
      await this.admin.redeemReset(this.token(), this.newPassword());
      this.done.set(true);
      // The fields hold a live credential and the link is now spent.
      this.newPassword.set('');
      this.confirmPassword.set('');
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'This link could not be used.'));
      // It may have been revoked, used or expired while this page sat open, so
      // re-read rather than leaving a button that will fail again.
      await this.loadPreview();
    } finally {
      this.saving.set(false);
    }
  }

  /**
   * On to the sign-in form rather than straight into the application.
   *
   * Redeeming deliberately does not issue a session — typing the new password
   * once is what proves it took, and signing in whoever holds the link would be
   * a strictly larger thing than letting them set a password.
   */
  protected async goToLogin(): Promise<void> {
    await this.router.navigate(['/login']);
  }
}
