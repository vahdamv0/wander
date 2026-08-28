import { Injectable, inject } from '@angular/core';
import { Connectivity } from './connectivity';
import { isNetworkError } from './errors';

/** What a read produced, and how old it is. `savedAt` is null when it is fresh. */
export interface CachedRead<T> {
  body: T;
  savedAt: number | null;
}

interface Entry {
  key: string;
  userId: number;
  savedAt: number;
  body: unknown;
}

const DB_NAME = 'wander';
const DB_VERSION = 1;
const STORE = 'responses';

/**
 * The last copy of what the server said, on this device.
 *
 * Written on every successful read and served when the network is gone, which is
 * the whole of "offline reads": the app has no separate offline mode, it just has
 * an answer for a request that cannot be made.
 *
 * **Entries are keyed by user and the store is emptied on sign-out.** Both, not
 * either. This is the first time wander keeps trip data on a device, and without
 * both rules the next person to use the browser could read the last person's
 * itineraries, expenses and booking references straight out of IndexedDB.
 */
@Injectable({ providedIn: 'root' })
export class OfflineCache {
  private readonly connectivity = inject(Connectivity);
  private database: Promise<IDBDatabase | null> | null = null;

  /**
   * Fetch, and fall back to the last copy if nothing is reachable.
   *
   * Only a *network* failure falls back. A 401, 404 or 500 is rethrown, because
   * the server answering "that is gone" must not be papered over with a copy of
   * what it used to say — a deleted trip has to disappear.
   *
   * With nothing cached, the network failure is rethrown too: an error is better
   * than a spinner that never resolves.
   */
  async readThrough<T>(userId: number | null, key: string, fetch: () => Promise<T>):
      Promise<CachedRead<T>> {
    try {
      const body = await fetch();
      this.connectivity.markReachable();
      if (userId !== null) {
        void this.put(userId, key, body);
      }
      return { body, savedAt: null };
    } catch (err) {
      if (!isNetworkError(err) || userId === null) {
        throw err;
      }
      this.connectivity.markFailure(err);
      const cached = await this.get<T>(userId, key);
      if (!cached) {
        throw err;
      }
      return { body: cached.body, savedAt: cached.savedAt };
    }
  }

  async get<T>(userId: number, key: string): Promise<{ body: T; savedAt: number } | null> {
    const db = await this.open();
    if (!db) {
      return null;
    }
    return new Promise((resolve) => {
      const request = db.transaction(STORE, 'readonly').objectStore(STORE).get(key);
      request.onsuccess = () => {
        const entry = request.result as Entry | undefined;
        // The user check is belt and braces next to `clearAll` on sign-out: if a
        // clear ever failed, a stale entry still must not be handed to somebody
        // it does not belong to.
        resolve(entry && entry.userId === userId
          ? { body: entry.body as T, savedAt: entry.savedAt }
          : null);
      };
      request.onerror = () => resolve(null);
    });
  }

  async put(userId: number, key: string, body: unknown): Promise<void> {
    const db = await this.open();
    if (!db) {
      return;
    }
    await new Promise<void>((resolve) => {
      const transaction = db.transaction(STORE, 'readwrite');
      transaction.objectStore(STORE).put({ key, userId, savedAt: Date.now(), body } satisfies Entry);
      transaction.oncomplete = () => resolve();
      // A cache that cannot be written is not an error worth surfacing: the page
      // has its data, it simply will not have it again offline.
      transaction.onerror = () => resolve();
      transaction.onabort = () => resolve();
    });
  }

  /** Everything, for everyone. Called on sign-out, and on discovering a different user. */
  async clearAll(): Promise<void> {
    const db = await this.open();
    if (!db) {
      return;
    }
    await new Promise<void>((resolve) => {
      const transaction = db.transaction(STORE, 'readwrite');
      transaction.objectStore(STORE).clear();
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => resolve();
      transaction.onabort = () => resolve();
    });
  }

  /**
   * Opened lazily and at most once. A browser with IndexedDB blocked — private
   * mode in some browsers, or a locked-down profile — resolves to null and every
   * method above becomes a no-op, which degrades to exactly the behaviour before
   * this feature existed.
   */
  private open(): Promise<IDBDatabase | null> {
    if (!this.database) {
      this.database = new Promise((resolve) => {
        if (typeof indexedDB === 'undefined') {
          resolve(null);
          return;
        }
        try {
          const request = indexedDB.open(DB_NAME, DB_VERSION);
          request.onupgradeneeded = () => {
            if (!request.result.objectStoreNames.contains(STORE)) {
              request.result.createObjectStore(STORE, { keyPath: 'key' });
            }
          };
          request.onsuccess = () => resolve(request.result);
          request.onerror = () => resolve(null);
          request.onblocked = () => resolve(null);
        } catch {
          resolve(null);
        }
      });
    }
    return this.database;
  }
}

/** The keys. Gathered here so a typo is a compile error rather than a silent miss. */
export const cacheKeys = {
  trips: () => 'trips',
  itinerary: (tripId: number) => `trip:${tripId}:itinerary`,
  members: (tripId: number) => `trip:${tripId}:members`,
  expenses: (tripId: number) => `trip:${tripId}:expenses`,
  packing: (tripId: number) => `trip:${tripId}:packing`,
  reservations: (tripId: number) => `trip:${tripId}:reservations`,
  session: () => 'session',
};
