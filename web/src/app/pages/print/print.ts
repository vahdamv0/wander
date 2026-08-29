import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ReservationView } from '../../api';
import { messageOf } from '../../core/errors';
import { formatMoney } from '../../core/money';
import { isoDayInZone, timeInZone, zoneAbbreviation } from '../../core/zones';
import { PlaceRepo } from '../../repo/place.repo';
import { ReservationRepo } from '../../repo/reservation.repo';

/**
 * The itinerary as a document: one page you can print, or save as a PDF, and
 * carry.
 *
 * **No PDF library.** `window.print()` is already a PDF exporter in every
 * browser, it honours the reader's paper size and margins, it works offline from
 * the cache, and it needs no server endpoint — this page is assembled entirely
 * from the two repos the app already has. A server-side renderer would have meant
 * a new dependency, bundled fonts, and a second layout engine to disagree with
 * the one that produced everything else.
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
  imports: [RouterLink],
  templateUrl: './print.html',
})
export class PrintPage {
  // PlaceRepo owns the itinerary — days, places and notes arrive together in
  // one read, which is what makes the whole document two requests.
  private readonly itinerary = inject(PlaceRepo);
  private readonly bookings = inject(ReservationRepo);

  readonly tripId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly trip = this.itinerary.trip;
  protected readonly days = this.itinerary.days;
  protected readonly savedAt = this.itinerary.savedAt;

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
    queueMicrotask(() => void this.load());
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      // Both, in parallel: the document is one thing and half of it is not worth
      // showing. Reservations are a separate page in the app but the same
      // journey on paper.
      await Promise.all([
        this.itinerary.load(this.id()),
        this.bookings.load(this.id()),
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

  protected money(minor: number): string {
    return formatMoney(minor, this.trip()?.currency ?? 'EUR');
  }
}
