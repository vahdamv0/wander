import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Api,
  PlaceEnrichmentView,
  PlaceView,
  getPlaceEnrichment,
  setPlacePhoto,
} from '../api';
import { Connectivity } from '../core/connectivity';
import { OfflineError } from '../core/errors';
import { OfflineCache } from '../core/offline-cache';
import { SessionStore } from '../core/session.store';

/**
 * What the world says about a place, one place at a time.
 *
 * Keyed by place id rather than held as a single value, because the map opens one
 * popup at a time but a viewer works through several — and re-fetching a place
 * they looked at a minute ago would spend a rate budget to show them the same
 * paragraph.
 *
 * Read-through the offline cache like every other read, so a popup opened before
 * the signal went is still readable after it.
 */
@Injectable({ providedIn: 'root' })
export class EnrichmentRepo {
  private readonly api = inject(Api);
  private readonly cache = inject(OfflineCache);
  private readonly session = inject(SessionStore);
  private readonly connectivity = inject(Connectivity);

  private readonly _byPlace = signal<Record<number, PlaceEnrichmentView>>({});
  private readonly _loading = signal<number | null>(null);
  private readonly _saving = signal(false);

  readonly loading = this._loading.asReadonly();
  readonly saving = this._saving.asReadonly();

  /** What is known about a place, or undefined until it has been asked for. */
  readonly forPlace = computed(() => this._byPlace());

  get(placeId: number): PlaceEnrichmentView | undefined {
    return this._byPlace()[placeId];
  }

  async load(tripId: number, placeId: number): Promise<void> {
    if (this._byPlace()[placeId]) {
      return;
    }
    this._loading.set(placeId);
    try {
      const read = await this.cache.readThrough(
        this.session.user()?.id ?? null,
        `trip:${tripId}:place:${placeId}:enrichment`,
        () => this.api.invoke(getPlaceEnrichment, { tripId, placeId }),
      );
      this._byPlace.update((all) => ({ ...all, [placeId]: read.body }));
    } finally {
      this._loading.set(null);
    }
  }

  /** Keeps one of the offered candidates, or clears the photo when url is null. */
  async setPhoto(
    tripId: number,
    placeId: number,
    photo: { url: string; thumbUrl?: string; author?: string; licence?: string; sourceUrl?: string }
      | null,
  ): Promise<PlaceView> {
    if (!this.connectivity.online()) {
      throw new OfflineError();
    }
    this._saving.set(true);
    try {
      return await this.api.invoke(setPlacePhoto, {
        tripId,
        placeId,
        // A null url is how the server is told to forget the picture.
        body: photo ?? { url: undefined },
      });
    } finally {
      this._saving.set(false);
    }
  }
}
