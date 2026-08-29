import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Api, getSignInConfig } from '../../api';
import { messageOf } from '../../core/errors';
import { SessionStore } from '../../core/session.store';
import { BrandMark } from '../../shell/brand-mark';

@Component({
  selector: 'app-login',
  imports: [FormsModule, BrandMark],
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
    } catch {
      // Unreachable config is not a reason to strand somebody who has an
      // account: the form still works, and the server refuses a sign-up anyway
      // if it is switched off.
      this.registrationEnabled.set(false);
    }
  }

  protected async submit(): Promise<void> {
    this.error.set(null);
    this.busy.set(true);
    try {
      if (this.mode() === 'login') {
        await this.session.login(this.email(), this.password());
      } else {
        await this.session.register(this.email(), this.displayName(), this.password());
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
