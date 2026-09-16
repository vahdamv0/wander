import type { PlaceView, TripDay, TripSummary } from '../api';

/**
 * A trip as a GPX 1.1 document: every located place as a waypoint, and each
 * day's stops as a track.
 *
 * **Built here rather than on the server, which is the print page's argument
 * again.** A GPX file is a reformatting of `TripItinerary` — name, latitude,
 * longitude, order, day — and every one of those is already on the device, read
 * through `OfflineCache` like everything else. There is no arithmetic for the
 * server to own the way it owns a split, so an endpoint would re-read the same
 * rows to produce the same bytes, would not work on the hotel wifi that made
 * somebody want the file in the first place, and would put an XML response on a
 * contract whose generated client assumes JSON — the exact shape of bug the
 * browser suite exists to catch.
 *
 * **Tracks, not routes, and that is a compatibility decision rather than a
 * semantic one.** GPX's `<rte>` is its element for a planned sequence of turn
 * points, which is precisely what a wander day is; `<trk>` describes a path that
 * was travelled. But OsmAnd and Organic Maps both draw a `<trk>` reliably and
 * only one of them can be relied on for a `<rte>`, and a file that opens empty
 * is a worse answer than an imprecise element name. So the honesty moves into
 * the document: a track is named "stops in order" and its description says the
 * line between two stops is drawn straight, because **wander does not know the
 * route** — the routing feature asks OSRM for `/table` and never `/route`, so
 * there is no geometry here to write down and inventing one would be the
 * `places.category` mistake in another costume.
 *
 * **No `<time>` on a point.** `places.starts_at` is a plain `TIME` with no zone
 * — deliberately, since the day supplies the date and the place supplies the
 * zone — so turning one into the instant GPX wants would mean choosing a zone on
 * the reader's behalf. The clock time goes in the description, where it is
 * plainly a label. The only `<time>` in the file is on the metadata, which
 * records when the file was written: the same fact, and for the same reason, as
 * the date in the printed itinerary's footer.
 */

/** What an export produced, and what it could not. */
export interface GpxExport {
  /** The document itself. */
  readonly xml: string;
  /** What to call it when it lands in somebody's downloads. */
  readonly filename: string;
  /** How many places made it in as waypoints. */
  readonly waypoints: number;
  /** How many days had enough located stops to draw a line through. */
  readonly tracks: number;
  /**
   * The names of places left out for having no coordinates, in itinerary order.
   *
   * Returned rather than counted so the caller can name them. A place with no
   * location is usually the note-to-self somebody typed rather than searched
   * ("pick up tickets"), and being told which ones are missing is the difference
   * between a disappointing answer and a mysterious one — the same reason a
   * route proposal names the stops it could not move.
   */
  readonly skipped: string[];
}

/** Coordinates a GPX reader will accept, from a place that has them. */
interface Located extends PlaceView {
  latitude: number;
  longitude: number;
}

/** What a file covers, which is the only thing the two exports disagree about. */
interface Scope {
  /** The document's own `<name>`, which is what a reader lists it under. */
  readonly title: string;
  /** Appended to the filename's slug, or blank for the whole trip. */
  readonly suffix: string;
}

/** The whole trip: every day, in order. */
export function buildGpx(
  trip: TripSummary,
  days: TripDay[],
  exportedAt: Date = new Date(),
): GpxExport {
  return build(trip, days, exportedAt, { title: trip.name, suffix: '' });
}

/**
 * One day, which is the unit somebody actually carries.
 *
 * The same builder over a list of one — there is no second way to write a
 * waypoint here, so a day exported on its own and the same day inside a whole
 * trip cannot drift apart. Only the label and the filename differ, and both are
 * about telling two files apart once they are on a phone: a reader lists a track
 * by its `<name>`, so "Barcelona & Girona" twice over would be two identical
 * rows, and the date is in the filename because that is what somebody matches
 * against when they are standing in the day itself.
 */
export function buildDayGpx(
  trip: TripSummary,
  day: TripDay,
  exportedAt: Date = new Date(),
): GpxExport {
  return build(trip, [day], exportedAt, {
    title: `${trip.name} — Day ${day.index}`,
    suffix: day.date,
  });
}

function build(
  trip: TripSummary,
  days: TripDay[],
  exportedAt: Date,
  scope: Scope,
): GpxExport {
  const skipped: string[] = [];
  const waypoints: string[] = [];
  const tracks: string[] = [];

  for (const day of days) {
    const located: Located[] = [];
    for (const place of day.places) {
      if (isLocated(place)) {
        located.push(place);
      } else {
        skipped.push(place.name);
      }
    }

    for (const place of located) {
      waypoints.push(waypoint(place, day));
    }

    // A single-point track draws nothing in any reader — the waypoint above
    // already says where that stop is, so a day with one located place
    // contributes no line rather than an invisible one.
    if (located.length > 1) {
      tracks.push(track(located, day));
    }
  }

  const xml = document(trip, scope, exportedAt, waypoints, tracks);
  return {
    xml,
    filename: gpxFilename(trip, scope.suffix),
    waypoints: waypoints.length,
    tracks: tracks.length,
    skipped,
  };
}

/**
 * `tokyo-and-kyoto.gpx`, from the trip's own name — or
 * `tokyo-and-kyoto-2026-08-28.gpx` for a single day.
 *
 * A name is free text and lands on somebody's filesystem, so everything that is
 * not a letter or a digit becomes a hyphen — which also takes care of the
 * separators, the quotes and the emoji people put in trip names. A name with
 * nothing left of it falls back to `trip`, because `.gpx` on its own is a
 * hidden file on every Unix machine there is.
 *
 * The suffix is added *after* the length cap, or a long trip name would truncate
 * the one part that tells two of these files apart.
 */
export function gpxFilename(trip: TripSummary, suffix = ''): string {
  const slug = trip.name
    .normalize('NFKD')
    // Strip the accents the decomposition just separated out, so "Málaga"
    // becomes "malaga" rather than "m-laga".
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60)
    .replace(/-+$/, '');
  return `${slug || 'trip'}${suffix ? `-${suffix}` : ''}.gpx`;
}

function isLocated(place: PlaceView): place is Located {
  return place.latitude != null && place.longitude != null;
}

function document(
  trip: TripSummary,
  scope: Scope,
  exportedAt: Date,
  waypoints: string[],
  tracks: string[],
): string {
  const description = [
    trip.destination,
    `${trip.startDate} to ${trip.endDate}`,
  ].filter(Boolean).join(' · ');

  // Schema order is metadata, then every wpt, then rte, then trk. A reader that
  // validates rejects the file outright if they are interleaved.
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<gpx version="1.1" creator="wander"'
      + ' xmlns="http://www.topografix.com/GPX/1/1"'
      + ' xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"'
      + ' xsi:schemaLocation="http://www.topografix.com/GPX/1/1'
      + ' http://www.topografix.com/GPX/1/1/gpx.xsd">',
    '  <metadata>',
    `    <name>${escapeText(scope.title)}</name>`,
    `    <desc>${escapeText(description)}</desc>`,
    // When the file was written, which is the one honest timestamp here: the
    // document stops being true the moment somebody edits the trip.
    `    <time>${exportedAt.toISOString()}</time>`,
    '  </metadata>',
    ...waypoints,
    ...tracks,
    '</gpx>',
    '',
  ].join('\n');
}

function waypoint(place: Located, day: TripDay): string {
  const lines = [
    `  <wpt lat="${coordinate(place.latitude)}" lon="${coordinate(place.longitude)}">`,
    `    <name>${escapeText(place.name)}</name>`,
  ];
  const description = placeDescription(place, day);
  if (description) {
    lines.push(`    <desc>${escapeText(description)}</desc>`);
  }
  if (place.category) {
    // `<type>` is GPX's own "classification of waypoint", which is exactly what
    // the geocoder's word for a place is — and it stays decorative here as it is
    // everywhere else. Nothing is inferred from it and nothing is inferred from
    // the name to fill it in.
    lines.push(`    <type>${escapeText(place.category)}</type>`);
  }
  lines.push('  </wpt>');
  return lines.join('\n');
}

/**
 * What a reader shows when somebody taps a pin: which day it is on, the time if
 * one was set, the address, and whatever notes were written on it.
 *
 * The notes are the part worth carrying. They are the reason removing a place
 * asks first, and a GPX that dropped them would be a worse copy of the trip than
 * the printed page.
 */
function placeDescription(place: Located, day: TripDay): string {
  const heading = [`Day ${day.index}`, day.date, place.startsAt?.slice(0, 5)]
    .filter(Boolean)
    .join(' · ');
  return [heading, place.address, ...place.notes].filter(Boolean).join('\n');
}

function track(places: Located[], day: TripDay): string {
  return [
    '  <trk>',
    `    <name>Day ${day.index} — stops in order</name>`,
    // Said in the file rather than only in the interface, because the file is
    // what gets opened six weeks later on a phone. wander knows the order of a
    // day and not the path between its stops.
    `    <desc>${escapeText(trackDescription(day))}</desc>`,
    '    <trkseg>',
    ...places.map((place) =>
      `      <trkpt lat="${coordinate(place.latitude)}" lon="${coordinate(place.longitude)}">`
      + `<name>${escapeText(place.name)}</name></trkpt>`),
    '    </trkseg>',
    '  </trk>',
  ].join('\n');
}

function trackDescription(day: TripDay): string {
  const note = day.note ? `${day.note}\n` : '';
  return `${note}${day.date}. Straight lines between stops — this is the order`
    + ' they are planned in, not the route between them.';
}

/**
 * Seven decimal places, which is about a centimetre and more than a geocoder
 * offers.
 *
 * `toFixed` rather than `String`, and that is the whole reason this is a
 * function. A longitude near the prime meridian serialises as `1e-7` through
 * the default conversion, which is a perfectly good JavaScript number and not a
 * decimal GPX accepts — the reader either refuses the file or drops the point,
 * and nothing here would have said so.
 */
function coordinate(value: number): string {
  // Trailing zeros trimmed afterwards, which is cosmetic — `35.7148000` is a
  // perfectly good decimal and every reader takes it. `toFixed` is the part
  // that matters.
  return value.toFixed(7).replace(/\.?0+$/, '');
}

/**
 * Text that will not break the document.
 *
 * Two jobs, and both fail the same silent way — a malformed file is refused
 * whole by the reader, so one bad character in one note costs the entire trip
 * rather than one stop. The first is the ordinary five entities; a place called
 * "Dunkin' Donuts & Co" or a note containing "<3" is not unusual. The second is
 * the control characters XML 1.0 has no representation for at all, which arrive
 * by way of text pasted out of a PDF or a booking email: they cannot be escaped,
 * only dropped.
 */
function escapeText(text: string): string {
  return text.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}
