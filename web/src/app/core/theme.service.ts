import { Injectable, effect, signal } from '@angular/core';

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

  readonly preference = this._preference.asReadonly();

  constructor() {
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
