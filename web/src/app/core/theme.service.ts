import { Injectable, computed, effect, signal } from '@angular/core';

export type ThemePreference = 'system' | 'light' | 'dark';

const STORAGE_KEY = 'wander.theme';

/**
 * The theme preference, persisted per browser.
 *
 * 'system' removes the attribute entirely rather than resolving the OS setting
 * itself — that way the page keeps following the OS live, including a change
 * made while the tab is open.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly _preference = signal<ThemePreference>(readStored());
  /** Whether the OS is asking for dark, tracked live. */
  private readonly _systemDark = signal(prefersDark());

  readonly preference = this._preference.asReadonly();

  /**
   * Whether dark is actually in effect, which is not the same question as the
   * preference: 'system' has to be resolved against the OS, and the answer can
   * change while the tab is open.
   *
   * CSS never needs this — it has `prefers-color-scheme` — but the map does. A
   * vector style is chosen in TypeScript, and asking the DOM for
   * `[data-theme]` would answer "nothing" for the common case of following the
   * OS.
   */
  readonly isDark = computed(() => {
    const preference = this._preference();
    return preference === 'system' ? this._systemDark() : preference === 'dark';
  });

  constructor() {
    if (typeof window !== 'undefined' && window.matchMedia) {
      window
        .matchMedia('(prefers-color-scheme: dark)')
        .addEventListener('change', (event) => this._systemDark.set(event.matches));
    }

    effect(() => {
      const preference = this._preference();
      const root = document.documentElement;
      if (preference === 'system') {
        root.removeAttribute('data-theme');
      } else {
        root.setAttribute('data-theme', preference);
      }
      try {
        localStorage.setItem(STORAGE_KEY, preference);
      } catch {
        // Private mode, or storage blocked. The theme still applies for this
        // page; it just will not be remembered.
      }
    });
  }

  set(preference: ThemePreference): void {
    this._preference.set(preference);
  }

  /** Cycles system → light → dark → system, for a single toggle button. */
  cycle(): void {
    this._preference.update((current) =>
      current === 'system' ? 'light' : current === 'light' ? 'dark' : 'system',
    );
  }
}

function prefersDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia('(prefers-color-scheme: dark)').matches
    : false;
}

function readStored(): ThemePreference {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'light' || stored === 'dark' || stored === 'system') {
      return stored;
    }
  } catch {
    // Unreadable storage is not an error; fall through to the default.
  }
  return 'system';
}
