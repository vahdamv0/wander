import { describe, expect, it } from 'vitest';
import { PlaceView, ReservationView, TripDay } from '../api';
import { localDateKey, todayActivities } from './today';
import { browserZone, timeInZone } from './zones';

describe('Today view schedule', () => {
  it('formats a browser-local date without converting it to UTC', () => {
    expect(localDateKey(new Date(2026, 9, 3, 23, 45))).toBe('2026-10-03');
  });

  it('orders places and bookings and temporarily delays only upcoming places', () => {
    const date = localDateKey(new Date(2026, 9, 3));
    const now = new Date(2026, 9, 3, 8, 30);
    const breakfast = place(1, 'Breakfast', '08:00:00');
    const shrine = place(2, 'Fushimi Inari', '09:00:00');
    const day: TripDay = {
      date,
      index: 1,
      places: [shrine, breakfast],
    };
    const booking = reservation(7, date, '14:00');

    const timeline = todayActivities(day, [booking], date, now, 30, new Set(['place:1']));

    expect(timeline.map(({ title }) => title))
      .toEqual(['Breakfast', 'Fushimi Inari', 'Museum']);
    expect(timeline.slice(0, 2).map(({ time }) => time)).toEqual(['08:00', '09:30']);
    expect(timeline[0].completed).toBe(true);
    expect(timeline[2].time).toBe(timeInZone(booking.startsAt, booking.startZone));
  });

  it('leaves unscheduled places on the timeline after timed items', () => {
    const date = localDateKey(new Date(2026, 9, 3));
    const day: TripDay = {
      date,
      index: 1,
      places: [place(1, 'Pick up tickets'), place(2, 'Lunch', '12:30:00')],
    };

    expect(todayActivities(day, [], date, new Date(2026, 9, 3, 8), 0, new Set())
      .map((activity) => [activity.title, activity.time]))
      .toEqual([['Lunch', '12:30'], ['Pick up tickets', null]]);
  });
});

function place(id: number, name: string, startsAt?: string): PlaceView {
  return {
    id,
    name,
    dayDate: '2026-10-03',
    position: id - 1,
    notes: [],
    enrichable: false,
    locked: false,
    ...(startsAt ? { startsAt } : {}),
  };
}

function reservation(id: number, date: string, time: string): ReservationView {
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = time.split(':').map(Number);
  const startsAt = new Date(year, month - 1, day, hour, minute).toISOString();
  const startZone = browserZone();
  return {
    id,
    kind: 'ACTIVITY',
    title: 'Museum',
    confirmation: 'KYO-42',
    startZone,
    startsAt,
    startsAtLocal: `${date}T${time}:00`,
  };
}
