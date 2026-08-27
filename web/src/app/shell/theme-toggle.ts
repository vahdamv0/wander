import { Component, inject } from '@angular/core';
import { ThemeService } from '../core/theme.service';

/** One button, three states. The icon shows what is currently in effect. */
@Component({
  selector: 'app-theme-toggle',
  template: `
    <button
      type="button"
      class="btn-ghost !px-2"
      [attr.aria-label]="'Theme: ' + theme.preference() + '. Click to change.'"
      [title]="'Theme: ' + theme.preference()"
      (click)="theme.cycle()"
    >
      @switch (theme.preference()) {
        @case ('light') {
          <!-- sun -->
          <svg class="size-[18px]" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="1.7" stroke-linecap="round" aria-hidden="true">
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" />
          </svg>
        }
        @case ('dark') {
          <!-- moon -->
          <svg class="size-[18px]" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="1.7" stroke-linecap="round" aria-hidden="true">
            <path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a7 7 0 1 0 10.5 10.5Z" />
          </svg>
        }
        @default {
          <!-- half-filled circle: following the OS -->
          <svg class="size-[18px]" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="1.7" aria-hidden="true">
            <circle cx="12" cy="12" r="8.5" />
            <path d="M12 3.5a8.5 8.5 0 0 1 0 17Z" fill="currentColor" stroke="none" />
          </svg>
        }
      }
    </button>
  `,
})
export class ThemeToggle {
  protected readonly theme = inject(ThemeService);
}
