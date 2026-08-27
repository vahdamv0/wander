import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Api,
  CreatePlaceRequest,
  TripItinerary,
  UpdatePlaceRequest,
  createPlace,
  deletePlace,
  getItinerary,
  movePlace,
  updatePlace,
} from '../api';

/**
 * One trip's itinerary, as signals. Like TripRepo this is where offline support
 * will land, so components never reach past it to HTTP.
 *
 * Every write re-reads the itinerary instead of patching the local copy. That is
 * deliberate: the server owns ranks, and it renumbers a whole day on any move or
 * delete, so a locally patched list would be a guess at what the server just
 * decided. The page is one request either way.
 */
@Injectable({ providedIn: 'root' })
export class PlaceRepo {
  private readonly api = inject(Api);

  private readonly _itinerary = signal<TripItinerary | null>(null);
  private readonly _loading = signal(false);
  /** Set while a write is in flight, so the page can disable its controls. */
  private readonly _saving = signal(false);

  readonly itinerary = this._itinerary.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly saving = this._saving.asReadonly();

  readonly trip = computed(() => this._itinerary()?.trip ?? null);
  readonly days = computed(() => this._itinerary()?.days ?? []);
  /** Viewers see the itinerary but get no controls. */
  readonly canEdit = computed(() => {
    const role = this.trip()?.myRole;
    return role === 'OWNER' || role === 'EDITOR';
  });

  async load(tripId: number): Promise<void> {
    // Clear first: leaving the previous trip on screen while another loads shows
    // one trip's places under another trip's name.
    if (this._itinerary()?.trip.id !== tripId) {
      this._itinerary.set(null);
    }
    this._loading.set(true);
    try {
      this._itinerary.set(await this.api.invoke(getItinerary, { tripId }));
    } finally {
      this._loading.set(false);
    }
  }

  async add(tripId: number, body: CreatePlaceRequest): Promise<void> {
    await this.write(tripId, () => this.api.invoke(createPlace, { tripId, body }));
  }

  async update(tripId: number, placeId: number, body: UpdatePlaceRequest): Promise<void> {
    await this.write(tripId, () => this.api.invoke(updatePlace, { tripId, placeId, body }));
  }

  async remove(tripId: number, placeId: number): Promise<void> {
    await this.write(tripId, () => this.api.invoke(deletePlace, { tripId, placeId }));
  }

  /**
   * A target day and rank covers both the up/down buttons and the drag-and-drop
   * that will replace them. A rank past the end of the day clamps server-side.
   */
  async move(tripId: number, placeId: number, dayDate: string, position: number): Promise<void> {
    await this.write(tripId, () =>
      this.api.invoke(movePlace, { tripId, placeId, body: { dayDate, position } }),
    );
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
