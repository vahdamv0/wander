import { Injectable, inject, signal } from '@angular/core';
import { Connectivity } from '../core/connectivity';
import { OfflineError } from '../core/errors';
import { OfflineCache, cacheKeys } from '../core/offline-cache';
import { SessionStore } from '../core/session.store';
import {
  Api,
  CreateTripRequest,
  TripSummary,
  UpdateTripRequest,
  create,
  list,
  updateTrip,
} from '../api';

/**
 * The repo layer: components talk to this, never to HTTP directly.
 *
 * Today it is a thin pass-through to the generated client. That is the point —
 * when offline support arrives, reading from IndexedDB and queueing writes
 * happens in here, and no component changes.
 */
@Injectable({ providedIn: 'root' })
export class TripRepo {
  private readonly api = inject(Api);
  private readonly cache = inject(OfflineCache);
  private readonly session = inject(SessionStore);
  private readonly connectivity = inject(Connectivity);

  private readonly _trips = signal<TripSummary[]>([]);
  private readonly _loading = signal(false);
  /** When this list came from the device rather than the server. Null when fresh. */
  private readonly _savedAt = signal<number | null>(null);

  readonly trips = this._trips.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly savedAt = this._savedAt.asReadonly();

  async refresh(): Promise<void> {
    this._loading.set(true);
    try {
      const read = await this.cache.readThrough(this.session.user()?.id ?? null,
        cacheKeys.trips(), () => this.api.invoke(list));
      this._trips.set(read.body);
      this._savedAt.set(read.savedAt);
    } finally {
      this._loading.set(false);
    }
  }

  /** Nothing is queued offline, so a write that cannot be sent is refused outright. */
  private requireOnline(): void {
    if (!this.connectivity.online()) {
      throw new OfflineError();
    }
  }

  /**
   * Rewrites a trip. Owner only, and the server may refuse: shortening a range
   * over places or notes comes back 409 with a message naming them, which is
   * the useful thing to show rather than something vaguer.
   */
  async update(tripId: number, body: UpdateTripRequest): Promise<TripSummary> {
    this.requireOnline();
    const updated = await this.api.invoke(updateTrip, { tripId, body });
    // The list may not be loaded — this is usually called from the trip page —
    // so patch it only where the trip is actually present.
    this._trips.update((trips) =>
      trips.map((trip) => (trip.id === tripId ? updated : trip)),
    );
    return updated;
  }

  async create(body: CreateTripRequest): Promise<TripSummary> {
    this.requireOnline();
    const created = await this.api.invoke(create, { body });
    // Server response wins over a guessed local shape — it carries the id and
    // the derived dayCount.
    this._trips.update((trips) => [created, ...trips]);
    return created;
  }
}
