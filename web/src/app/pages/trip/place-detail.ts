import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PlaceView } from '../../api';
import { messageOf } from '../../core/errors';
import { PhotoLoad } from '../../core/photo-load';
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
  imports: [FormsModule, PhotoLoad],
  templateUrl: './place-detail.html',
  // Escape closes the directions menu wherever the focus is. The panel itself
  // has no Escape handler, so this cannot swallow one.
  host: { '(document:keydown.escape)': 'closeDirections()' },
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
   * The candidates that are not already the picture at the top of the panel.
   *
   * Most places come back with exactly one, so an unfiltered chooser drew the
   * same photograph twice — once as the place's picture and again as the only
   * thing on offer, under a heading and a licence sentence. A chooser with
   * nothing to choose is noise, so the current photo lives in one place: the
   * hero, whose caption carries the credit and the way to put it back.
   */
  protected readonly otherPhotos = computed(
    () => this.facts()?.photos.filter((photo) => photo.url !== this.place().photoUrl) ?? [],
  );

  /** Whether the directions menu is showing. */
  protected readonly directionsOpen = signal(false);

  /**
   * Whether Remove has been pressed once and is waiting to be meant.
   *
   * Deleting a place is the only irreversible thing on this panel — it takes the
   * notes written on it, there is no undo, and live sync will carry it to
   * everybody else's screen within the second. So it asks. Inline rather than a
   * dialog: the panel is already a surface, and a confirmation that appears where
   * the button was cannot be dismissed by clicking the wrong bit of a backdrop.
   */
  protected readonly confirmingRemoval = signal(false);

  /**
   * Where to send somebody for directions, and a choice of two.
   *
   * This used to be one link to OpenStreetMap, on the argument that the
   * coordinates and the tiles are OSM's and a commercial map would be an odd
   * place to stop being consistent. That holds for what wander *draws* and no
   * longer decides this: getting somewhere is the one moment a person wants the
   * routing they actually use, and on a phone that is usually Google Maps. So
   * both are offered and neither is chosen for them — Google first because it is
   * the one most people are going to want, OSM second because it is the source
   * of everything else here.
   *
   * Only ever a link out. Nothing is sent to either service beyond the
   * coordinates already in the URL the user chose to open.
   */
  protected readonly directionsLinks = computed(() => {
    const place = this.place();
    if (place.latitude == null || place.longitude == null) {
      return null;
    }
    const point = `${place.latitude},${place.longitude}`;
    return [
      {
        label: 'Google Maps',
        // The documented, parameter-stable form; the /maps/@... shapes are not.
        url: `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(point)}`,
      },
      {
        label: 'OpenStreetMap',
        url: `https://www.openstreetmap.org/directions?to=${encodeURIComponent(point)}`,
      },
    ];
  });

  protected toggleDirections(): void {
    this.directionsOpen.update((open) => !open);
  }

  protected closeDirections(): void {
    this.directionsOpen.set(false);
  }

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
        this.directionsOpen.set(false);
        // A pending "are you sure" belonged to the place that is no longer shown.
        this.confirmingRemoval.set(false);
        this.confirmingPhotoRemoval.set(false);
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

  /**
   * Pinning this place so an auto-sort leaves it where it is — the hotel to
   * start from, the table booked for eight.
   *
   * It lives here rather than on the row for the reason the row's own comment
   * gives: `.row-actions` is already six small icons and stays on permanently
   * where there is no hover. The row shows a lock *indicator*; this is where
   * there is room to say what it means.
   *
   * Offered whether or not the instance has a routing engine. A lock is a fact
   * about the place, an operator may switch routing on next week, and a control
   * that only sometimes exists is worse than one that always does.
   */
  protected async toggleLock(): Promise<void> {
    await this.guard(async () => {
      await this.places.setLocked(this.tripId(), this.place().id, !this.place().locked);
      this.changed.emit();
    });
  }

  protected askRemove(): void {
    this.error.set(null);
    this.confirmingRemoval.set(true);
  }

  protected cancelRemove(): void {
    this.confirmingRemoval.set(false);
  }

  protected async remove(): Promise<void> {
    await this.guard(async () => {
      await this.places.remove(this.tripId(), this.place().id);
      this.changed.emit();
      // The thing this panel is about no longer exists.
      this.closed.emit();
    });
  }

  /**
   * Whether the kept photo's "remove" has been pressed once.
   *
   * Recoverable in principle — the candidates are still an enrichment away —
   * but only for a place that has an `osm_ref` to ask about, and the control is
   * a four-letter word in a run of credit text, which is a thing you can hit
   * while reaching for the Commons link beside it.
   */
  protected readonly confirmingPhotoRemoval = signal(false);

  protected askRemovePhoto(): void {
    this.confirmingPhotoRemoval.set(true);
  }

  protected cancelRemovePhoto(): void {
    this.confirmingPhotoRemoval.set(false);
  }

  /** Keeps one of the offered photo candidates, or clears the kept one. */
  protected async keepPhoto(
    photo: { url: string; thumbUrl: string; author: string; licence: string; sourceUrl: string }
      | null,
  ): Promise<void> {
    this.confirmingPhotoRemoval.set(false);
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
