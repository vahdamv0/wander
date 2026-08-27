import { Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PlaceView, TripDay } from '../../api';
import { PlaceRepo } from '../../repo/place.repo';

/**
 * One trip: its derived days, top to bottom, with the places on each.
 *
 * Ordering is done with buttons for now — a move is "put this place at rank N of
 * day D", which is the same call drag-and-drop will make later.
 */
@Component({
  selector: 'app-trip',
  imports: [FormsModule, RouterLink],
  templateUrl: './trip.html',
})
export class TripPage {
  private readonly repo = inject(PlaceRepo);

  /** Bound from the route, as a string — coerced once here. */
  readonly tripId = input.required<string>();

  protected readonly trip = this.repo.trip;
  protected readonly days = this.repo.days;
  protected readonly loading = this.repo.loading;
  protected readonly saving = this.repo.saving;
  protected readonly canEdit = this.repo.canEdit;

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
    this.draftName.set('');
    this.draftNotes.set('');
    this.error.set(null);
    this.addingTo.set(this.addingTo() === date ? null : date);
  }

  protected openEdit(place: PlaceView): void {
    this.addingTo.set(null);
    this.draftName.set(place.name);
    this.draftNotes.set(place.notes ?? '');
    this.error.set(null);
    this.editing.set(this.editing() === place.id ? null : place.id);
  }

  protected cancel(): void {
    this.addingTo.set(null);
    this.editing.set(null);
    this.error.set(null);
  }

  protected async addPlace(date: string): Promise<void> {
    await this.guard(async () => {
      await this.repo.add(this.id(), {
        dayDate: date,
        name: this.draftName(),
        notes: this.draftNotes() || undefined,
      });
      // Left open: adding several places to one day is the common case.
      this.draftName.set('');
      this.draftNotes.set('');
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
