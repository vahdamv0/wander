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
  /**
   * Where this instance's source lives — the licence's link, not a nicety. Empty
   * until answered and empty when the operator cleared it, and both draw
   * nothing, so there is no state where a dead link is offered.
   */
  private readonly _sourceUrl = signal('');
  /**
   * How often the shared demo account's trips are swept, in minutes. Zero on
   * every ordinary instance, and zero is what "not answered yet" looks like too
   * — both draw nothing, so there is no state in which the page promises a
   * deletion schedule it has not been told.
   */
  private readonly _demoSweepMinutes = signal(0);
  /**
   * Whether a booking can be read out of a confirmation file, and whether
   * *documents* can be — or only calendar attachments.
   *
   * Both default to **false**, which inverts this store's usual rule. Everything
   * else here is optimistic until answered so that nothing flickers into
   * existence; an Import button that appears and then errors is worse than one
   * that appears a beat late, and the whole purpose of the switch is the instance
   * that cannot. Same reasoning as `registrationEnabled` on the sign-in config.
   */
  private readonly _bookingImportEnabled = signal(false);
  private readonly _bookingDocumentImport = signal(false);
  /**
   * Whether this instance can look an exchange rate up.
   *
   * **True until answered**, which follows this store's usual optimism rather
   * than the booking-import exception above — and the difference is what being
   * wrong costs. There, being wrong offers a button that errors when pressed.
   * Here it offers a rate box a beat late on an instance with the lookup off,
   * on a form whose currency picker works either way: a foreign expense can
   * always be recorded, the only question is whether the rate arrives on its own
   * or has to be typed.
   */
  private readonly _rateLookupEnabled = signal(true);
  private readonly _rateAttribution = signal('');
  private readonly _rateAttributionUrl = signal('');
  /**
   * Whether a day can be sorted by route.
   *
   * **False until answered**, with booking import rather than with the rate
   * lookup: there is no public routing engine, so most instances do not have
   * one, and an Auto-sort button that appears and then answers 503 is worse
   * than one that appears a beat late.
   */
  private readonly _routingEnabled = signal(false);
  private readonly _routingAttribution = signal('');
  private readonly _routingAttributionUrl = signal('');

  readonly map = this._map.asReadonly();
  readonly searchEnabled = this._searchEnabled.asReadonly();
  readonly weatherEnabled = this._weatherEnabled.asReadonly();
  readonly defaultCurrency = this._defaultCurrency.asReadonly();
  readonly loaded = this._loaded.asReadonly();
  readonly mapEnabled = computed(() => this._map()?.enabled === true);
  readonly sourceUrl = this._sourceUrl.asReadonly();
  readonly demoSweepMinutes = this._demoSweepMinutes.asReadonly();
  readonly bookingImportEnabled = this._bookingImportEnabled.asReadonly();
  readonly bookingDocumentImport = this._bookingDocumentImport.asReadonly();
  readonly rateLookupEnabled = this._rateLookupEnabled.asReadonly();
  readonly rateAttribution = this._rateAttribution.asReadonly();
  readonly rateAttributionUrl = this._rateAttributionUrl.asReadonly();
  readonly routingEnabled = this._routingEnabled.asReadonly();
  readonly routingAttribution = this._routingAttribution.asReadonly();
  readonly routingAttributionUrl = this._routingAttributionUrl.asReadonly();

  /**
   * What the file picker will accept.
   *
   * Narrowed on an instance with no document extractor, because offering a PDF
   * there and answering "nothing recognised" would send somebody hunting for a
   * fault in a file that is perfectly fine.
   */
  readonly bookingImportAccept = computed(() =>
    this._bookingDocumentImport()
      ? '.eml,.pdf,.html,.htm,.txt,.ics,.pkpass'
      : '.eml,.ics',
  );

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
      this._sourceUrl.set(config.sourceUrl);
      this._demoSweepMinutes.set(config.demoSweepMinutes);
      this._bookingImportEnabled.set(config.bookingImportEnabled);
      this._bookingDocumentImport.set(config.bookingDocumentImport);
      this._rateLookupEnabled.set(config.rateLookupEnabled);
      this._rateAttribution.set(config.rateAttribution);
      this._rateAttributionUrl.set(config.rateAttributionUrl);
      this._routingEnabled.set(config.routingEnabled);
      this._routingAttribution.set(config.routingAttribution);
      this._routingAttributionUrl.set(config.routingAttributionUrl);
    } catch {
      // A signed-out visitor gets 401 here, which is not a failure — the
      // defaults stand, and the next sign-in loads it again.
      this._map.set(null);
      this._searchEnabled.set(false);
      this._weatherEnabled.set(false);
      this._defaultCurrency.set(null);
      this._sourceUrl.set('');
      this._demoSweepMinutes.set(0);
      this._bookingImportEnabled.set(false);
      this._bookingDocumentImport.set(false);
      this._routingEnabled.set(false);
      this._routingAttribution.set('');
      this._routingAttributionUrl.set('');
      // Left alone on a failed read, unlike the switches above: the optimistic
      // default is the useful one here, and clearing it would hide the rate box
      // from the instance most likely to still be able to fetch a rate.
      this._rateAttribution.set('');
      this._rateAttributionUrl.set('');
    } finally {
      this._loaded.set(true);
    }
  }
}
