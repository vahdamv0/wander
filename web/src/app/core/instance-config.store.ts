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
  private readonly _weatherEnabled = signal(false);
  /** Null until answered, so a form can tell "not yet" from a real choice. */
  private readonly _defaultCurrency = signal<string | null>(null);
  private readonly _loaded = signal(false);
  /**
   * What is running, for the account menu. Empty until answered — the chip is
   * drawn only once there is something to put in it, so nothing flickers.
   */
  private readonly _version = signal('');
  private readonly _buildRef = signal('');

  readonly map = this._map.asReadonly();
  readonly searchEnabled = this._searchEnabled.asReadonly();
  readonly weatherEnabled = this._weatherEnabled.asReadonly();
  readonly defaultCurrency = this._defaultCurrency.asReadonly();
  readonly loaded = this._loaded.asReadonly();
  readonly mapEnabled = computed(() => this._map()?.enabled === true);

  /**
   * "wander v1.2.0 · a1b2c3d", or the parts of it that exist. An image built
   * outside CI has no commit, and one built from an ordinary commit calls itself
   * "dev" — both are shown as they are rather than dressed up.
   */
  readonly versionLabel = computed(() => {
    const version = this._version();
    const build = this._buildRef();
    if (!version) {
      return '';
    }
    const name = version === 'dev' ? 'dev' : `v${version}`;
    return build ? `${name} · ${build}` : name;
  });

  async load(): Promise<void> {
    try {
      const config = await this.api.invoke(getInstanceConfig);
      this._map.set(config.map);
      this._searchEnabled.set(config.searchEnabled);
      this._weatherEnabled.set(config.weatherEnabled);
      this._defaultCurrency.set(config.defaultCurrency);
      this._version.set(config.version);
      this._buildRef.set(config.buildRef);
    } catch {
      // A signed-out visitor gets 401 here, which is not a failure — the
      // defaults stand, and the next sign-in loads it again.
      this._map.set(null);
      this._searchEnabled.set(false);
      this._weatherEnabled.set(false);
      this._defaultCurrency.set(null);
    } finally {
      this._loaded.set(true);
    }
  }
}
