import { Injectable, computed, inject, signal } from '@angular/core';
import { Api, ExchangeRateView, getExchangeRate, listCurrencies } from '../api';

/**
 * Exchange rates, for the expense form.
 *
 * **The third repo with no `OfflineCache`**, after `InviteRepo` and `AdminRepo`,
 * and the reasoning is the weather one rather than theirs. A rate is only ever
 * wanted while somebody is filling in a form, and a form cannot be submitted
 * offline anyway — writes are refused rather than queued. So a cached rate could
 * only ever be shown on a page that has no way to use it.
 *
 * The currency list is held for the session because it is a listing, not a fact
 * about a day: it changes when a central bank joins or leaves, the server holds
 * it for a day at a time, and asking again on every keystroke would be asking a
 * question nobody has changed the answer to.
 */
@Injectable({ providedIn: 'root' })
export class FxRepo {
  private readonly api = inject(Api);

  private readonly _currencies = signal<string[]>([]);
  private readonly _lookupEnabled = signal(true);
  private readonly _loaded = signal(false);
  private readonly _quoting = signal(false);

  readonly currencies = this._currencies.asReadonly();
  /** Whether a rate can be fetched at all; false means the form asks for one. */
  readonly lookupEnabled = this._lookupEnabled.asReadonly();
  readonly quoting = this._quoting.asReadonly();
  readonly loaded = this._loaded.asReadonly();

  /**
   * What the picker offers, and it falls back to the browser's own ISO list.
   *
   * `Intl.supportedValuesOf` is the same trick `money.ts` uses for exponents and
   * `zones.ts` uses for timezones: the browser ships the table, so this project
   * does not carry one that would be wrong somewhere. It is the *fallback*
   * rather than the source, because the server's list is the one that says which
   * currencies a rate actually exists for — but an instance with the lookup off
   * still has to let somebody pick the yen and type a rate.
   */
  readonly options = computed(() => {
    const supported = this._currencies();
    if (supported.length) {
      return supported;
    }
    try {
      return Intl.supportedValuesOf('currency');
    } catch {
      return [];
    }
  });

  async load(): Promise<void> {
    if (this._loaded()) {
      return;
    }
    try {
      const supported = await this.api.invoke(listCurrencies);
      this._currencies.set(supported.currencies);
      this._lookupEnabled.set(supported.lookupEnabled);
    } catch {
      // The picker falls back to the browser's list, and a rate gets typed. Not
      // being able to list currencies is not a reason to refuse an expense.
      this._currencies.set([]);
    } finally {
      this._loaded.set(true);
    }
  }

  /**
   * The rate for a pair on a day, or null when there is not one.
   *
   * Null rather than a thrown error on purpose: every reason this fails is a
   * reason to show the rate box instead — a date with nothing published, a
   * currency the upstream does not carry, an instance with no outbound network.
   * The form's job in all three cases is the same, so they arrive the same way.
   */
  async quote(
    from: string,
    to: string,
    on: string,
    amountMinor?: number,
  ): Promise<ExchangeRateView | null> {
    if (!from || !to || !on || from === to) {
      return null;
    }
    this._quoting.set(true);
    try {
      // The amount travels so the answer carries what it converts to. That
      // number is the server's arithmetic, because this side does none.
      return await this.api.invoke(getExchangeRate, { from, to, on, amountMinor });
    } catch {
      return null;
    } finally {
      this._quoting.set(false);
    }
  }
}
