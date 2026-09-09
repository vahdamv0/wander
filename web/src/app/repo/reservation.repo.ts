import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Api,
  BookingImportResult,
  ReservationRequest,
  TripReservations,
  createReservation,
  deleteReservation,
  importReservations,
  listReservations,
  updateReservation,
} from '../api';
import { Connectivity } from '../core/connectivity';
import { OfflineError } from '../core/errors';
import { OfflineCache, cacheKeys } from '../core/offline-cache';
import { SessionStore } from '../core/session.store';

/**
 * One trip's bookings, soonest first.
 *
 * The order comes from the server and is by *instant*, which is the only
 * ordering that stays right when a trip crosses a zone — so this repo keeps the
 * list as given rather than sorting it again on anything local.
 */
@Injectable({ providedIn: 'root' })
export class ReservationRepo {
  private readonly api = inject(Api);
  private readonly cache = inject(OfflineCache);
  private readonly session = inject(SessionStore);
  private readonly connectivity = inject(Connectivity);

  private readonly _trip = signal<TripReservations | null>(null);
  private readonly _loading = signal(false);
  /** When this came from the device rather than the server. Null when fresh. */
  private readonly _savedAt = signal<number | null>(null);
  private readonly _saving = signal(false);
  private readonly _importing = signal(false);

  readonly loading = this._loading.asReadonly();
  readonly savedAt = this._savedAt.asReadonly();
  readonly saving = this._saving.asReadonly();
  readonly importing = this._importing.asReadonly();

  readonly trip = computed(() => this._trip()?.trip ?? null);
  readonly reservations = computed(() => this._trip()?.reservations ?? []);
  readonly canEdit = computed(() => {
    const role = this.trip()?.myRole;
    return role === 'OWNER' || role === 'EDITOR';
  });

  async load(tripId: number): Promise<void> {
    if (this._trip()?.trip.id !== tripId) {
      this._trip.set(null);
    }
    this._loading.set(true);
    try {
      const read = await this.cache.readThrough(this.session.user()?.id ?? null,
        cacheKeys.reservations(tripId), () => this.api.invoke(listReservations, { tripId }));
      this._trip.set(read.body);
      this._savedAt.set(read.savedAt);
    } finally {
      this._loading.set(false);
    }
  }

  async add(tripId: number, body: ReservationRequest): Promise<void> {
    await this.write(tripId, () => this.api.invoke(createReservation, { tripId, body }));
  }

  async update(tripId: number, reservationId: number, body: ReservationRequest): Promise<void> {
    await this.write(tripId, () =>
      this.api.invoke(updateReservation, { tripId, reservationId, body }),
    );
  }

  async remove(tripId: number, reservationId: number): Promise<void> {
    await this.write(tripId, () => this.api.invoke(deleteReservation, { tripId, reservationId }));
  }

  /**
   * Reads a confirmation file into draft bookings.
   *
   * **Not a write**, which is why it does not go through `write` and does not
   * reload afterwards: the server parses and answers with candidates, and
   * nothing is stored until the ordinary `add` saves one. So there is no `saving`
   * flag to raise, no list to re-read, and nothing to undo if the parse is wrong.
   * It has its own `importing` signal because the page has to be able to say
   * "reading…" without the row controls thinking a save is in flight.
   *
   * There is deliberately **no `OfflineCache`** here. Parsing happens on the
   * server, so this cannot work from a saved copy, and a cached result would be
   * last week's file — `requireOnline` refuses it outright instead, as the writes
   * do.
   *
   * The argument must be a `File` rather than a `Blob`. The generated client
   * puts it through `FormData.set`, which only carries a filename for a `File` —
   * a plain `Blob` arrives named "blob", and the server picks its reader from the
   * extension, so it would refuse every upload.
   */
  async importFile(tripId: number, file: File): Promise<BookingImportResult> {
    this.requireOnline();
    this._importing.set(true);
    try {
      return await this.api.invoke(importReservations, { tripId, body: { file } });
    } finally {
      this._importing.set(false);
    }
  }


  /** Nothing is queued offline, so a write that cannot be sent is refused outright. */
  private requireOnline(): void {
    if (!this.connectivity.online()) {
      throw new OfflineError();
    }
  }

  private async write(tripId: number, call: () => Promise<unknown>): Promise<void> {
    this.requireOnline();
    this._saving.set(true);
    try {
      await call();
      await this.load(tripId);
    } finally {
      this._saving.set(false);
    }
  }
}
