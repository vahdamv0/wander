import { describe, expect, it } from 'vitest';
import { OfflineError, ageLabel, isNetworkError, messageOf } from './errors';
import { cacheKeys } from './offline-cache';

/**
 * The pure half of offline. IndexedDB and the service worker are tested through
 * the browser suite, where they actually exist; these are the bits of logic that
 * decide *which* failure happened, and getting that wrong signs people out on a
 * train.
 */
describe('errors', () => {
  it('tells a network failure from a refusal', () => {
    // Angular reports "never got a response" as status 0. Everything else is the
    // server having an opinion.
    expect(isNetworkError({ status: 0 })).toBe(true);
    expect(isNetworkError(new OfflineError())).toBe(true);

    expect(isNetworkError({ status: 401 })).toBe(false);
    expect(isNetworkError({ status: 404 })).toBe(false);
    expect(isNetworkError({ status: 500 })).toBe(false);
    expect(isNetworkError(null)).toBe(false);
    expect(isNetworkError(new Error('boom'))).toBe(false);
  });

  it('prefers the server’s own message', () => {
    expect(messageOf({ status: 409, error: { message: '1 place falls outside' } }))
      .toBe('1 place falls outside');
  });

  it('explains an offline write without inventing a server message', () => {
    expect(messageOf(new OfflineError())).toContain("offline");
    expect(messageOf({ status: 0 })).toContain("offline");
  });

  it('falls back when there is nothing useful to say', () => {
    expect(messageOf({ status: 500 })).toBe('That did not work. Try again.');
    expect(messageOf({ status: 500, error: {} }, 'Could not save.')).toBe('Could not save.');
  });

  it('describes how old a saved copy is', () => {
    const now = Date.now();
    expect(ageLabel(now, now)).toBe('just now');
    expect(ageLabel(now - 30_000, now)).toBe('just now');
    expect(ageLabel(now - 60_000, now)).toBe('1 minute ago');
    expect(ageLabel(now - 5 * 60_000, now)).toBe('5 minutes ago');
    expect(ageLabel(now - 2 * 3_600_000, now)).toBe('2 hours ago');
    expect(ageLabel(now - 26 * 3_600_000, now)).toBe('1 day ago');
    expect(ageLabel(now - 3 * 86_400_000, now)).toBe('3 days ago');
    // A clock that has gone backwards must not produce "-3 minutes ago".
    expect(ageLabel(now + 60_000, now)).toBe('just now');
  });
});

describe('cache keys', () => {
  it('keeps one trip’s pages apart from another’s', () => {
    expect(cacheKeys.itinerary(7)).toBe('trip:7:itinerary');
    expect(cacheKeys.itinerary(8)).not.toBe(cacheKeys.itinerary(7));
    // And one page of a trip apart from another page of the same trip.
    expect(new Set([
      cacheKeys.itinerary(7),
      cacheKeys.members(7),
      cacheKeys.expenses(7),
      cacheKeys.packing(7),
      cacheKeys.reservations(7),
      cacheKeys.trips(),
      cacheKeys.session(),
    ]).size).toBe(7);
  });
});
