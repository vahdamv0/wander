/**
 * One place to turn a thrown thing into a sentence.
 *
 * Every page had its own copy of this, digging the server's error envelope out
 * of an HttpErrorResponse. They are collapsed here because offline adds a second
 * kind of failure that is not an envelope at all, and five copies would have
 * meant five places to teach about it.
 */

/**
 * A write that was not attempted because there is no connection.
 *
 * Distinct from a failed request: nothing was sent, so nothing is half-done and
 * there is nothing to retry on the server's side.
 */
export class OfflineError extends Error {
  constructor(message = "You're offline. This can't be saved until you reconnect.") {
    super(message);
    this.name = 'OfflineError';
  }
}

/**
 * True when a request failed because nothing was reachable, rather than because
 * the server said no.
 *
 * Telling this apart from a 401 is the difference between "you are signed out"
 * and "you are on a train", and the whole of offline reads hangs on it.
 *
 * Two statuses, and the second one is the trap. Angular reports a bare network
 * failure as **status 0**. But once a service worker controls the page it
 * intercepts every request, and for anything it does not cache it passes through
 * to the network and — when that fails — synthesises a **504 Gateway Timeout**
 * rather than letting the fetch reject. So with the worker installed, which is
 * exactly the state offline reads depend on, an unreachable server looks like an
 * ordinary HTTP error unless 504 is listed here. A real gateway timeout means the
 * same thing anyway: the request never reached anything that could answer it.
 */
export function isNetworkError(err: unknown): boolean {
  if (err instanceof OfflineError) {
    return true;
  }
  const status = (err as { status?: number } | null)?.status;
  return status === 0 || status === 504;
}

/** A genuine "you are not signed in", which is the only thing that clears the cache. */
export function isUnauthorized(err: unknown): boolean {
  return (err as { status?: number } | null)?.status === 401;
}

/** The server's message if it sent one, this error's own if not, else the fallback. */
export function messageOf(err: unknown, fallback = 'That did not work. Try again.'): string {
  if (err instanceof OfflineError) {
    return err.message;
  }
  const body = (err as { error?: { message?: string; fields?: Record<string, string> } } | null)
    ?.error;

  // A validation failure's own `message` is the envelope's "Validation failed",
  // which tells the person typing nothing at all — the reason is in `fields`,
  // one entry per field that was refused. Prefer that. It matters most for the
  // password rules, where "Validation failed" would leave somebody guessing at
  // what is wrong with a password the form let them type.
  const fields = body?.fields;
  if (fields) {
    const first = Object.values(fields).find((value) => typeof value === 'string' && value);
    if (first) {
      return first;
    }
  }

  if (typeof body?.message === 'string' && body.message) {
    return body.message;
  }
  if (isNetworkError(err)) {
    return "You're offline. This can't be saved until you reconnect.";
  }
  return fallback;
}

/** "2 hours ago", for saying how old a cached copy is. */
export function ageLabel(savedAt: number, now = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - savedAt) / 1000));
  if (seconds < 60) {
    return 'just now';
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return `${minutes} minute${minutes === 1 ? '' : 's'} ago`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `${hours} hour${hours === 1 ? '' : 's'} ago`;
  }
  const days = Math.round(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}
