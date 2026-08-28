import { Injectable, inject, signal } from '@angular/core';
import { SessionStore } from './session.store';

/**
 * The one hand-written copy of a server payload in this project.
 *
 * CLAUDE.md's rule is "never hand-write a DTO twice", and this breaks it
 * knowingly: the OpenAPI document describes HTTP operations, so a WebSocket
 * frame cannot appear in it and `ng-openapi-gen` has nothing to generate. The
 * mitigation is to keep the payload trivial and to keep it *invalidation only* —
 * it says what changed, never what it changed to, so the two definitions have
 * almost no surface on which to drift. Anything richer belongs on the REST side
 * where the contract loop can own it.
 */
export type TripChangeKind = 'ITINERARY' | 'MEMBERS' | 'TRIP_DELETED';

export interface TripChange {
  tripId: number;
  kind: TripChangeKind;
  actorUserId: number;
  /** Set only when somebody lost access; the server hangs up on them next. */
  revokedUserId: number | null;
}

export type SyncStatus = 'idle' | 'connecting' | 'live' | 'offline';

/** Backoff between reconnection attempts, and the point at which we stop trying. */
const RETRY_BASE_MS = 1000;
const RETRY_MAX_MS = 30_000;
const RETRY_LIMIT = 10;
/** Keepalive gap. Long enough to be cheap, short enough for a 60s idle proxy. */
const PING_INTERVAL_MS = 25_000;

/**
 * Live updates for one trip.
 *
 * **The only file in this project that touches WebSocket**, for the same reason
 * `trip-map.ts` is the only one that imports Leaflet: it is imperative,
 * stateful, and holds a resource that has to be released — a socket left open on
 * navigation keeps a server-side session alive and goes on delivering events to
 * a page nobody is looking at.
 *
 * What it does *not* do is decide anything. It exposes what arrived and whether
 * it is connected; re-reading is the page's job, because the page is what knows
 * which repos it is showing.
 *
 * Three things here are not obvious:
 *
 *  - **A reconnection is not the same as an event.** Anything that happened
 *    during the gap was never delivered, so `reconnected` counts resumptions and
 *    the page re-reads unconditionally when it changes. Without that, a laptop
 *    that slept through somebody else's edits looks up to date and is not.
 *  - **Losing access stops the retries.** The server sends the event and then
 *    closes, and the handshake would now be refused; retrying it ten times would
 *    be ten pointless 404s on the way out.
 *  - **The page still works with none of this.** Every failure path lands in
 *    'offline' and nothing else changes: a reverse proxy that will not upgrade
 *    costs the user live updates, not the application.
 */
@Injectable({ providedIn: 'root' })
export class TripSyncService {
  private readonly session = inject(SessionStore);

  private socket: WebSocket | null = null;
  private tripId: number | null = null;
  private retries = 0;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  /** True once this trip has been connected at least once, so a later open is a resumption. */
  private everConnected = false;
  /** Set when there is no point reconnecting: we stopped, or access is gone. */
  private done = false;

  private readonly _status = signal<SyncStatus>('idle');
  private readonly _lastChange = signal<TripChange | null>(null);
  private readonly _reconnected = signal(0);

  readonly status = this._status.asReadonly();
  /** The most recent event. A fresh object every time, so an effect fires even on a repeat. */
  readonly lastChange = this._lastChange.asReadonly();
  /** Increments on every resumption after the first connect. Re-read everything. */
  readonly reconnected = this._reconnected.asReadonly();

  constructor() {
    // A sleeping machine's socket often looks open and is not. Coming back to the
    // tab is the cheapest reliable moment to find out.
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && this.tripId !== null && !this.done
            && this._status() !== 'live') {
          this.retries = 0;
          this.open();
        }
      });
    }
  }

  /** Start (or switch) watching a trip. Safe to call repeatedly with the same id. */
  watch(tripId: number): void {
    if (this.tripId === tripId && (this._status() === 'live' || this._status() === 'connecting')) {
      return;
    }
    this.stop();
    this.tripId = tripId;
    this.done = false;
    this.everConnected = false;
    this.open();
  }

  /** Release the socket. Called when the page goes away. */
  stop(): void {
    this.done = true;
    this.clearTimers();
    const socket = this.socket;
    this.socket = null;
    this.tripId = null;
    this._status.set('idle');
    if (socket) {
      // Drop the handlers first: closing fires onclose, which would otherwise
      // schedule a reconnection to the trip we are leaving.
      socket.onopen = socket.onmessage = socket.onerror = socket.onclose = null;
      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        socket.close();
      }
    }
  }

  private open(): void {
    const tripId = this.tripId;
    if (tripId === null || this.done) {
      return;
    }
    this.clearTimers();
    this._status.set('connecting');

    // Same origin, so the session cookie rides along and no token is needed —
    // and wss:// whenever the page itself is https, or the browser blocks it as
    // mixed content.
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${scheme}://${location.host}/api/ws/trips/${tripId}`);
    this.socket = socket;

    socket.onopen = () => {
      this.retries = 0;
      this._status.set('live');
      this.pingTimer = setInterval(() => {
        if (socket.readyState === WebSocket.OPEN) {
          // Content-free: the server ignores anything a client sends. This is
          // only here to keep an idle proxy from deciding the socket is dead.
          socket.send('ping');
        }
      }, PING_INTERVAL_MS);

      if (this.everConnected) {
        // Whatever happened while we were away was never delivered.
        this._reconnected.update((count) => count + 1);
      }
      this.everConnected = true;
    };

    socket.onmessage = (event) => {
      const change = this.parse(event.data);
      if (!change) {
        return;
      }
      // Our own access just went: the next handshake would be refused, so do not
      // spend ten retries finding that out.
      if (change.revokedUserId !== null && change.revokedUserId === this.session.user()?.id) {
        this.done = true;
      }
      if (change.kind === 'TRIP_DELETED') {
        this.done = true;
      }
      this._lastChange.set(change);
    };

    socket.onerror = () => {
      // Always followed by onclose, which is where the retry lives.
      this._status.set('offline');
    };

    socket.onclose = () => {
      this.socket = null;
      this.clearTimers();
      this._status.set('offline');
      this.scheduleRetry();
    };
  }

  private scheduleRetry(): void {
    if (this.done || this.tripId === null || this.retries >= RETRY_LIMIT) {
      return;
    }
    // Exponential, capped, and jittered: without the jitter every client that
    // rode out the same restart comes back in the same millisecond.
    const backoff = Math.min(RETRY_BASE_MS * 2 ** this.retries, RETRY_MAX_MS);
    const wait = backoff / 2 + Math.random() * (backoff / 2);
    this.retries += 1;
    this.retryTimer = setTimeout(() => this.open(), wait);
  }

  /** Anything that is not a change we understand is dropped, loudly enough to debug. */
  private parse(data: unknown): TripChange | null {
    if (typeof data !== 'string') {
      return null;
    }
    try {
      const parsed = JSON.parse(data) as Partial<TripChange>;
      if (parsed.kind !== 'ITINERARY' && parsed.kind !== 'MEMBERS' && parsed.kind !== 'TRIP_DELETED') {
        return null;
      }
      return {
        tripId: Number(parsed.tripId),
        kind: parsed.kind,
        actorUserId: Number(parsed.actorUserId),
        revokedUserId: parsed.revokedUserId ?? null,
      };
    } catch {
      return null;
    }
  }

  private clearTimers(): void {
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    if (this.pingTimer !== null) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }
}
