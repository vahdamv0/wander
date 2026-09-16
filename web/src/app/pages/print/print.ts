import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PlaceView, ReservationView, TripDay } from '../../api';
import { messageOf } from '../../core/errors';
import { formatMoney } from '../../core/money';
import { isoDayInZone, timeInZone, zoneAbbreviation } from '../../core/zones';
import { PhotoLoad } from '../../core/photo-load';
import { ExpenseRepo } from '../../repo/expense.repo';
import { MemberRepo } from '../../repo/member.repo';
import { PlaceRepo } from '../../repo/place.repo';
import { ReservationRepo } from '../../repo/reservation.repo';

/**
 * The two print choices, remembered per browser the way the theme is. A setting
 * you have to make again on every reprint is a setting that gets made wrong
 * once and then printed thirty times.
 */
const PAGE_BREAK_KEY = 'wander.print.page-break';
const PHOTOS_KEY = 'wander.print.photos';

/**
 * The itinerary as a document: one page you can print, or save as a PDF, and
 * carry.
 *
 * **No PDF library.** `window.print()` is already a PDF exporter in every
 * browser, it honours the reader's paper size and margins, it works offline from
 * the cache, and it needs no server endpoint — this page is assembled entirely
 * from repos the app already has. A server-side renderer would have meant a new
 * dependency, bundled fonts, and a second layout engine to disagree with the one
 * that produced everything else.
 *
 * **It opens with a cover**, which is what makes it a document somebody can hand
 * to the other people on the trip rather than a long list: the name, where and
 * when, who is coming and what it cost so far, then a page break. The cover *is*
 * the document's header — printing the title twice would be printing the title
 * twice, and it would also make "the heading called Tokyo" ambiguous to anything
 * looking for one.
 *
 * **Bookings are folded into their day**, rather than listed separately as they
 * are on screen. On screen they are a thing you maintain; on paper they are a
 * thing you follow, and "Tuesday" is how you look something up when you are
 * standing in a station. A booking whose day falls outside the trip's range still
 * appears, under its own heading — a flight out the night before is exactly the
 * kind of thing that must not silently vanish from the page you are carrying.
 *
 * **Every time carries its zone**, which is the opposite of the screen rule. On
 * screen a zone is shown only when it differs from the reader's, because a
 * domestic trip would otherwise be all noise. A printout is read in a different
 * place from where it was made — that is what it is for — so the zone the
 * document was printed in tells the reader nothing, and an unlabelled 09:15 is
 * a genuinely dangerous thing to hand somebody heading for an airport.
 */
@Component({
  selector: 'app-print',
  imports: [PhotoLoad, RouterLink],
  templateUrl: './print.html',
})
export class PrintPage {
  // PlaceRepo owns the itinerary — days, places, notes and what each day cost
  // arrive together in one read.
  private readonly itinerary = inject(PlaceRepo);
  private readonly bookings = inject(ReservationRepo);
  // The cover's two remaining facts. Neither is on the itinerary: the member
  // list is its own read, and a trip's *total* is arithmetic the server owns —
  // see `totalSpent`.
  private readonly people = inject(MemberRepo);
  private readonly ledger = inject(ExpenseRepo);

  readonly tripId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly trip = this.itinerary.trip;
  protected readonly days = this.itinerary.days;
  protected readonly savedAt = this.itinerary.savedAt;

  /**
   * One sheet per day, and photos: both off the shelf and both remembered.
   *
   * Photos default to **off**. Paper is white and ink is expensive, so the
   * plain printout stays light and the picture is something you ask for — the
   * same reasoning as the print stylesheet forcing a white background.
   */
  protected readonly onePagePerDay = signal(readFlag(PAGE_BREAK_KEY));
  protected readonly showPhotos = signal(readFlag(PHOTOS_KEY));

  /** Who is on the trip, for the cover. */
  protected readonly travellers = computed(() =>
    this.people.members().map((member) => member.displayName).join(', '),
  );

  /**
   * What the trip cost, from the server's own summary.
   *
   * Deliberately **not** the sum of `day.spentMinor`: an expense's date is not
   * range-checked against the trip, so the flight bought in March is in the
   * total and on no printed day, and the two figures are legitimately
   * different. Adding them up here would also be the client doing money
   * arithmetic, which is the one thing the ledger's rules forbid.
   *
   * Null rather than zero when nothing has been spent, like `spentMinor`: a
   * cover printing "Total spent 0.00" makes a claim nobody entered.
   */
  protected readonly totalSpent = computed(() => {
    const total = this.ledger.summary()?.totalMinor;
    return total ? this.money(total) : null;
  });

  /** Bookings that fall on a day of the trip, keyed by that day's date. */
  private readonly bookingsByDay = computed(() => {
    const grouped = new Map<string, ReservationView[]>();
    for (const booking of this.bookings.reservations()) {
      const day = isoDayInZone(booking.startsAt, booking.startZone);
      grouped.set(day, [...(grouped.get(day) ?? []), booking]);
    }
    return grouped;
  });

  /**
   * Bookings whose day is not one of the trip's own — the flight out the evening
   * before, the hotel night after. They are printed under their own heading
   * instead of being dropped, which is what grouping alone would do.
   */
  protected readonly bookingsOutsideTheTrip = computed(() => {
    const tripDays = new Set(this.days().map((day) => day.date));
    return this.bookings.reservations()
      .filter((booking) => !tripDays.has(isoDayInZone(booking.startsAt, booking.startZone)));
  });

  protected readonly anyBookings = computed(() => this.bookings.reservations().length > 0);

  protected readonly printedOn = new Date().toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });

  constructor() {
    effect(() => remember(PAGE_BREAK_KEY, this.onePagePerDay()));
    effect(() => remember(PHOTOS_KEY, this.showPhotos()));
    queueMicrotask(() => void this.load());
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      // All four in parallel: the document is one thing and a quarter of it is
      // not worth showing. Reservations are a separate page in the app but the
      // same journey on paper, and the members and the ledger are the cover.
      // Every one of them is a read-through cached read, so the document still
      // assembles from the device with no signal.
      await Promise.all([
        this.itinerary.load(this.id()),
        this.bookings.load(this.id()),
        this.people.load(this.id()),
        this.ledger.load(this.id()),
      ]);
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'This trip could not be loaded.'));
    } finally {
      this.loading.set(false);
    }
  }

  private id(): number {
    return Number(this.tripId());
  }

  protected print(): void {
    window.print();
  }

  protected bookingsFor(date: string): ReservationView[] {
    return this.bookingsByDay().get(date) ?? [];
  }

  protected dayHeading(date: string): string {
    // Parsed as a local date rather than through `new Date(string)`, which reads
    // a bare yyyy-mm-dd as UTC and can print the day before in western zones.
    const [year, month, day] = date.split('-').map(Number);
    return new Date(year, month - 1, day).toLocaleDateString(undefined, {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
    });
  }

  protected placeTime(startsAt: string): string {
    return startsAt.slice(0, 5);
  }

  protected kindLabel(kind: ReservationView['kind']): string {
    return kind.charAt(0) + kind.slice(1).toLowerCase();
  }

  /** Always with its zone — see the note on this class. */
  protected bookingTime(booking: ReservationView): string {
    return `${timeInZone(booking.startsAt, booking.startZone)} `
      + zoneAbbreviation(booking.startsAt, booking.startZone);
  }

  protected bookingEnd(booking: ReservationView): string | null {
    if (!booking.endsAt || !booking.endZone) {
      return null;
    }
    const endDay = isoDayInZone(booking.endsAt, booking.endZone);
    const sameDay = endDay === isoDayInZone(booking.startsAt, booking.startZone);
    const clock = `${timeInZone(booking.endsAt, booking.endZone)} `
      + zoneAbbreviation(booking.endsAt, booking.endZone);
    return sameDay ? clock : `${this.dayHeading(endDay)}, ${clock}`;
  }

  protected bookingDay(booking: ReservationView): string {
    return this.dayHeading(isoDayInZone(booking.startsAt, booking.startZone));
  }

  /** What a day cost, or null when nothing was spent on it. */
  protected dayCost(day: TripDay): string | null {
    return day.spentMinor == null ? null : this.money(day.spentMinor);
  }

  /**
   * A Commons image is licensed *per image*, so the credit travels with the
   * picture wherever it is drawn — including onto paper, where there is no
   * tooltip to hide it in. `Place.setPhoto` refuses a photo without both of
   * these, which is what makes printing one defensible.
   */
  protected photoCredit(place: PlaceView): string {
    return `${place.photoAuthor} · ${place.photoLicence}`;
  }

  protected money(minor: number): string {
    return formatMoney(minor, this.trip()?.currency ?? 'EUR');
  }
}

function readFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === 'true';
  } catch {
    // Private mode, or storage blocked. Unreadable storage is not an error.
    return false;
  }
}

function remember(key: string, value: boolean): void {
  try {
    localStorage.setItem(key, String(value));
  } catch {
    // The choice still applies to this printout; it just is not remembered.
  }
}
