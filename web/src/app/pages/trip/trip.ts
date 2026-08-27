import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PlaceSuggestion, PlaceView, TripDay } from '../../api';
import { InstanceConfigStore } from '../../core/instance-config.store';
import { GeoRepo } from '../../repo/geo.repo';
import { PlaceRepo } from '../../repo/place.repo';
import { TripMap } from './trip-map';

/**
 * How long to sit on a keystroke before searching. The geocoder allows one
 * request a second across the whole instance, so a typeahead that fired on every
 * character would spend that budget on prefixes nobody wanted.
 */
const SEARCH_DEBOUNCE_MS = 400;

/**
 * One trip: its derived days, top to bottom, with the places on each.
 *
 * Ordering is done with buttons for now — a move is "put this place at rank N of
 * day D", which is the same call drag-and-drop will make later.
 */
@Component({
  selector: 'app-trip',
  imports: [FormsModule, RouterLink, TripMap],
  templateUrl: './trip.html',
})
export class TripPage {
  private readonly repo = inject(PlaceRepo);
  private readonly geo = inject(GeoRepo);
  private readonly config = inject(InstanceConfigStore);

  /** Bound from the route, as a string — coerced once here. */
  readonly tripId = input.required<string>();

  protected readonly trip = this.repo.trip;
  protected readonly days = this.repo.days;
  protected readonly loading = this.repo.loading;
  protected readonly saving = this.repo.saving;
  protected readonly canEdit = this.repo.canEdit;

  /** Null until the instance has said where tiles come from; the map waits. */
  protected readonly mapConfig = computed(() =>
    this.config.mapEnabled() ? this.config.map() : null,
  );
  /** The place the map should pan to. Set by clicking a row's pin button. */
  protected readonly focused = signal<PlaceView | null>(null);
  /** Highlighted row, set by clicking a marker. */
  protected readonly highlighted = signal<number | null>(null);

  protected readonly mappedCount = computed(
    () =>
      this.days().reduce(
        (total, day) => total + day.places.filter((place) => place.latitude != null).length,
        0,
      ),
  );

  protected readonly suggestions = this.geo.results;
  protected readonly searching = this.geo.searching;
  protected readonly searchError = this.geo.error;
  protected readonly searchAvailable = this.geo.available;

  protected readonly placeCount = computed(() =>
    this.days().reduce((total, day) => total + day.places.length, 0),
  );

  /** Which day's add-form is open, by date. Only one at a time. */
  protected readonly addingTo = signal<string | null>(null);
  /** Which place is being edited, by id. */
  protected readonly editing = signal<number | null>(null);

  protected readonly draftName = signal('');
  protected readonly draftNotes = signal('');
  protected readonly error = signal<string | null>(null);

  /** Set when the draft came from a search hit, so its point is saved with it. */
  protected readonly draftLocation = signal<PlaceSuggestion | null>(null);

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  /** Skeleton rows: a field, not an inline literal — see CLAUDE.md on `@for`. */
  protected readonly skeletons = [0, 1, 2];

  constructor() {
    // input() is set before the first render, so reading it here is safe.
    queueMicrotask(() => void this.reload());
  }

  private id(): number {
    return Number(this.tripId());
  }

  private async reload(): Promise<void> {
    try {
      await this.repo.load(this.id());
    } catch {
      this.error.set('Could not load this trip.');
    }
  }

  protected openAdd(date: string): void {
    this.editing.set(null);
    this.resetDraft();
    this.addingTo.set(this.addingTo() === date ? null : date);
  }

  protected openEdit(place: PlaceView): void {
    this.addingTo.set(null);
    this.resetDraft();
    this.draftName.set(place.name);
    this.draftNotes.set(place.notes ?? '');
    this.editing.set(this.editing() === place.id ? null : place.id);
  }

  protected cancel(): void {
    this.addingTo.set(null);
    this.editing.set(null);
    this.resetDraft();
  }

  private resetDraft(): void {
    this.draftName.set('');
    this.draftNotes.set('');
    this.draftLocation.set(null);
    this.error.set(null);
    this.clearSearch();
  }

  private clearSearch(): void {
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
      this.searchTimer = null;
    }
    this.geo.clear();
  }

  /**
   * Typing invalidates any picked location: the name no longer describes the
   * point that would be saved with it.
   */
  protected onNameTyped(value: string): void {
    this.draftName.set(value);
    this.draftLocation.set(null);
    if (!this.searchAvailable()) {
      return;
    }
    if (this.searchTimer !== null) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => {
      this.searchTimer = null;
      void this.geo.search(value);
    }, SEARCH_DEBOUNCE_MS);
  }

  /** Takes the hit's own name, and remembers the point to save with it. */
  protected pick(suggestion: PlaceSuggestion): void {
    this.draftName.set(suggestion.name);
    this.draftLocation.set(suggestion);
    this.clearSearch();
  }

  /** The address of a hit, minus the leading name it repeats. */
  protected addressDetail(suggestion: PlaceSuggestion): string {
    return this.withoutLeadingName(suggestion.name, suggestion.address);
  }

  /**
   * A geocoder's address line usually starts with the name of the thing, which
   * is redundant directly under it — "Carrer de Mallorca, Barcelona" reads
   * better than the name twice.
   */
  protected withoutLeadingName(name: string, address: string): string {
    return address.startsWith(name + ',') ? address.slice(name.length + 1).trim() : address;
  }

  protected async addPlace(date: string): Promise<void> {
    await this.guard(async () => {
      const location = this.draftLocation();
      await this.repo.add(this.id(), {
        dayDate: date,
        name: this.draftName(),
        notes: this.draftNotes() || undefined,
        // Sent as picked rather than re-searched: the user chose one candidate
        // out of several, and a second search could rank a different one first.
        latitude: location?.latitude,
        longitude: location?.longitude,
        address: location?.address,
      });
      // The form stays open — adding several places to one day is the common
      // case — but the draft and its search results go.
      this.draftName.set('');
      this.draftNotes.set('');
      this.draftLocation.set(null);
      this.clearSearch();
    });
  }

  protected async savePlace(place: PlaceView): Promise<void> {
    await this.guard(async () => {
      await this.repo.update(this.id(), place.id, {
        name: this.draftName(),
        notes: this.draftNotes() || undefined,
      });
      this.editing.set(null);
    });
  }

  protected async removePlace(place: PlaceView): Promise<void> {
    await this.guard(() => this.repo.remove(this.id(), place.id));
  }

  /** Up and down inside one day. */
  protected async nudge(place: PlaceView, by: -1 | 1): Promise<void> {
    await this.guard(() => this.repo.move(this.id(), place.id, place.dayDate, place.position + by));
  }

  /** Move to the start of an adjacent day. */
  protected async shiftDay(place: PlaceView, day: TripDay, by: -1 | 1): Promise<void> {
    const target = this.days()[this.days().indexOf(day) + by];
    if (!target) {
      return;
    }
    await this.guard(() => this.repo.move(this.id(), place.id, target.date, target.places.length));
  }

  protected isFirstDay(day: TripDay): boolean {
    return day.index === 1;
  }

  protected isLastDay(day: TripDay): boolean {
    return day.index === this.days().length;
  }

  /** Clicking a row's pin moves the map; clicking the same one again re-centres it. */
  protected showOnMap(place: PlaceView): void {
    this.highlighted.set(place.id);
    // A fresh object each time, so panning to the same place twice still fires.
    this.focused.set({ ...place });
  }

  /** A marker was clicked: highlight its row without moving the map again. */
  protected onMarkerPicked(place: PlaceView): void {
    this.highlighted.set(place.id);
  }

  /** Weekday and day-of-month, e.g. "Mon 3 Nov". */
  protected dayLabel(date: string): string {
    return new Date(date + 'T00:00:00').toLocaleDateString(undefined, {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
    });
  }

  private async guard(action: () => Promise<void>): Promise<void> {
    this.error.set(null);
    try {
      await action();
    } catch (err: unknown) {
      const body = (err as { error?: { message?: string } } | null)?.error;
      this.error.set(body?.message ?? 'That did not work. Try again.');
    }
  }
}
