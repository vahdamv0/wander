import { describe, expect, it } from 'vitest';
import type { PlaceView, TripDay, TripSummary } from '../api';
import { buildDayGpx, buildGpx, gpxFilename } from './gpx';

/**
 * The third unit test in this client, and it qualifies on the same grounds as
 * the first two: every way this function goes wrong is silent *here*.
 *
 * A GPX file is not read by wander. It is read six weeks later by OsmAnd on a
 * phone, and the failures all look identical from this side — an unescaped
 * ampersand in a note, a coordinate that serialised as `1e-7`, a control
 * character pasted out of a booking email. Each one produces a file this code
 * is perfectly happy with and the reader refuses whole, so the trip does not
 * appear and nothing anywhere logged a reason. The browser suite cannot help:
 * it can watch a download happen, not parse what was in it.
 */

const trip: TripSummary = {
  currency: 'JPY',
  dayCount: 2,
  destination: 'Japan',
  endDate: '2026-08-29',
  id: 7,
  myRole: 'OWNER',
  name: 'Tokyo & Kyoto',
  startDate: '2026-08-28',
};

function place(over: Partial<PlaceView> & { name: string; id: number }): PlaceView {
  return {
    dayDate: '2026-08-28',
    enrichable: false,
    locked: false,
    notes: [],
    position: 0,
    ...over,
  };
}

function day(over: Partial<TripDay> & { index: number; date: string }): TripDay {
  return { places: [], ...over };
}

const exportedAt = new Date('2026-09-16T10:00:00Z');

function parse(xml: string): Document {
  const parsed = new DOMParser().parseFromString(xml, 'application/xml');
  const failure = parsed.querySelector('parsererror');
  expect(failure?.textContent ?? null).toBeNull();
  return parsed;
}

describe('buildGpx', () => {
  it('writes a waypoint for every located place and a track for the day', () => {
    const result = buildGpx(trip, [
      day({
        index: 1,
        date: '2026-08-28',
        places: [
          place({ id: 1, name: 'Fushimi Inari', latitude: 34.9671, longitude: 135.7727 }),
          place({ id: 2, name: 'Nishiki Market', latitude: 35.0050, longitude: 135.7649 }),
        ],
      }),
    ], exportedAt);

    const doc = parse(result.xml);
    expect(doc.documentElement.tagName).toBe('gpx');
    expect(doc.querySelectorAll('wpt')).toHaveLength(2);
    expect(doc.querySelectorAll('trk')).toHaveLength(1);
    expect(doc.querySelectorAll('trkpt')).toHaveLength(2);
    expect(result.waypoints).toBe(2);
    expect(result.tracks).toBe(1);

    // The order of a day is the whole content of a track: a reader draws the
    // line in document order, so a sort slipping in here would redraw somebody's
    // day and look perfectly plausible doing it.
    const names = [...doc.querySelectorAll('trkpt > name')].map((n) => n.textContent);
    expect(names).toEqual(['Fushimi Inari', 'Nishiki Market']);
  });

  it('leaves out a place with no coordinates, and says which', () => {
    // Usually the note-to-self somebody typed rather than searched. It cannot be
    // a waypoint, and guessing a location for it would be worse than omitting
    // it — so the caller gets the name to show.
    const result = buildGpx(trip, [
      day({
        index: 1,
        date: '2026-08-28',
        places: [
          place({ id: 1, name: 'Pick up tickets' }),
          place({ id: 2, name: 'Senso-ji', latitude: 35.7148, longitude: 139.7967 }),
        ],
      }),
    ], exportedAt);

    expect(result.waypoints).toBe(1);
    expect(result.skipped).toEqual(['Pick up tickets']);
    expect(result.xml).not.toContain('Pick up tickets');
  });

  it('draws no track through a day with only one located stop', () => {
    // A one-point trkseg renders as nothing at all. The waypoint already says
    // where the stop is, so an empty line is noise in the reader's track list.
    const result = buildGpx(trip, [
      day({
        index: 1,
        date: '2026-08-28',
        places: [place({ id: 1, name: 'Senso-ji', latitude: 35.7148, longitude: 139.7967 })],
      }),
    ], exportedAt);

    expect(result.waypoints).toBe(1);
    expect(result.tracks).toBe(0);
    expect(parse(result.xml).querySelectorAll('trk')).toHaveLength(0);
  });

  it('keeps every day apart, one track each', () => {
    const result = buildGpx(trip, [
      day({
        index: 1,
        date: '2026-08-28',
        places: [
          place({ id: 1, name: 'A', latitude: 35.1, longitude: 139.1 }),
          place({ id: 2, name: 'B', latitude: 35.2, longitude: 139.2 }),
        ],
      }),
      day({
        index: 2,
        date: '2026-08-29',
        places: [
          place({ id: 3, name: 'C', latitude: 34.1, longitude: 135.1 }),
          place({ id: 4, name: 'D', latitude: 34.2, longitude: 135.2 }),
        ],
      }),
    ], exportedAt);

    const doc = parse(result.xml);
    expect(doc.querySelectorAll('trk')).toHaveLength(2);
    const trackNames = [...doc.querySelectorAll('trk > name')].map((n) => n.textContent);
    expect(trackNames).toEqual(['Day 1 — stops in order', 'Day 2 — stops in order']);
  });

  it('escapes the characters that would otherwise break the whole document', () => {
    // One unescaped ampersand does not corrupt one stop — the reader refuses the
    // file, so the entire trip fails to import. Trip names with "&" are ordinary
    // and notes with "<" are not rare.
    const result = buildGpx(trip, [
      day({
        index: 1,
        date: '2026-08-28',
        places: [
          place({
            id: 1,
            name: 'Dunkin\' Donuts & Co <main>',
            notes: ['Ask for the "set" menu & pay < ¥1000'],
            latitude: 35.1,
            longitude: 139.1,
          }),
          place({ id: 2, name: 'B', latitude: 35.2, longitude: 139.2 }),
        ],
      }),
    ], exportedAt);

    const doc = parse(result.xml);
    // Parsed back out, the text is exactly what was typed — which is the real
    // assertion. Checking for "&amp;" in the string would pass on a document
    // that double-escaped it.
    const name = doc.querySelector('wpt > name')?.textContent;
    expect(name).toBe('Dunkin\' Donuts & Co <main>');
    expect(doc.querySelector('wpt > desc')?.textContent)
      .toContain('Ask for the "set" menu & pay < ¥1000');
    // The trip name reaches the metadata by the same path.
    expect(doc.querySelector('metadata > name')?.textContent).toBe('Tokyo & Kyoto');
  });

  it('drops control characters, which cannot be escaped at all', () => {
    // XML 1.0 has no representation for these; they arrive by way of text pasted
    // out of a PDF or a confirmation email, and a reader rejects the document.
    const result = buildGpx(trip, [
      day({
        index: 1,
        date: '2026-08-28',
        places: [
          place({ id: 1, name: 'Hotel\u0007 Okura', latitude: 35.1, longitude: 139.1 }),
          place({ id: 2, name: 'B', latitude: 35.2, longitude: 139.2 }),
        ],
      }),
    ], exportedAt);

    expect(result.xml).not.toContain('\u0007');
    expect(parse(result.xml).querySelector('wpt > name')?.textContent).toBe('Hotel Okura');
  });

  it('writes coordinates as decimals, never in exponential notation', () => {
    // The trap, and the reason coordinates go through a function at all:
    // String(1e-7) is "1e-7", which is a fine JavaScript number and not a
    // decimal any GPX reader accepts. Greenwich and the equator are real places.
    const result = buildGpx(trip, [
      day({
        index: 1,
        date: '2026-08-28',
        places: [
          place({ id: 1, name: 'Prime meridian', latitude: 0.0000001, longitude: -0.0000001 }),
          place({ id: 2, name: 'B', latitude: 51.4779, longitude: 0.0 }),
        ],
      }),
    ], exportedAt);

    expect(result.xml).not.toMatch(/e-/i);
    const first = parse(result.xml).querySelector('wpt');
    expect(first?.getAttribute('lat')).toBe('0.0000001');
    expect(first?.getAttribute('lon')).toBe('-0.0000001');
  });

  it('trims trailing zeros without losing the number', () => {
    // The trim is cosmetic, but a greedy regex over "35.0000000" that took the
    // dot and left "35." would produce a document every reader refuses.
    const result = buildGpx(trip, [
      day({
        index: 1,
        date: '2026-08-28',
        places: [
          place({ id: 1, name: 'Equator', latitude: 0, longitude: 100 }),
          place({ id: 2, name: 'Round', latitude: 35, longitude: 139.1 }),
        ],
      }),
    ], exportedAt);

    const points = [...parse(result.xml).querySelectorAll('wpt')];
    expect(points.map((p) => [p.getAttribute('lat'), p.getAttribute('lon')]))
      .toEqual([['0', '100'], ['35', '139.1']]);
  });

  it('carries the day, the time and the notes into the description', () => {
    const result = buildGpx(trip, [
      day({
        index: 2,
        date: '2026-08-29',
        places: [
          place({
            id: 1,
            name: 'Senso-ji',
            latitude: 35.7148,
            longitude: 139.7967,
            startsAt: '09:15:00',
            address: '2-3-1 Asakusa, Taito City',
            notes: ['Go early', 'Second note'],
          }),
          place({ id: 2, name: 'B', latitude: 35.2, longitude: 139.2 }),
        ],
      }),
    ], exportedAt);

    const desc = parse(result.xml).querySelector('wpt > desc')?.textContent ?? '';
    expect(desc).toContain('Day 2 · 2026-08-29 · 09:15');
    expect(desc).toContain('2-3-1 Asakusa, Taito City');
    // The notes are the part of a place people would miss — they are why
    // removing one asks first.
    expect(desc).toContain('Go early');
    expect(desc).toContain('Second note');
  });

  it('says in the file that the lines between stops are not a route', () => {
    // wander asks OSRM for a duration matrix and never for geometry, so it does
    // not know the path. A <trk> that did not say so would be claiming one.
    const result = buildGpx(trip, [
      day({
        index: 1,
        date: '2026-08-28',
        places: [
          place({ id: 1, name: 'A', latitude: 35.1, longitude: 139.1 }),
          place({ id: 2, name: 'B', latitude: 35.2, longitude: 139.2 }),
        ],
      }),
    ], exportedAt);

    expect(parse(result.xml).querySelector('trk > desc')?.textContent)
      .toContain('not the route between them');
  });

  it('is a valid document with nothing on the trip at all', () => {
    const result = buildGpx(trip, [day({ index: 1, date: '2026-08-28' })], exportedAt);

    const doc = parse(result.xml);
    expect(doc.querySelectorAll('wpt')).toHaveLength(0);
    expect(doc.querySelector('metadata > time')?.textContent)
      .toBe('2026-09-16T10:00:00.000Z');
  });
});

describe('buildDayGpx', () => {
  const tokyo = day({
    index: 1,
    date: '2026-08-28',
    places: [
      place({ id: 1, name: 'Senso-ji', latitude: 35.7148, longitude: 139.7967 }),
      place({ id: 2, name: 'Skytree', latitude: 35.7101, longitude: 139.8107 }),
    ],
  });
  const kyoto = day({
    index: 2,
    date: '2026-08-29',
    places: [
      place({ id: 3, name: 'Kinkaku-ji', latitude: 35.0394, longitude: 135.7292 }),
      place({ id: 4, name: 'Nijō Castle', latitude: 35.0142, longitude: 135.7481 }),
    ],
  });

  it('writes one day and nothing from any other', () => {
    const result = buildDayGpx(trip, kyoto, exportedAt);

    const doc = parse(result.xml);
    expect([...doc.querySelectorAll('wpt > name')].map((n) => n.textContent))
      .toEqual(['Kinkaku-ji', 'Nijō Castle']);
    expect(result.xml).not.toContain('Senso-ji');
    expect(doc.querySelectorAll('trk')).toHaveLength(1);
  });

  it('names the file and the document so two days can be told apart', () => {
    // Both matter on a phone: the filename is what somebody picks in a file
    // manager, and the <name> is what the reader lists the track under — two
    // days both called "Tokyo & Kyoto" would be two identical rows.
    const first = buildDayGpx(trip, tokyo, exportedAt);
    const second = buildDayGpx(trip, kyoto, exportedAt);

    expect(first.filename).toBe('tokyo-kyoto-2026-08-28.gpx');
    expect(second.filename).toBe('tokyo-kyoto-2026-08-29.gpx');
    expect(parse(first.xml).querySelector('metadata > name')?.textContent)
      .toBe('Tokyo & Kyoto — Day 1');
    expect(parse(second.xml).querySelector('metadata > name')?.textContent)
      .toBe('Tokyo & Kyoto — Day 2');
  });

  it('produces exactly what the whole-trip export produces for that day', () => {
    // The load-bearing one: a day on its own and the same day inside the trip
    // must not drift, which is the reason this is one builder over a list of one
    // rather than a second way to write a waypoint.
    const whole = buildGpx(trip, [kyoto], exportedAt);
    const alone = buildDayGpx(trip, kyoto, exportedAt);

    const strip = (xml: string) => xml.replace(/<metadata>[\s\S]*?<\/metadata>/, '');
    expect(strip(alone.xml)).toBe(strip(whole.xml));
    expect(alone.waypoints).toBe(whole.waypoints);
    expect(alone.tracks).toBe(whole.tracks);
  });

  it('reports what that day could not take with it', () => {
    const result = buildDayGpx(trip, day({
      index: 3,
      date: '2026-08-30',
      places: [
        place({ id: 5, name: 'Laundry', notes: [] }),
        place({ id: 6, name: 'Nara Park', latitude: 34.685, longitude: 135.843 }),
      ],
    }), exportedAt);

    expect(result.waypoints).toBe(1);
    expect(result.tracks).toBe(0);
    expect(result.skipped).toEqual(['Laundry']);
  });
});

describe('gpxFilename', () => {
  it('makes a trip name safe to land on a filesystem', () => {
    expect(gpxFilename({ ...trip, name: 'Tokyo & Kyoto' })).toBe('tokyo-kyoto.gpx');
    expect(gpxFilename({ ...trip, name: 'Málaga 2027' })).toBe('malaga-2027.gpx');
    expect(gpxFilename({ ...trip, name: '  Road/trip  ' })).toBe('road-trip.gpx');
  });

  it('appends a day after the length cap, not before it', () => {
    // A long trip name must not truncate away the part that tells two files
    // apart, which is what capping the whole string would do.
    const long = { ...trip, name: 'A'.repeat(90) };
    expect(gpxFilename(long, '2026-08-28')).toBe(`${'a'.repeat(60)}-2026-08-28.gpx`);
    expect(gpxFilename({ ...trip, name: '🎌' }, '2026-08-28')).toBe('trip-2026-08-28.gpx');
  });

  it('never produces a dotfile', () => {
    // A name with nothing alphanumeric in it is unusual and entirely possible,
    // and ".gpx" is invisible in every file picker there is.
    expect(gpxFilename({ ...trip, name: '🎌' })).toBe('trip.gpx');
    expect(gpxFilename({ ...trip, name: '---' })).toBe('trip.gpx');
  });
});
