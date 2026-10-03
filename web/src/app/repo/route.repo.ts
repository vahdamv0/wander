import { Injectable, inject, signal } from '@angular/core';
import {
  Api,
  DayRouteLegsView,
  DayRoutePreview,
  PreviewDayRoute$Params,
  estimateDayRouteLegs,
  previewDayRoute,
} from '../api';

/** The three profiles, taken from the generated parameter rather than retyped. */
export type RouteProfile = NonNullable<PreviewDayRoute$Params['profile']>;

/**
 * A proposed order for one day's stops.
 *
 * **No `OfflineCache`**, the third repo without one after `InviteRepo` and
 * `AdminRepo`, and for `ReservationRepo.importFile`'s reason rather than
 * theirs: the answer comes from a routing engine, so a cached one would be a
 * proposal about a day that has since changed — and applying a stale order is
 * exactly the mistake the server's "the ids must match this day" check exists
 * to catch. With no connection there is no proposal, and the itinerary is
 * unaffected.
 *
 * It holds **one** preview at a time, keyed by its day. Two days' proposals on
 * screen at once would be two things to keep in step with an itinerary that
 * live sync can change under both of them; one is a thing you look at, decide,
 * and dismiss.
 */
@Injectable({ providedIn: 'root' })
export class RouteRepo {
  private readonly api = inject(Api);

  private readonly _preview = signal<DayRoutePreview | null>(null);
  private readonly _legs = signal<DayRouteLegsView | null>(null);
  private readonly _estimating = signal<string | null>(null);
  private readonly _estimateError = signal(false);
  private requestId = 0;
  /** Not `saving`: this writes nothing, and the page's controls stay live. */
  private readonly _previewing = signal<string | null>(null);

  readonly preview = this._preview.asReadonly();
  readonly legs = this._legs.asReadonly();
  readonly estimating = this._estimating.asReadonly();
  readonly estimateError = this._estimateError.asReadonly();
  readonly previewing = this._previewing.asReadonly();

  /**
   * Ask for a better order for one day. Throws on failure — unlike the
   * forecast, this was asked for by somebody pressing a button, and a button
   * that silently does nothing is worse than an error line.
   */
  async propose(tripId: number, date: string, profile: RouteProfile): Promise<void> {
    this._previewing.set(date);
    try {
      const preview = await this.api.invoke(previewDayRoute, { tripId, date, profile });
      this._preview.set(preview);
    } finally {
      this._previewing.set(null);
    }
  }

  /** Travel estimates for the current order, without asking the optimiser to reorder it. */
  async estimate(tripId: number, date: string, profile: RouteProfile): Promise<void> {
    const requestId = ++this.requestId;
    this._legs.set(null);
    this._estimateError.set(false);
    this._estimating.set(date);
    try {
      const legs = await this.api.invoke(estimateDayRouteLegs, { tripId, date, profile });
      if (requestId === this.requestId) {
        this._legs.set(legs);
      }
    } catch {
      if (requestId === this.requestId) {
        this._estimateError.set(true);
      }
    } finally {
      if (requestId === this.requestId) {
        this._estimating.set(null);
      }
    }
  }

  clearEstimates(): void {
    this.requestId++;
    this._legs.set(null);
    this._estimating.set(null);
    this._estimateError.set(false);
  }

  /** Dismissing proposals and stale estimates. */
  clear(): void {
    this._preview.set(null);
    this.clearEstimates();
  }
}
