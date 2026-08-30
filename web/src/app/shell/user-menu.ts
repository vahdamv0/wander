import { Component, ElementRef, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { InstanceConfigStore } from '../core/instance-config.store';
import { SessionStore } from '../core/session.store';

/**
 * The account menu behind the avatar: who you are, and the two ways out of it.
 *
 * The first item names the page rather than one of the two things on it — it
 * changes your password *and* renames you, so "Change password" would have been
 * half a label.
 *
 * It owns signing out rather than the shell doing it, because the menu is now
 * the only way to reach it — an action and the control that offers it belong
 * together.
 *
 * The identity line at the top is not decoration. The email used to live only in
 * the avatar's `title`, which does not exist on a touch device, and on a
 * self-hosted instance somebody may well hold both an admin account and their
 * own — "which one am I in" needs an answer you can see.
 */
@Component({
  selector: 'app-user-menu',
  imports: [RouterLink],
  host: {
    class: 'relative',
    // Closing on an outside click is what makes this behave like a menu rather
    // than a panel that has to be dismissed by the control that opened it.
    '(document:click)': 'onDocumentClick($event)',
    '(document:keydown.escape)': 'close()',
  },
  template: `
    <button
      type="button"
      class="grid size-8 place-items-center rounded-full bg-accent-soft text-xs
             font-semibold text-accent transition-shadow hover:ring-2 hover:ring-accent"
      [class.ring-2]="open()"
      [class.ring-accent]="open()"
      aria-haspopup="menu"
      [attr.aria-expanded]="open()"
      [attr.aria-label]="'Account menu for ' + (user()?.displayName ?? '')"
      (click)="toggle()"
    >
      {{ initials(user()?.displayName) }}
    </button>

    @if (open()) {
      <div
        role="menu"
        class="absolute right-0 top-full z-20 mt-2 w-56 overflow-hidden rounded-control
               border border-border bg-surface shadow-lg"
      >
        <div class="border-b border-border px-3 py-2.5">
          <p class="truncate text-sm font-medium">{{ user()?.displayName }}</p>
          <!-- An address can be long and this menu is not; truncating beats
               wrapping into three lines, and the title carries the whole thing
               for anyone with a pointer. -->
          <p class="truncate text-xs text-muted" [title]="user()?.email">{{ user()?.email }}</p>
        </div>

        <a
          role="menuitem"
          routerLink="/account"
          class="block px-3 py-2 text-sm hover:bg-surface-2"
          (click)="close()"
        >
          Your account
        </a>

        <!-- Only for an administrator, and the route is guarded as well: a menu
             item nobody else can see is a courtesy, not a control. It sits here
             rather than in the header because administering the instance is a
             rare errand, and the header belongs to the trip you are planning. -->
        @if (isAdmin()) {
          <a
            role="menuitem"
            routerLink="/admin"
            class="block px-3 py-2 text-sm hover:bg-surface-2"
            (click)="close()"
          >
            Accounts
          </a>
        }

        <button
          role="menuitem"
          type="button"
          class="block w-full px-3 py-2 text-left text-sm hover:bg-surface-2"
          (click)="signOut()"
        >
          Sign out
        </button>

        <!-- What is running. Not a menu item — it is not actionable, so it must
             not be in the keyboard order or announced as something to choose.
             Drawn only once the config has answered, so nothing flickers. -->
        @if (versionLabel()) {
          <p class="border-t border-border px-3 py-2 text-center text-xs text-muted">
            <span class="rounded-full bg-surface-2 px-2 py-0.5">
              wander {{ versionLabel() }}
            </span>
          </p>
        }
      </div>
    }
  `,
})
export class UserMenu {
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly user = this.session.user;
  protected readonly isAdmin = computed(() => this.session.user()?.role === 'ADMIN');
  protected readonly versionLabel = inject(InstanceConfigStore).versionLabel;
  protected readonly open = signal(false);

  protected toggle(): void {
    this.open.update((value) => !value);
  }

  protected close(): void {
    this.open.set(false);
  }

  /**
   * Containment rather than a backdrop element: a full-screen backdrop would
   * swallow the first click anywhere else on the page, so dismissing the menu
   * and pressing the thing you were reaching for would take two clicks.
   */
  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }

  protected async signOut(): Promise<void> {
    this.close();
    await this.session.logout();
    await this.router.navigate(['/login']);
  }

  /** Initials for the avatar: "Vivek Madhav" → "VM". */
  protected initials(name: string | undefined): string {
    if (!name) {
      return '?';
    }
    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]!.toUpperCase())
      .join('');
  }
}
