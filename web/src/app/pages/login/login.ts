import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { SessionStore } from '../../core/session.store';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.html',
})
export class LoginPage {
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly mode = signal<'login' | 'register'>('login');
  protected readonly email = signal('');
  protected readonly displayName = signal('');
  protected readonly password = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected async submit(): Promise<void> {
    this.error.set(null);
    this.busy.set(true);
    try {
      if (this.mode() === 'login') {
        await this.session.login(this.email(), this.password());
      } else {
        await this.session.register(this.email(), this.displayName(), this.password());
      }
      await this.router.navigate(['/trips']);
    } catch (err: unknown) {
      this.error.set(messageOf(err));
    } finally {
      this.busy.set(false);
    }
  }

  protected toggleMode(): void {
    this.mode.update((m) => (m === 'login' ? 'register' : 'login'));
    this.error.set(null);
  }
}

/** Pulls the server's error envelope out, falling back to something readable. */
function messageOf(err: unknown): string {
  const body = (err as { error?: { message?: string } } | null)?.error;
  return body?.message ?? 'Something went wrong. Please try again.';
}
