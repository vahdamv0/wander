import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Api,
  ReservationRequest,
  TripReservations,
  createReservation,
  deleteReservation,
  listReservations,
  updateReservation,
} from '../api';

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

  private readonly _trip = signal<TripReservations | null>(null);
  private readonly _loading = signal(false);
  private readonly _saving = signal(false);

  readonly loading = this._loading.asReadonly();
  readonly saving = this._saving.asReadonly();

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
      this._trip.set(await this.api.invoke(listReservations, { tripId }));
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

  private async write(tripId: number, call: () => Promise<unknown>): Promise<void> {
    this._saving.set(true);
    try {
      await call();
      await this.load(tripId);
    } finally {
      this._saving.set(false);
    }
  }
}
