import { Injectable, computed, inject, signal } from '@angular/core';
import { Api, MapConfig, getInstanceConfig } from '../api';

/**
 * What this instance allows, loaded once.
 *
 * Self-hosting is why this exists: the tile server and whether place search
 * works are the operator's choices, so the client asks instead of assuming. The
 * defaults below are the "no answer yet" state, deliberately conservative — a
 * map drawn from a guessed tile URL would send requests the operator meant to
 * prevent.
 */
@Injectable({ providedIn: 'root' })
export class InstanceConfigStore {
  private readonly api = inject(Api);

  private readonly _map = signal<MapConfig | null>(null);
  private readonly _searchEnabled = signal(false);
  /** Null until answered, so a form can tell "not yet" from a real choice. */
  private readonly _defaultCurrency = signal<string | null>(null);
  private readonly _loaded = signal(false);

  readonly map = this._map.asReadonly();
  readonly searchEnabled = this._searchEnabled.asReadonly();
  readonly defaultCurrency = this._defaultCurrency.asReadonly();
  readonly loaded = this._loaded.asReadonly();
  readonly mapEnabled = computed(() => this._map()?.enabled === true);

  async load(): Promise<void> {
    try {
      const config = await this.api.invoke(getInstanceConfig);
      this._map.set(config.map);
      this._searchEnabled.set(config.searchEnabled);
      this._defaultCurrency.set(config.defaultCurrency);
    } catch {
      // A signed-out visitor gets 401 here, which is not a failure — the
      // defaults stand, and the next sign-in loads it again.
      this._map.set(null);
      this._searchEnabled.set(false);
      this._defaultCurrency.set(null);
    } finally {
      this._loaded.set(true);
    }
  }
}
