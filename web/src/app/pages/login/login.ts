import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api, getSignInConfig } from '../../api';
import { messageOf } from '../../core/errors';
import { SessionStore } from '../../core/session.store';
import { BrandMark } from '../../shell/brand-mark';

@Component({
  selector: 'app-login',
  imports: [FormsModule, RouterLink, BrandMark],
  templateUrl: './login.html',
})
export class LoginPage {
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(Api);

  /**
   * Where to go once signed in — whatever the guard was protecting, or the trip
   * list. Only ever a path from this application's own router: taking an absolute
   * URL here would turn the login page into an open redirect, which is a
   * phishing primitive on any site that has one.
   */
  private returnUrl(): string {
    const requested = this.route.snapshot.queryParamMap.get('returnUrl');
    return requested && requested.startsWith('/') && !requested.startsWith('//')
      ? requested
      : '/trips';
  }

  /**
   * The invitation this sign-in is on the way to, if it is on the way to one.
   *
   * An instance with sign-ups switched off still admits the holder of a live
   * link — otherwise turning them off would break every invitation, since
   * accepting one needs an account and nothing here sends mail to make one. The
   * token is the authorisation, so it has to travel with the registration; the
   * only place it exists at this point is the `returnUrl` the guard put here on
   * the way past.
   *
   * Read from the snapshot once, like `returnUrl` — this page is not reused
   * across navigations.
   */
  private readonly inviteToken = signal(inviteTokenIn(this.returnUrl()));

  /**
   * Whether to offer a sign-up at all: this instance accepts them, or this
   * visitor is holding an invitation that says otherwise.
   *
   * The token is not checked here — the server does that, and a made-up one is
   * refused with the same 403 as no token at all. Offering the form to somebody
   * whose link turns out to be spent is the better failure: they get the
   * server's answer instead of a page with no way forward on it.
   */
  /** Whether this visitor arrived holding a link, which the form says out loud. */
  protected readonly invited = computed(() => this.inviteToken() !== null);

  protected readonly canRegister = computed(
    () => this.registrationEnabled() === true || this.inviteToken() !== null,
  );

  /**
   * Whether this instance accepts sign-ups. Null until the server says, and the
   * offer stays hidden until then.
   *
   * That is the opposite of the rule the signed-in shell follows — features there
   * treat "not answered yet" as available so nothing flickers into existence —
   * and deliberately so. The whole point of this fix is not to offer registration
   * on an instance that forbids it, and a "Create one" link that vanishes as
   * somebody reaches for it is worse than one that appears a moment late.
   */
  protected readonly registrationEnabled = signal<boolean | null>(null);

  /**
   * Where this instance's source lives. Empty until the config answers, and
   * empty when the operator cleared it — both draw nothing.
   *
   * This page carries the link as well as the account menu, and the licence is
   * the reason rather than symmetry: AGPL-3.0 section 13 owes source to everyone
   * *interacting with the instance over a network*, and on a public one most of
   * those people get exactly this far and no further. A link that only existed
   * behind sign-in would miss the audience the clause is written for.
   */
  protected readonly sourceUrl = signal('');

  /**
   * Whether this instance can mail a reset link. Null until the server says, and
   * the offer stays hidden until then — `registrationEnabled`'s rule, not the
   * shell's, and for a sharper version of the same reason. The person who clicks
   * this is already locked out; offering them a route that turns out not to
   * exist is worse than making them ask the administrator directly.
   */
  protected readonly passwordResetEnabled = signal<boolean | null>(null);

  /**
   * The published demo credentials, or empty on an instance without a demo.
   *
   * Shown rather than hidden behind a "sign in as demo" button that posts them
   * invisibly: somebody evaluating a self-hosted project is entitled to see what
   * they are signing in as, and a visible email is also the thing that explains
   * the READ ONLY badge they are about to meet.
   */
  protected readonly demoEmail = signal('');
  protected readonly demoPassword = signal('');
  protected readonly demoOffered = computed(
    () => this.demoEmail() !== '' && this.demoPassword() !== '',
  );

  protected readonly mode = signal<'login' | 'register'>('login');
  protected readonly email = signal('');
  protected readonly displayName = signal('');
  protected readonly password = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  constructor() {
    // Also the GET that hands this client its XSRF-TOKEN cookie, which the first
    // POST of a session needs and cannot get any other way.
    void this.loadSignInConfig();
  }

  private async loadSignInConfig(): Promise<void> {
    try {
      const config = await this.api.invoke(getSignInConfig);
      this.registrationEnabled.set(config.registrationEnabled);
      this.sourceUrl.set(config.sourceUrl);
      this.demoEmail.set(config.demoEmail);
      this.demoPassword.set(config.demoPassword);
      this.passwordResetEnabled.set(config.passwordResetEnabled);
    } catch {
      // Unreachable config is not a reason to strand somebody who has an
      // account: the form still works, and the server refuses a sign-up anyway
      // if it is switched off.
      this.registrationEnabled.set(false);
      this.passwordResetEnabled.set(false);
    }
  }

  /**
   * Fills the form with the demo credentials and signs in. It fills rather than
   * posting straight past the form so the visitor can see what they are using,
   * and so a failure lands on the same error line as any other sign-in.
   */
  protected async useDemo(): Promise<void> {
    this.mode.set('login');
    this.email.set(this.demoEmail());
    this.password.set(this.demoPassword());
    await this.submit();
  }

  protected async submit(): Promise<void> {
    this.error.set(null);
    this.busy.set(true);
    try {
      if (this.mode() === 'login') {
        await this.session.login(this.email(), this.password());
      } else {
        await this.session.register(
          this.email(),
          this.displayName(),
          this.password(),
          this.inviteToken(),
        );
      }
      await this.router.navigateByUrl(this.returnUrl());
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'Something went wrong. Please try again.'));
    } finally {
      this.busy.set(false);
    }
  }

  protected toggleMode(): void {
    this.mode.update((m) => (m === 'login' ? 'register' : 'login'));
    this.error.set(null);
  }
}

/**
 * The token out of an `/invite/<token>` path, or null for anything else.
 *
 * Deliberately narrow: this decides whether to offer a sign-up form on an
 * instance that has them switched off, so it matches that one route and nothing
 * that merely resembles it.
 */
function inviteTokenIn(path: string): string | null {
  const match = /^\/invite\/([^/?#]+)$/.exec(path);
  return match ? decodeURIComponent(match[1]) : null;
}
