import {
  CdkDrag,
  CdkDragDrop,
  CdkDragHandle,
  CdkDragPlaceholder,
  CdkDropList,
  CdkDropListGroup,
} from '@angular/cdk/drag-drop';
import {
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PlaceSuggestion, PlaceView, TripDay } from '../../api';
import { InstanceConfigStore } from '../../core/instance-config.store';
import { ageLabel, messageOf } from '../../core/errors';
import { formatMoney } from '../../core/money';
import { SessionStore } from '../../core/session.store';
import { TripChange, TripSyncService } from '../../core/trip-sync';
import { GeoRepo } from '../../repo/geo.repo';
import { InviteRepo } from '../../repo/invite.repo';
import { MemberRepo } from '../../repo/member.repo';
import { RouteProfile, RouteRepo } from '../../repo/route.repo';
import { TripRepo } from '../../repo/trip.repo';
import { WeatherRepo } from '../../repo/weather.repo';
import { PlaceRepo } from '../../repo/place.repo';
import { PlaceDetail } from './place-detail';
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
 * Origin, destination and nine waypoints — the most a `maps/dir/?api=1` URL
 * carries. Past that Google keeps the ones it can and drops the rest silently,
 * which is exactly the sort of quiet loss this application refuses elsewhere,
 * so the link says what it is doing instead.
 */
const MAPS_MAX_STOPS = 11;

/** Google's own words for the three profiles. "bicycling", not "cycling". */
const GOOGLE_TRAVEL_MODES: Record<RouteProfile, string> = {
  WALKING: 'walking',
  DRIVING: 'driving',
  CYCLING: 'bicycling',
};

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
    PlaceDetail,
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
  private readonly invites = inject(InviteRepo);
  private readonly session = inject(SessionStore);
  private readonly sync = inject(TripSyncService);
  private readonly trips = inject(TripRepo);
  private readonly weather = inject(WeatherRepo);
  private readonly routes = inject(RouteRepo);
  private readonly destroyRef = inject(DestroyRef);

  /** Bound from the route, as a string — coerced once here. */
  readonly tripId = input.required<string>();

  /**
   * The same id as a number, for children that want one.
   *
   * Not `trip()!.id`: the map renders before the itinerary has loaded, so that
   * assertion is false on the first pass and throws. The route parameter is
   * always there, which makes this the honest source.
   */
  protected readonly numericTripId = computed(() => Number(this.tripId()));

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

  /** The map's own box, so a pin on a day far down the page can scroll it into view. */
  private readonly mapSection = viewChild<ElementRef<HTMLElement>>('mapSection');
  /** Highlighted row, set by clicking a marker. */
  protected readonly highlighted = signal<number | null>(null);

  protected readonly mappedCount = computed(
    () =>
      this.days().reduce(
        (total, day) => total + day.places.filter((place) => place.latitude != null).length,
        0,
      ),
  );

  /**
   * What the forecast depends on: each day's first located place, rounded.
   *
   * A computed string, because it is what decides when to ask again. Re-reading
   * the weather on every itinerary change would double the requests this page
   * makes under live sync for a number that only moves when a day's *location*
   * does — renaming a place or reordering two restaurants cannot change the
   * weather. Rounded to about a kilometre, matching the server's own tolerance,
   * so nudging a pin is not a refetch either.
   */
  private readonly weatherAnchors = computed(() =>
    this.days()
      .map((day) => {
        const located = day.places.find((place) => place.latitude != null);
        return located
          ? `${day.date}:${located.latitude!.toFixed(2)},${located.longitude!.toFixed(2)}`
          : day.date;
      })
      .join('|'),
  );

  protected readonly dayWeather = this.weather.byDate;
  protected readonly weatherCredit = this.weather.attribution;

  protected readonly suggestions = this.geo.results;
  protected readonly searching = this.geo.searching;
  protected readonly searchError = this.geo.error;
  protected readonly searchAvailable = this.geo.available;

  protected readonly placeCount = computed(() =>
    this.days().reduce((total, day) => total + day.places.length, 0),
  );

  /**
   * The place whose panel is open, by id.
   *
   * Held here rather than in the panel because both the row and the map pin open
   * it, and the itinerary is what knows about both.
   */
  protected readonly selectedPlaceId = signal<number | null>(null);

  protected readonly selectedPlace = computed(() => {
    const id = this.selectedPlaceId();
    return id === null ? null : this.days().flatMap((day) => day.places).find((p) => p.id === id) ?? null;
  });

  /** The day number the selected place sits on, for the panel's heading. */
  protected readonly selectedDayIndex = computed(() => {
    const id = this.selectedPlaceId();
    const day = this.days().find((candidate) => candidate.places.some((p) => p.id === id));
    return day?.index ?? 0;
  });

  /** Which day's add-form is open, by date. Only one at a time. */
  protected readonly addingTo = signal<string | null>(null);
  /** Which day's note is being edited, by date. */
  protected readonly editingNote = signal<string | null>(null);
  /** The note being typed, separate from the place draft above it. */
  protected readonly draftNote = signal('');

  protected readonly draftName = signal('');
  /** The add form's single box. A place usually starts with one thought. */
  protected readonly draftNotes = signal('');

  protected readonly error = signal<string | null>(null);

  /** Set when the draft came from a search hit, so its point is saved with it. */
  protected readonly draftLocation = signal<PlaceSuggestion | null>(null);

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  /** Skeleton rows: a field, not an inline literal — see CLAUDE.md on `@for`. */
  protected readonly skeletons = [0, 1, 2];

  /** Whether live updates are flowing, for the indicator in the header. */
  protected readonly syncStatus = this.sync.status;
  /** Set when this page is showing a copy from the device rather than the server. */
  protected readonly savedAt = this.repo.savedAt;

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

    // The forecast, once the instance has said it has one and the itinerary has
    // said where. `weatherAnchors` is a computed string, so this fires on the
    // first load and then only when a day's location actually moves.
    effect(() => {
      const enabled = this.config.weatherEnabled();
      const anchors = this.weatherAnchors();
      if (enabled && anchors) {
        untracked(() => void this.weather.refresh(this.id()));
      }
    });

    // A socket that outlives the page keeps a server session alive and goes on
    // delivering events to nobody.
    this.destroyRef.onDestroy(() => this.sync.stop());
    // Otherwise the next trip opens showing this one's temperatures for a moment.
    this.destroyRef.onDestroy(() => this.weather.clear());
    // A proposal is about one day of one trip, and it is stale the moment the
    // day changes. Leaving the page is the clearest such moment.
    this.destroyRef.onDestroy(() => this.routes.clear());
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

    if (change.kind === 'ITINERARY') {
      // Somebody else moved, added or removed a place, so a proposal held on
      // screen is about a day that no longer exists in that shape — and the
      // server would refuse it anyway, its id set no longer matching the day.
      this.routes.clear();
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
              // An accepted invitation is a MEMBERS change too, and it turns a
              // link in the owner's list from "waiting" into "used". Only re-read
              // if the owner actually has that list open — refreshIfLoaded is
              // what makes this free for everybody else.
              await this.invites.refreshIfLoaded(this.id());
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
      this.error.set(messageOf(err, 'Could not save the trip.'));
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
    this.editingNote.set(null);
    this.resetDraft();
    this.addingTo.set(this.addingTo() === date ? null : date);
  }





  /** "08:00" from the stored "08:00:00". */
  protected timeLabel(startsAt: string): string {
    return startsAt.slice(0, 5);
  }

  protected cancel(): void {
    this.addingTo.set(null);
    this.resetDraft();
  }

  /** Opens a day's note for editing, seeded with whatever it already says. */
  protected openNote(day: TripDay): void {
    this.addingTo.set(null);
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
        // One box on the add form, but the API takes a list everywhere — one shape
        // for notes is worth more than a convenience.
        notes: this.draftNotes().trim() ? [this.draftNotes().trim()] : [],
        // Sent as picked rather than re-searched: the user chose one candidate
        // out of several, and a second search could rank a different one first.
        latitude: location?.latitude,
        longitude: location?.longitude,
        address: location?.address,
        // The geocoder's own reference for the hit they picked. Kept because it
        // is the only thing that can identify this place upstream afterwards, and
        // it cannot be recovered once the moment of choosing has passed.
        osmRef: location?.ref,
        // Same reasoning: it arrived with the hit they chose and cannot be
        // recovered once the choosing is over.
        category: location?.category,
      });
      // The form stays open — adding several places to one day is the common
      // case — but the draft and its search results go.
      this.draftName.set('');
      this.draftNotes.set('');
      this.draftLocation.set(null);
      this.clearSearch();
    });
  }


  /**
   * Which place's × has been pressed once and is waiting to be meant.
   *
   * The × is the sixth small icon in a row, immediately after "move to next day",
   * and `.row-actions` stay on permanently where there is no hover — so on a
   * phone it is a live target next to an arrow. Removing takes the place's notes
   * with it and there is no undo, so it asks. One id rather than a set: asking
   * about two places at once is not a state worth having.
   */
  protected readonly confirmingRemoval = signal<number | null>(null);

  protected askRemovePlace(place: PlaceView): void {
    this.confirmingRemoval.set(place.id);
  }

  protected cancelRemovePlace(): void {
    this.confirmingRemoval.set(null);
  }

  protected async removePlace(place: PlaceView): Promise<void> {
    this.confirmingRemoval.set(null);
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
    this.bringTheMapIntoView();
  }

  /**
   * Panning a map nobody can see is not "showing" anything.
   *
   * On a narrow screen the map is *above* the days, so a pin clicked on day four
   * moved it well off the top of the window and the button appeared to do
   * nothing at all. `block: 'nearest'` is what makes this safe to call every
   * time: it scrolls only when the map is not already fully in view, so on a
   * wide screen — where the map is stuck to the top of its column — it does
   * nothing, and the page does not lurch under somebody who can see it fine.
   */
  private bringTheMapIntoView(): void {
    const section = this.mapSection()?.nativeElement;
    if (!section) {
      return;
    }
    // Somebody who has asked for less motion gets the jump rather than the
    // journey; the browser's own media query is the only thing that knows.
    const gently = !matchMedia('(prefers-reduced-motion: reduce)').matches;
    section.scrollIntoView({ block: 'nearest', behavior: gently ? 'smooth' : 'auto' });
  }

  /** A marker was clicked: highlight its row without moving the map again. */
  protected onMarkerPicked(place: PlaceView): void {
    this.highlighted.set(place.id);
    // A pin and a row are the two ways in, and both land on the same panel.
    this.selectedPlaceId.set(place.id);
  }

  protected openPlace(place: PlaceView): void {
    this.selectedPlaceId.set(place.id);
  }

  protected closePlace(): void {
    this.selectedPlaceId.set(null);
  }

  /** The panel wrote something; the itinerary and the map both need the new truth. */
  protected async onPlaceChanged(): Promise<void> {
    await this.reload();
  }

  /** Weekday and day-of-month, e.g. "Mon 3 Nov". */
  protected dayLabel(date: string): string {
    return new Date(date + 'T00:00:00').toLocaleDateString(undefined, {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
    });
  }

  /**
   * What the day cost, in the trip's currency, or null when nothing was spent.
   *
   * Null and zero are kept distinct all the way from the server: a day card that
   * printed "0.00" on every untouched day would be saying something about the
   * trip that nobody entered.
   */
  protected dayCost(day: TripDay): string | null {
    const trip = this.trip();
    return day.spentMinor == null || !trip ? null : formatMoney(day.spentMinor, trip.currency);
  }

  /** "19° / 7°" — rounded here, because rounding is a display choice. */
  protected temperatureRange(date: string): string | null {
    const day = this.dayWeather().get(date);
    return day ? `${Math.round(day.tempMaxC)}° / ${Math.round(day.tempMinC)}°` : null;
  }

  protected conditions(date: string): string {
    return this.dayWeather().get(date)?.summary ?? '';
  }

  /**
   * A coarse band of the WMO code, for choosing an icon.
   *
   * The words come from the server — one copy of the WMO table, and it is content.
   * The glyph is presentation, so it is chosen here, from six buckets rather than
   * thirty: an icon that distinguished light drizzle from moderate drizzle would
   * be two icons nobody could tell apart.
   */
  protected weatherIcon(date: string): 'clear' | 'cloud' | 'fog' | 'rain' | 'snow' | 'storm' | null {
    const code = this.dayWeather().get(date)?.weatherCode;
    if (code == null) {
      return null;
    }
    if (code === 0 || code === 1) return 'clear';
    if (code === 2 || code === 3) return 'cloud';
    if (code === 45 || code === 48) return 'fog';
    if (code >= 71 && code <= 77) return 'snow';
    if (code === 85 || code === 86) return 'snow';
    if (code >= 95) return 'storm';
    if (code >= 51) return 'rain';
    return 'cloud';
  }

  // -- routes -------------------------------------------------------------

  /**
   * Sorting a day by how long it takes to get between its stops.
   *
   * Only offered when the operator has stood a routing engine up: false until
   * `/api/config` says otherwise, which inverts this store's usual optimism
   * for booking import's reason — a button that appears and then answers 503
   * is worse than one that appears a beat late.
   */
  protected readonly routingEnabled = this.config.routingEnabled;
  protected readonly routePreview = this.routes.preview;
  protected readonly previewing = this.routes.previewing;

  /**
   * Walking, driving or cycling. One choice for the page rather than one per
   * day: it is a fact about how this trip is being got around, and setting it
   * eight times on an eight-day trip is eight chances to leave one wrong.
   */
  protected readonly routeProfile = signal<RouteProfile>('WALKING');
  protected readonly routeProfiles: RouteProfile[] = ['WALKING', 'DRIVING', 'CYCLING'];

  /** "On foot", not "WALKING": the enum is the wire, this is the label. */
  protected profileLabel(profile: RouteProfile): string {
    return profile === 'WALKING' ? 'On foot' : profile === 'DRIVING' ? 'Driving' : 'Cycling';
  }

  /** A day is worth sorting when at least two of its stops have coordinates. */
  protected canSort(day: TripDay): boolean {
    return this.canEdit() && this.routingEnabled() && this.locatedIn(day).length >= 2;
  }

  /** The proposal, if the one being held is for this day. */
  protected proposalFor(day: TripDay) {
    const preview = this.routePreview();
    return preview && preview.dayDate === day.date ? preview : null;
  }

  protected async proposeRoute(day: TripDay): Promise<void> {
    await this.guard(async () => {
      this.routes.clear();
      await this.routes.propose(this.numericTripId(), day.date, this.routeProfile());
    });
  }

  protected async applyProposal(day: TripDay): Promise<void> {
    const preview = this.proposalFor(day);
    if (!preview) {
      return;
    }
    await this.guard(async () => {
      await this.repo.reorderDay(this.numericTripId(), day.date,
        preview.stops.map((stop) => stop.placeId));
      this.routes.clear();
    });
  }

  protected discardProposal(): void {
    this.routes.clear();
  }

  /** "24 min", "1 h 05". Rounded, because a routed estimate is not a timetable. */
  protected travelLabel(seconds: number): string {
    const minutes = Math.round(seconds / 60);
    if (minutes < 60) {
      return `${minutes} min`;
    }
    return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')}`;
  }

  /** What the proposal saves, as words, or null when it saves nothing worth saying. */
  protected savingLabel(): string | null {
    const preview = this.routePreview();
    if (!preview || !preview.changed) {
      return null;
    }
    const saved = preview.currentSeconds - preview.proposedSeconds;
    return saved > 0 ? `${this.travelLabel(saved)} less travelling` : null;
  }

  /**
   * The day's stops, in their current order, as a Google Maps directions link.
   *
   * Built here from coordinates rather than fetched from the server, the way a
   * single place's Directions menu is: wander proxies nothing to Google, and
   * the URL is the documented `api=1` form that will not rot.
   *
   * Google's URL API takes nine waypoints between the origin and the
   * destination. A day with more stops than that links the first eleven and
   * says so, which beats a link that silently drops the afternoon.
   */
  protected mapsLink(day: TripDay): string | null {
    const points = this.locatedIn(day).map((place) => `${place.latitude},${place.longitude}`);
    if (points.length < 2) {
      return null;
    }
    const capped = points.slice(0, MAPS_MAX_STOPS);
    const origin = capped[0];
    const destination = capped[capped.length - 1];
    const between = capped.slice(1, -1);
    const url = new URLSearchParams({
      api: '1',
      origin,
      destination,
      travelmode: GOOGLE_TRAVEL_MODES[this.routeProfile()],
    });
    if (between.length) {
      url.set('waypoints', between.join('|'));
    }
    return `https://www.google.com/maps/dir/?${url.toString()}`;
  }

  protected mapsTitle(day: TripDay): string {
    const stops = this.locatedIn(day).length;
    return stops > MAPS_MAX_STOPS
      ? `Open the first ${MAPS_MAX_STOPS} stops in Google Maps — it takes no more`
      : 'Open this day in Google Maps';
  }

  /** Credit for whatever engine answered, shown while a proposal is on screen. */
  protected readonly routeCredit = computed(() => {
    const preview = this.routePreview();
    return preview && preview.attribution ? preview : null;
  });

  private locatedIn(day: TripDay): PlaceView[] {
    return day.places.filter((place) => place.latitude != null && place.longitude != null);
  }

  protected savedLabel(savedAt: number): string {
    return `Saved copy · ${ageLabel(savedAt)}`;
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
