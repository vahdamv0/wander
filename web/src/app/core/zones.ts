/**
 * Time zones, in the browser.
 *
 * The division of labour with the server: **the server turns a wall-clock time
 * and a zone into an instant**, because Java carries the full IANA database and
 * the arithmetic — gaps and overlaps at the two awkward hours of the year — is
 * easy to get subtly wrong here. **The browser turns an instant back into a
 * wall-clock time in a zone**, which is the direction `Intl` is genuinely good
 * at and needs no library.
 */

/** The zone this browser thinks it is in. The sensible default for a new booking. */
export function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/**
 * Every zone the browser knows, for the picker.
 *
 * `supportedValuesOf` is recent; where it is missing the list falls back to the
 * viewer's own zone plus UTC, which still lets them book something — the server
 * accepts any valid IANA id regardless of what this returns.
 */
export function allZones(): string[] {
  try {
    const supported = (
      Intl as unknown as { supportedValuesOf?: (key: string) => string[] }
    ).supportedValuesOf;
    if (supported) {
      return supported('timeZone');
    }
  } catch {
    // Fall through to the minimum below.
  }
  const own = browserZone();
  return own === 'UTC' ? ['UTC'] : [own, 'UTC'];
}

/** "12 Jul 2027, 09:15" as it reads *there*, whatever zone the viewer is in. */
export function formatInZone(instant: string, zone: string): string {
  return format(instant, zone, { dateStyle: 'medium', timeStyle: 'short' });
}

/** Just the clock: "09:15" in that zone. */
export function timeInZone(instant: string, zone: string): string {
  return format(instant, zone, { timeStyle: 'short' });
}

/** Just the day: "12 Jul 2027" in that zone. */
export function dayInZone(instant: string, zone: string): string {
  return format(instant, zone, { dateStyle: 'medium' });
}

/**
 * The calendar date in that zone as `yyyy-mm-dd` — the form the itinerary uses
 * for a day.
 *
 * Distinct from `dayInZone`, which formats for a human ("12 Jul 2027") and is a
 * display string. Using that one as a key is a quiet disaster: it never equals a
 * `dayDate`, so anything grouped by it matches no day at all and simply goes
 * missing, with no error anywhere. That is exactly what happened when the print
 * page was first written.
 *
 * Built from `formatToParts` rather than the widespread `en-CA` locale trick,
 * which happens to produce ISO-ish output but is not specified to.
 */
export function isoDayInZone(instant: string, zone: string): string {
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(new Date(instant));
    const part = (type: string) => parts.find((candidate) => candidate.type === type)?.value ?? '';
    return `${part('year')}-${part('month')}-${part('day')}`;
  } catch {
    // An unknown zone cannot reach here from the server, which validates them —
    // but the instant's own UTC date is a better answer than throwing.
    return instant.slice(0, 10);
  }
}

/** The short name a person would say — "JST", "BST" — for labelling a foreign time. */
export function zoneAbbreviation(instant: string, zone: string): string {
  try {
    const parts = new Intl.DateTimeFormat(undefined, {
      timeZone: zone,
      timeZoneName: 'short',
    }).formatToParts(new Date(instant));
    return parts.find((part) => part.type === 'timeZoneName')?.value ?? zone;
  } catch {
    return zone;
  }
}

function format(instant: string, zone: string, options: Intl.DateTimeFormatOptions): string {
  try {
    return new Intl.DateTimeFormat(undefined, { ...options, timeZone: zone })
      .format(new Date(instant));
  } catch {
    // An unknown zone should be impossible — the server validates it — but a
    // broken date is not worth a blank page.
    return new Intl.DateTimeFormat(undefined, options).format(new Date(instant));
  }
}
