import { Injectable, inject, signal } from '@angular/core';
import { Api, CreateTripRequest, TripSummary, create, list } from '../api';

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

  private readonly _trips = signal<TripSummary[]>([]);
  private readonly _loading = signal(false);

  readonly trips = this._trips.asReadonly();
  readonly loading = this._loading.asReadonly();

  async refresh(): Promise<void> {
    this._loading.set(true);
    try {
      this._trips.set(await this.api.invoke(list));
    } finally {
      this._loading.set(false);
    }
  }

  async create(body: CreateTripRequest): Promise<TripSummary> {
    const created = await this.api.invoke(create, { body });
    // Server response wins over a guessed local shape — it carries the id and
    // the derived dayCount.
    this._trips.update((trips) => [created, ...trips]);
    return created;
  }
}
