import { describe, expect, it } from 'vitest';
import { dayInZone, isoDayInZone } from './zones';

/**
 * The second unit test in this client, and it is here for the same reason the
 * first one is: a wrong answer is invisible from the outside.
 *
 * `isoDayInZone` decides which day of a trip a booking belongs to. Get it wrong
 * and nothing throws, nothing logs, and no request fails — the booking simply
 * does not appear under any day, which is precisely how it was first written.
 * The browser suite caught that, but only because the printout happened to be
 * asserted on; a keying bug deserves better than being noticed by accident.
 */
describe('isoDayInZone', () => {
  it('answers in the yyyy-mm-dd form the itinerary keys days by', () => {
    expect(isoDayInZone('2027-05-11T06:00:00Z', 'Asia/Tokyo')).toBe('2027-05-11');
  });

  it('is not the same thing as the display format, which is the trap', () => {
    // dayInZone is for a reader; this one is for a key. They must never be
    // confused, so this test states the difference rather than implying it.
    const instant = '2027-05-11T06:00:00Z';
    expect(dayInZone(instant, 'Asia/Tokyo')).not.toBe(isoDayInZone(instant, 'Asia/Tokyo'));
    expect(isoDayInZone(instant, 'Asia/Tokyo')).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('gives the local day, not the UTC one', () => {
    // 23:00 in Tokyo on the 11th is still the 10th in UTC. A booking made for a
    // late dinner has to land on the day it happens, not the day Greenwich
    // thinks it is.
    const lateInTokyo = '2027-05-11T14:00:00Z';
    expect(isoDayInZone(lateInTokyo, 'Asia/Tokyo')).toBe('2027-05-11');
    expect(isoDayInZone(lateInTokyo, 'UTC')).toBe('2027-05-11');

    const veryLateInTokyo = '2027-05-10T15:30:00Z'; // 00:30 on the 11th in Tokyo
    expect(isoDayInZone(veryLateInTokyo, 'Asia/Tokyo')).toBe('2027-05-11');
    expect(isoDayInZone(veryLateInTokyo, 'UTC')).toBe('2027-05-10');
  });

  it('pads single digits, or the key would not match a day_date', () => {
    expect(isoDayInZone('2027-01-05T12:00:00Z', 'UTC')).toBe('2027-01-05');
  });

  it('falls back to the instant rather than throwing on an unknown zone', () => {
    // The server validates zones against the tz database, so this should be
    // unreachable — but a document that loses a booking is worse than one that
    // files it a few hours out.
    expect(isoDayInZone('2027-05-11T06:00:00Z', 'Mars/Olympus')).toBe('2027-05-11');
  });
});
