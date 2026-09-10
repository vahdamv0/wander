import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AppUpdate } from '../core/app-update';
import { Connectivity } from '../core/connectivity';
import { InstanceConfigStore } from '../core/instance-config.store';
import { BrandMark } from './brand-mark';
import { ThemeToggle } from './theme-toggle';
import { UserMenu } from './user-menu';

/**
 * The signed-in frame: brand, primary nav, theme toggle, account menu, and the
 * routed page. Every authenticated route renders inside this, so a page only
 * ever writes its own content.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, BrandMark, ThemeToggle, UserMenu],
  templateUrl: './app-shell.html',
})
export class AppShell {
  private readonly config = inject(InstanceConfigStore);
  private readonly connectivity = inject(Connectivity);
  private readonly appUpdate = inject(AppUpdate);

  /**
   * Said once, in the frame, so every page inherits it. The pages themselves say
   * how old *their* copy is, which is the part that differs between them.
   */
  protected readonly online = this.connectivity.online;

  /**
   * Beside the offline notice rather than at the root: an installed window left
   * open for a fortnight is a signed-in window, and reaching any page outside
   * the shell involved a load, which is when ngsw picks up a build anyway.
   */
  protected readonly updateReady = this.appUpdate.ready;

  protected applyUpdate(): void {
    void this.appUpdate.apply();
  }

  constructor() {
    // Here rather than in an app initializer: the endpoint is authenticated, and
    // this shell only ever renders for someone signed in. Loaded once — the
    // store is a root singleton and these are instance settings, not user data.
    if (!this.config.loaded()) {
      void this.config.load();
    }
  }
}
