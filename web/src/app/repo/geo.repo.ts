import { Injectable, computed, inject, signal } from '@angular/core';
import { Api, PlaceSuggestion, searchPlaces } from '../api';
import { InstanceConfigStore } from '../core/instance-config.store';

/** Below this the server rejects the query, so there is no point sending it. */
const MIN_QUERY_LENGTH = 3;

/**
 * Place search, one query at a time.
 *
 * Two things here exist because the geocoder is a shared, rate-limited service
 * rather than our own database:
 *
 *  - a request counter, so a slow answer to "sag" cannot overwrite the results
 *    for "sagrada" that the user is already looking at;
 *  - a 503 (search turned off on this instance) is remembered, so the page can
 *    hide the search box for good rather than asking again on every keystroke.
 *
 * Debouncing lives in the component, next to the keystrokes it is throttling.
 */
@Injectable({ providedIn: 'root' })
export class GeoRepo {
  private readonly api = inject(Api);
  private readonly config = inject(InstanceConfigStore);

  private readonly _results = signal<PlaceSuggestion[]>([]);
  private readonly _searching = signal(false);
  private readonly _error = signal<string | null>(null);
  /** Set when the server answers 503 — a belt to the config's braces. */
  private readonly _refused = signal(false);

  readonly results = this._results.asReadonly();
  readonly searching = this._searching.asReadonly();
  readonly error = this._error.asReadonly();
  /**
   * Whether to offer search at all. Available until told otherwise: the config
   * arrives a moment after the shell mounts, and hiding the box in the meantime
   * would make it flicker into existence.
   */
  readonly available = computed(
    () => !this._refused() && (!this.config.loaded() || this.config.searchEnabled()),
  );

  /** Which request is current. A reply from an older one is dropped. */
  private latest = 0;

  async search(query: string): Promise<void> {
    const trimmed = query.trim();
    if (trimmed.length < MIN_QUERY_LENGTH) {
      this.clear();
      return;
    }

    const request = ++this.latest;
    this._searching.set(true);
    this._error.set(null);
    try {
      const results = await this.api.invoke(searchPlaces, { q: trimmed });
      if (request === this.latest) {
        this._results.set(results);
      }
    } catch (err: unknown) {
      if (request !== this.latest) {
        return;
      }
      const status = (err as { status?: number } | null)?.status;
      this._results.set([]);
      if (status === 503) {
        // Nothing to retry: this instance does not search at all.
        this._refused.set(true);
      } else if (status === 429) {
        this._error.set('Searching a little fast — try again in a moment.');
      } else {
        this._error.set('Place search is unavailable right now.');
      }
    } finally {
      if (request === this.latest) {
        this._searching.set(false);
      }
    }
  }

  clear(): void {
    // Bumping the counter drops whatever is still in flight.
    this.latest++;
    this._results.set([]);
    this._searching.set(false);
    this._error.set(null);
  }
}
