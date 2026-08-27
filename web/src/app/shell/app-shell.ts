import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SessionStore } from '../core/session.store';
import { BrandMark } from './brand-mark';
import { ThemeToggle } from './theme-toggle';

/**
 * The signed-in frame: brand, primary nav, theme toggle, account menu, and the
 * routed page. Every authenticated route renders inside this, so a page only
 * ever writes its own content.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, BrandMark, ThemeToggle],
  templateUrl: './app-shell.html',
})
export class AppShell {
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly user = this.session.user;

  protected async signOut(): Promise<void> {
    await this.session.logout();
    await this.router.navigate(['/login']);
  }

  /** Initials for the account chip: "Vivek Madhav" → "VM". */
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
