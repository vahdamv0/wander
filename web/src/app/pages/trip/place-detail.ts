import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PlaceView } from '../../api';
import { messageOf } from '../../core/errors';
import { EnrichmentRepo } from '../../repo/enrichment.repo';
import { PlaceRepo } from '../../repo/place.repo';

/**
 * Everything about one place, and the only place it is edited.
 *
 * This replaces the enrichment-in-a-map-popup built earlier today. A popup was
 * the wrong home: it took four attempts to fit a paragraph and a photo strip into
 * one, and the machinery that took — a component rendered into Leaflet's DOM, a
 * `ResizeObserver`, a hand-written pan — all came out again when this arrived. A
 * panel has room and needs none of it.
 *
 * It is also the *only* editor for a place, deliberately. The row used to carry an
 * inline form; two editors for one thing means two places to keep in step and two
 * answers to "where do I change the name".
 *
 * Your words and the fetched summary are drawn as separate things. Not styling:
 * the fetched half is CC BY-SA and has to carry its source, and a single merged
 * block could not say which half the credit belongs to.
 */
@Component({
  selector: 'app-place-detail',
  imports: [FormsModule],
  templateUrl: './place-detail.html',
})
export class PlaceDetail {
  private readonly places = inject(PlaceRepo);
  private readonly enrichment = inject(EnrichmentRepo);

  readonly tripId = input.required<number>();
  readonly place = input.required<PlaceView>();
  readonly dayIndex = input.required<number>();
  readonly canEdit = input.required<boolean>();

  readonly closed = output<void>();
  /** Raised after any write, so the page re-reads and the map redraws. */
  readonly changed = output<void>();

  protected readonly saving = this.places.saving;
  protected readonly error = signal<string | null>(null);
  protected readonly editing = signal(false);

  protected readonly draftName = signal('');
  protected readonly draftTime = signal('');
  protected readonly draftNotes = signal<string[]>(['']);

  protected readonly facts = computed(() => this.enrichment.forPlace()[this.place().id]);

  /**
   * Directions on OpenStreetMap rather than a commercial map.
   *
   * The coordinates came from OSM, the tiles are OSM's by default, and sending
   * somebody to a service the rest of the application deliberately avoids would
   * be an odd place to stop being consistent.
   */
  protected readonly directionsUrl = computed(() => {
    const place = this.place();
    return place.latitude == null || place.longitude == null
      ? null
      : `https://www.openstreetmap.org/directions?to=${place.latitude}%2C${place.longitude}`;
  });

  constructor() {
    // Asked for when the panel opens, not when the itinerary loads: most places
    // in a trip are never opened, and four upstreams sit behind this.
    effect(() => {
      const place = this.place();
      if (place.enrichable) {
        untracked(() => void this.loadFacts(place.id));
      }
    });

    // A different place selected while the panel is open means the drafts belong
    // to the previous one.
    effect(() => {
      this.place();
      untracked(() => {
        this.editing.set(false);
        this.error.set(null);
      });
    });
  }

  private async loadFacts(placeId: number): Promise<void> {
    try {
      await this.enrichment.load(this.tripId(), placeId);
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'Could not look this place up.'));
    }
  }

  protected timeLabel(startsAt: string): string {
    return startsAt.slice(0, 5);
  }

  protected readOn(fetchedAt: string): string {
    return new Date(fetchedAt).toLocaleDateString(undefined, { month: 'short', year: 'numeric' });
  }

  protected openEdit(): void {
    const place = this.place();
    this.error.set(null);
    this.draftName.set(place.name);
    this.draftTime.set(place.startsAt ? place.startsAt.slice(0, 5) : '');
    this.draftNotes.set(place.notes.length ? [...place.notes] : ['']);
    this.editing.set(true);
  }

  protected cancelEdit(): void {
    this.editing.set(false);
    this.error.set(null);
  }

  protected setNoteAt(index: number, value: string): void {
    this.draftNotes.update((notes) => notes.map((note, i) => (i === index ? value : note)));
  }

  protected addNoteBox(): void {
    this.draftNotes.update((notes) => [...notes, '']);
  }

  protected removeNoteAt(index: number): void {
    // Never to nothing: an empty list leaves no box to type in, and a blank one is
    // dropped on save anyway.
    this.draftNotes.update((notes) =>
      notes.length > 1 ? notes.filter((_, i) => i !== index) : [''],
    );
  }

  protected async save(): Promise<void> {
    if (!this.draftName().trim()) {
      return;
    }
    await this.guard(async () => {
      await this.places.update(this.tripId(), this.place().id, {
        name: this.draftName().trim(),
        notes: this.draftNotes(),
        startsAt: this.draftTime() ? `${this.draftTime()}:00` : undefined,
      });
      this.editing.set(false);
      this.changed.emit();
    });
  }

  protected async remove(): Promise<void> {
    await this.guard(async () => {
      await this.places.remove(this.tripId(), this.place().id);
      this.changed.emit();
      // The thing this panel is about no longer exists.
      this.closed.emit();
    });
  }

  /** Keeps one of the offered photo candidates, or clears the kept one. */
  protected async keepPhoto(
    photo: { url: string; thumbUrl: string; author: string; licence: string; sourceUrl: string }
      | null,
  ): Promise<void> {
    await this.guard(async () => {
      await this.enrichment.setPhoto(this.tripId(), this.place().id, photo);
      this.changed.emit();
    });
  }

  private async guard(action: () => Promise<void>): Promise<void> {
    this.error.set(null);
    try {
      await action();
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'That did not work. Try again.'));
    }
  }
}
