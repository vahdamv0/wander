import { PlaceView, ReservationView, TripDay } from '../api';
import { isoDayInZone, timeInZone } from './zones';

export interface TodayActivity {
  key: string;
  title: string;
  time: string | null;
  startsAt: number | null;
  completed: boolean;
  place?: PlaceView;
  booking?: ReservationView;
}

/** A browser-local calendar date, without parsing a date-only string as UTC. */
export function localDateKey(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/**
 * Today's places and bookings, in schedule order.
 *
 * Place times have no stored timezone, so they are interpreted in the device's
 * local zone. Booking instants keep their own zones. A late adjustment is a
 * temporary preview: it shifts upcoming places, never fixed bookings.
 */
export function todayActivities(
  day: TripDay,
  bookings: ReservationView[],
  date: string,
  now: Date,
  lateByMinutes: number,
  completedKeys: ReadonlySet<string>,
): TodayActivity[] {
  const result: TodayActivity[] = [];
  for (const place of day.places) {
    const key = `place:${place.id}`;
    const startsAt = place.startsAt ? localDateTime(date, place.startsAt) : null;
    const shifted = startsAt && startsAt.getTime() > now.getTime()
      ? new Date(startsAt.getTime() + lateByMinutes * 60_000)
      : startsAt;
    result.push({
      key,
      title: place.name,
      time: shifted ? clock(shifted) : null,
      startsAt: shifted?.getTime() ?? null,
      completed: completedKeys.has(key),
      place,
    });
  }

  for (const booking of bookings) {
    if (isoDayInZone(booking.startsAt, booking.startZone) !== date) {
      continue;
    }
    const key = `booking:${booking.id}`;
    result.push({
      key,
      title: booking.title,
      time: timeInZone(booking.startsAt, booking.startZone),
      startsAt: new Date(booking.startsAt).getTime(),
      completed: completedKeys.has(key),
      booking,
    });
  }

  return result.sort((left, right) => {
    if (left.startsAt == null) return right.startsAt == null ? 0 : 1;
    if (right.startsAt == null) return -1;
    return left.startsAt - right.startsAt;
  });
}

/** Interpret the itinerary's wall-clock time in the device's local zone. */
function localDateTime(date: string, time: string): Date | null {
  const dateMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  const timeMatch = /^(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(time);
  if (!dateMatch || !timeMatch) {
    return null;
  }
  const [, year, month, day] = dateMatch;
  const [, hour, minute, second = '0'] = timeMatch;
  const values = [year, month, day, hour, minute, second].map(Number);
  if (values[1] < 1 || values[1] > 12 || values[2] < 1 || values[2] > 31
      || values[3] > 23 || values[4] > 59 || values[5] > 59) {
    return null;
  }
  const result = new Date(values[0], values[1] - 1, values[2], values[3], values[4], values[5]);
  if (result.getFullYear() !== values[0]
      || result.getMonth() !== values[1] - 1
      || result.getDate() !== values[2]) {
    return null;
  }
  return result;
}

function clock(date: Date): string {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}
