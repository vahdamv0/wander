import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, getSignInConfig } from '../../api';
import { messageOf } from '../../core/errors';
import { AdminRepo } from '../../repo/admin.repo';
import { BrandMark } from '../../shell/brand-mark';

/**
 * Asking for a reset link, as somebody who cannot sign in.
 *
 * **The second page outside the shell and outside `authGuard`**, after
 * `/reset/:token`, and it is the same argument: its entire audience is people
 * without a session. The two are the halves of one journey — this one asks for
 * the link, that one spends it.
 *
 * **The success message is deliberately vague, and it must stay that way.** It
 * says "if that address has an account" rather than "sent", because the server
 * answers 204 identically for an address it knows, one it does not, and one
 * belonging to a disabled account. Wording that claimed delivery would turn this
 * page into the enumeration oracle the endpoint was written to avoid — the
 * client would be leaking what the server carefully withheld. It is also simply
 * true: this page has not been told whether anything was sent.
 */
@Component({
  selector: 'app-forgot',
  imports: [FormsModule, RouterLink, BrandMark],
  templateUrl: './forgot.html',
})
export class ForgotPage {
  private readonly admin = inject(AdminRepo);
  private readonly api = inject(Api);

  protected readonly email = signal('');
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal<string | null>(null);

  /**
   * Whether this instance can send at all. Null until the server says.
   *
   * Follows the login page's rule rather than the shell's: absent reads as
   * *un*available. Somebody who reached this page on an instance with no relay
   * needs to be told there is no self-service route, not shown a form that would
   * 404 — and the administrator's minted link is still the way back, which is
   * what the page says instead.
   */
  protected readonly available = signal<boolean | null>(null);

  protected readonly canSubmit = computed(
    () => !this.busy() && !this.sent() && this.email().trim().length > 3,
  );

  constructor() {
    // Also the GET that hands this client its XSRF-TOKEN cookie, which the POST
    // below needs and cannot get any other way.
    void this.loadConfig();
  }

  private async loadConfig(): Promise<void> {
    try {
      this.available.set((await this.api.invoke(getSignInConfig)).passwordResetEnabled);
    } catch {
      this.available.set(false);
    }
  }

  protected async submit(): Promise<void> {
    if (!this.canSubmit()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.admin.requestReset(this.email().trim());
      this.sent.set(true);
    } catch (err) {
      // A 429 is the realistic failure and it is worth showing: unlike the
      // outcome of the request, being throttled is a fact about this caller
      // rather than about whether the address exists.
      this.error.set(messageOf(err, 'Could not send a reset link. Try again in a few minutes.'));
    } finally {
      this.busy.set(false);
    }
  }
}
