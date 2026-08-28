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
