import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { messageOf } from '../../core/errors';
import { SessionStore } from '../../core/session.store';

/**
 * Your own account: your name, and your password.
 *
 * It is a page rather than a dialog because it is reached from the header on
 * every screen, and because a password field inside a modal over a trip is the
 * kind of thing a password manager fills badly.
 */
@Component({
  selector: 'app-account',
  imports: [FormsModule],
  templateUrl: './account.html',
})
export class AccountPage {
  private readonly session = inject(SessionStore);

  protected readonly user = this.session.user;

  /**
   * A linked signal rather than a plain one seeded in the constructor: it
   * re-syncs when the stored identity changes, so a successful save leaves the
   * field showing what the server actually recorded (trimmed, in particular)
   * instead of what was typed.
   */
  protected readonly displayName = linkedSignal(() => this.user()?.displayName ?? '');
  protected readonly savingName = signal(false);
  protected readonly nameError = signal<string | null>(null);
  protected readonly nameSaved = signal(false);

  protected readonly canSaveName = computed(() => {
    const next = this.displayName().trim();
    return !this.savingName() && next.length > 0 && next !== this.user()?.displayName;
  });

  protected async saveName(): Promise<void> {
    if (!this.canSaveName()) {
      return;
    }
    this.savingName.set(true);
    this.nameError.set(null);
    this.nameSaved.set(false);
    try {
      await this.session.updateDisplayName(this.displayName().trim());
      this.nameSaved.set(true);
    } catch (err) {
      this.nameError.set(messageOf(err));
    } finally {
      this.savingName.set(false);
    }
  }

  protected readonly currentPassword = signal('');
  protected readonly newPassword = signal('');
  protected readonly confirmPassword = signal('');

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal(false);

  /**
   * The confirmation field is checked here and nowhere else — the server has no
   * business knowing a form typed the password twice. It is the one piece of
   * validation this page owns; length and correctness belong to the server,
   * which is the only side that can be sure of either.
   */
  protected readonly mismatch = computed(
    () => this.confirmPassword().length > 0 && this.newPassword() !== this.confirmPassword(),
  );

  protected readonly canSubmit = computed(
    () =>
      !this.busy() &&
      this.currentPassword().length > 0 &&
      this.newPassword().length >= 10 &&
      this.newPassword() === this.confirmPassword(),
  );

  protected async submit(): Promise<void> {
    if (!this.canSubmit()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.done.set(false);
    try {
      await this.session.changePassword(this.currentPassword(), this.newPassword());
      this.done.set(true);
      // Clear the fields on success: they hold a live credential, and leaving
      // them filled invites a second submit that would now fail as a reuse.
      this.currentPassword.set('');
      this.newPassword.set('');
      this.confirmPassword.set('');
    } catch (err) {
      this.error.set(messageOf(err));
    } finally {
      this.busy.set(false);
    }
  }
}
