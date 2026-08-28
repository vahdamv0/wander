import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Api,
  CreatePlaceRequest,
  PlaceView,
  TripDay,
  TripItinerary,
  UpdatePlaceRequest,
  createPlace,
  deletePlace,
  getItinerary,
  movePlace,
  putDayNote,
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
   * A day's note, written whole. An empty string clears it — that is the
   * server's meaning of a blank note too, so there is no separate delete.
   */
  async saveDayNote(tripId: number, date: string, note: string): Promise<void> {
    await this.write(tripId, () =>
      this.api.invoke(putDayNote, { tripId, date, body: { note } }),
    );
  }

  /**
   * A target day and rank — the same call for the arrow buttons and for a drag.
   * A rank past the end of the day clamps server-side.
   *
   * This one is optimistic, unlike the other writes: a drag has already moved
   * the row under the user's finger, so waiting for the round trip would snap it
   * back and then move it again. The local list is reordered first, the server
   * is told, and the re-read afterwards is what makes the ranks canonical. A
   * failure puts the old order back.
   */
  async move(tripId: number, placeId: number, dayDate: string, position: number): Promise<void> {
    const snapshot = this._itinerary();
    const optimistic = snapshot && withMovedPlace(snapshot.days, placeId, dayDate, position);
    if (snapshot && optimistic) {
      this._itinerary.set({ ...snapshot, days: optimistic });
    }

    this._saving.set(true);
    try {
      await this.api.invoke(movePlace, { tripId, placeId, body: { dayDate, position } });
      await this.load(tripId);
    } catch (err) {
      if (snapshot) {
        this._itinerary.set(snapshot);
      }
      throw err;
    } finally {
      this._saving.set(false);
    }
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

/**
 * The move applied to a copy of the days, mirroring what the server does:
 * take the place out of its day, put it back at the target rank, and renumber
 * both days densely from zero. Returns null if the place is not there.
 */
function withMovedPlace(
  days: TripDay[],
  placeId: number,
  dayDate: string,
  position: number,
): TripDay[] | null {
  const moving = days.flatMap((day) => day.places).find((place) => place.id === placeId);
  if (!moving) {
    return null;
  }

  const withoutIt = days.map((day) => ({
    ...day,
    places: day.places.filter((place) => place.id !== placeId),
  }));

  return withoutIt.map((day) => {
    if (day.date !== dayDate) {
      return { ...day, places: renumber(day.places) };
    }
    const places = [...day.places];
    // Clamp like the server does, so the client never has to know the length.
    places.splice(Math.min(position, places.length), 0, { ...moving, dayDate });
    return { ...day, places: renumber(places) };
  });
}

function renumber(places: PlaceView[]): PlaceView[] {
  return places.map((place, index) => (place.position === index ? place : { ...place, position: index }));
}
