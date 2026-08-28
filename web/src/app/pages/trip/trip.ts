import {
  CdkDrag,
  CdkDragDrop,
  CdkDragHandle,
  CdkDragPlaceholder,
  CdkDropList,
  CdkDropListGroup,
} from '@angular/cdk/drag-drop';
import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PlaceSuggestion, PlaceView, TripDay } from '../../api';
import { InstanceConfigStore } from '../../core/instance-config.store';
import { SessionStore } from '../../core/session.store';
import { TripChange, TripSyncService } from '../../core/trip-sync';
import { GeoRepo } from '../../repo/geo.repo';
import { MemberRepo } from '../../repo/member.repo';
import { TripRepo } from '../../repo/trip.repo';
import { PlaceRepo } from '../../repo/place.repo';
import { TripMap } from './trip-map';
import { TripMembers } from './trip-members';

/** Offered in the trip's edit form. The server accepts any three-letter code. */
const CURRENCIES = ['EUR', 'USD', 'GBP', 'CHF', 'SEK', 'NOK', 'DKK', 'PLN', 'CZK',
  'JPY', 'INR', 'AUD', 'CAD', 'NZD', 'SGD', 'ZAR', 'BRL', 'MXN'];

/**
 * How long to sit on a keystroke before searching. The geocoder allows one
 * request a second across the whole instance, so a typeahead that fired on every
 * character would spend that budget on prefixes nobody wanted.
 */
const SEARCH_DEBOUNCE_MS = 400;

/**
 * How hard to try to catch up after a live update. Nobody is watching a pushed
 * re-read fail, so it retries itself: 0.5s, 1s, 2s, then gives up and says so.
 */
const REFRESH_ATTEMPTS = 4;
const REFRESH_BACKOFF_MS = 500;

/**
 * One trip: its derived days, top to bottom, with the places on each.
 *
 * Ordering is done with buttons for now — a move is "put this place at rank N of
 * day D", which is the same call drag-and-drop will make later.
 */
@Component({
  selector: 'app-trip',
  imports: [
    CdkDrag,
    CdkDragHandle,
    CdkDragPlaceholder,
    CdkDropList,
    CdkDropListGroup,
    FormsModule,
    RouterLink,
    TripMap,
    TripMembers,
  ],
  templateUrl: './trip.html',
})
export class TripPage {
  private readonly repo = inject(PlaceRepo);
  private readonly geo = inject(GeoRepo);
  private readonly config = inject(InstanceConfigStore);
  private readonly router = inject(Router);
  private readonly members = inject(MemberRepo);
  private readonly session = inject(SessionStore);
  private readonly sync = inject(TripSyncService);
  private readonly trips = inject(TripRepo);
  private readonly destroyRef = inject(DestroyRef);

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
  /** Which day's note is being edited, by date. */
  protected readonly editingNote = signal<string | null>(null);
  /** The note being typed, separate from the place draft above it. */
  protected readonly draftNote = signal('');

  protected readonly draftName = signal('');
  protected readonly draftNotes = signal('');
  protected readonly error = signal<string | null>(null);

  /** Set when the draft came from a search hit, so its point is saved with it. */
  protected readonly draftLocation = signal<PlaceSuggestion | null>(null);

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  /** Skeleton rows: a field, not an inline literal — see CLAUDE.md on `@for`. */
  protected readonly skeletons = [0, 1, 2];

  /** Whether live updates are flowing, for the indicator in the header. */
  protected readonly syncStatus = this.sync.status;

  /** Only the owner may rewrite the trip itself; editors change its content. */
  protected readonly isOwner = computed(() => this.trip()?.myRole === 'OWNER');

  protected readonly editingTrip = signal(false);
  /**
   * Set when a save was refused and moving the itinerary along might rescue it.
   * The server cannot guess whether "longer and later" means the plan follows the
   * trip or stays put, so this is where the question gets asked.
   */
  protected readonly offerShift = signal(false);
  protected readonly tripName = signal('');
  protected readonly tripDestination = signal('');
  protected readonly tripStart = signal('');
  protected readonly tripEnd = signal('');
  protected readonly tripCurrency = signal('');

  /**
   * The currencies the select offers, plus whatever this trip already uses — an
   * instance may have been configured with one that is not on the list, and its
   * own currency vanishing from its own edit form would be absurd.
   */
  protected readonly currencyOptions = computed(() => {
    const own = this.trip()?.currency;
    return own && !CURRENCIES.includes(own) ? [own, ...CURRENCIES] : CURRENCIES;
  });

  constructor() {
    // input() is set before the first render, so reading it here is safe.
    queueMicrotask(() => void this.reload());
    queueMicrotask(() => this.sync.watch(this.id()));

    // Somebody else changed something. The service only reports; deciding what
    // to re-read is this page's job, because it is what knows which repos are on
    // screen.
    effect(() => {
      const change = this.sync.lastChange();
      if (change && change.tripId === this.id()) {
        untracked(() => void this.onRemoteChange(change));
      }
    });

    // A resumption is not an event: whatever happened while the socket was down
    // was never delivered, so re-read both sides unconditionally.
    effect(() => {
      if (this.sync.reconnected() > 0) {
        untracked(() => void this.resync());
      }
    });

    // A socket that outlives the page keeps a server session alive and goes on
    // delivering events to nobody.
    this.destroyRef.onDestroy(() => this.sync.stop());
  }

  /**
   * Re-reads what the change affects.
   *
   * The guard is the pair of "it was me" and "and I am still mid-write": our own
   * write re-reads when it completes, so acting on the echo as well would be a
   * second request for the same news. Checking `saving` rather than only the
   * actor is what keeps a *second tab of the same account* working — that tab is
   * not saving, so it reloads like anybody else.
   */
  private async onRemoteChange(change: TripChange): Promise<void> {
    const mine = change.actorUserId === this.session.user()?.id;
    if (mine && this.saving()) {
      return;
    }

    if (change.kind === 'TRIP_DELETED') {
      // No message: this page is about to unmount, and a trip's absence from the
      // list it lands on is its own explanation.
      await this.router.navigate(['/trips']);
      return;
    }

    if (change.kind === 'MEMBERS') {
      if (change.revokedUserId === this.session.user()?.id) {
        this.members.clear();
        await this.router.navigate(['/trips']);
        return;
      }
      // Our own role may have moved, and `canEdit` comes from the itinerary.
      this.wantMembers = true;
    }

    await this.refreshFromServer();
  }

  private async resync(): Promise<void> {
    this.wantMembers = true;
    await this.refreshFromServer();
  }

  /** Set when a live update means the member list is stale too. */
  private wantMembers = false;
  private refreshing = false;
  private refreshAgain = false;

  /**
   * Re-reads what a live update invalidated, and **keeps trying**.
   *
   * The retry is the whole point. A push-driven re-read has no user behind it to
   * notice it failed and press the button again: one dropped request during a
   * lift-flicker leaves the page quietly stale, showing an itinerary that is
   * wrong with no indication that it is — and the next event might be hours
   * away. `reload()` on its own swallows the failure into an error message,
   * which is right for a page load and useless here.
   *
   * Single-flight, because several changes arriving together (a drag renumbers a
   * day, so does a delete) should cost one re-read, not one each. Anything that
   * lands mid-flight sets the flag and gets folded into one more pass.
   */
  private async refreshFromServer(): Promise<void> {
    if (this.refreshing) {
      this.refreshAgain = true;
      return;
    }
    this.refreshing = true;
    try {
      do {
        this.refreshAgain = false;
        for (let attempt = 0; ; attempt++) {
          try {
            if (this.wantMembers) {
              await this.members.refreshIfLoaded(this.id());
              this.wantMembers = false;
            }
            await this.repo.load(this.id());
            this.error.set(null);
            break;
          } catch {
            if (attempt >= REFRESH_ATTEMPTS - 1) {
              this.error.set('This trip may be out of date — could not reach the server.');
              break;
            }
            await new Promise((resolve) => setTimeout(resolve, REFRESH_BACKOFF_MS * 2 ** attempt));
          }
        }
      } while (this.refreshAgain);
    } finally {
      this.refreshing = false;
    }
  }

  private id(): number {
    return Number(this.tripId());
  }

  /**
   * Somebody's role changed, possibly our own: a transfer of ownership demotes
   * the caller, and `canEdit` comes from the itinerary's `myRole`, so the rest
   * of the page is stale until it is re-read.
   */
  /** Opens the trip's own edit form, seeded with what it currently says. */
  protected openTripEdit(): void {
    const trip = this.trip();
    if (!trip) {
      return;
    }
    this.error.set(null);
    this.tripName.set(trip.name);
    this.tripDestination.set(trip.destination ?? '');
    this.tripStart.set(trip.startDate);
    this.tripEnd.set(trip.endDate);
    this.tripCurrency.set(trip.currency);
    this.offerShift.set(false);
    this.editingTrip.set(true);
  }

  protected cancelTripEdit(): void {
    this.editingTrip.set(false);
    this.offerShift.set(false);
    this.error.set(null);
  }

  /**
   * Saves the trip, optionally bringing the itinerary with it.
   *
   * Not routed through `guard` because a refusal here is not simply an error to
   * display: when the start date moved, the same save may well succeed with the
   * itinerary shifted along, and that offer is more useful than the message.
   */
  protected async saveTrip(shiftItinerary = false): Promise<void> {
    this.error.set(null);
    const startMoved = this.tripStart() !== this.trip()?.startDate;
    try {
      await this.trips.update(this.id(), {
        name: this.tripName().trim(),
        destination: this.tripDestination().trim() || undefined,
        startDate: this.tripStart(),
        endDate: this.tripEnd(),
        currency: this.tripCurrency() || undefined,
        shiftItinerary: shiftItinerary || undefined,
      });
      this.editingTrip.set(false);
      this.offerShift.set(false);
      // The days, and every place's date, may have moved underneath us.
      await this.reload();
    } catch (err: unknown) {
      const body = (err as { error?: { message?: string } } | null)?.error;
      this.error.set(body?.message ?? 'Could not save the trip.');
      // Only worth offering when the start moved: with the same start there is
      // no offset to shift by, and the retry would fail identically.
      this.offerShift.set(!shiftItinerary && startMoved);
    }
  }

  protected async onRolesChanged(): Promise<void> {
    await this.reload();
  }

  /** We left the trip; it is a 404 for us now, so there is nothing to show. */
  protected async onLeft(): Promise<void> {
    await this.router.navigate(['/trips']);
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
    this.editingNote.set(null);
    this.resetDraft();
    this.addingTo.set(this.addingTo() === date ? null : date);
  }

  protected openEdit(place: PlaceView): void {
    this.addingTo.set(null);
    this.editingNote.set(null);
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

  /** Opens a day's note for editing, seeded with whatever it already says. */
  protected openNote(day: TripDay): void {
    this.addingTo.set(null);
    this.editing.set(null);
    this.resetDraft();
    this.draftNote.set(day.note ?? '');
    this.editingNote.set(this.editingNote() === day.date ? null : day.date);
  }

  protected cancelNote(): void {
    this.editingNote.set(null);
    this.draftNote.set('');
  }

  /**
   * Saves the note as typed. An empty box clears the day — the same meaning the
   * server gives a blank note, so "Clear" is just an empty save.
   */
  protected async saveNote(day: TripDay): Promise<void> {
    await this.guard(async () => {
      await this.repo.saveDayNote(this.id(), day.date, this.draftNote());
      this.cancelNote();
    });
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

  /**
   * A dropped row. CDK reports the index within the target list after the move,
   * which is the same thing the API's `position` means, so no translation is
   * needed — and the same call serves the arrow buttons.
   */
  protected async onDrop(event: CdkDragDrop<TripDay>): Promise<void> {
    const place: PlaceView = event.item.data;
    const target = event.container.data;
    if (event.previousContainer === event.container && event.previousIndex === event.currentIndex) {
      return;
    }
    await this.guard(() => this.repo.move(this.id(), place.id, target.date, event.currentIndex));
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
