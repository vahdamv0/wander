import { Injectable, signal } from '@angular/core';
import { isNetworkError } from './errors';

/**
 * Whether anything is reachable.
 *
 * `navigator.onLine` is the starting point and not the authority: it says a
 * network interface exists, which is true of a hotel wifi portal that resolves
 * nothing and of a phone with one bar and no throughput. So a request that
 * actually failed at the network level is treated as stronger evidence than the
 * browser's opinion, and a request that actually succeeded is stronger still.
 */
@Injectable({ providedIn: 'root' })
export class Connectivity {
  private readonly _online = signal(true);

  readonly online = this._online.asReadonly();

  constructor() {
    if (typeof navigator !== 'undefined') {
      this._online.set(navigator.onLine !== false);
    }
    if (typeof window !== 'undefined') {
      // The events are the fast path — they fire the moment the interface goes
      // away, before anything has had a chance to fail.
      window.addEventListener('online', () => this._online.set(true));
      window.addEventListener('offline', () => this._online.set(false));
    }
  }

  /** Called by the repos: a request that worked proves more than any event. */
  markReachable(): void {
    this._online.set(true);
  }

  /** Called when a request failed. Only a *network* failure counts — a 404 does not. */
  markFailure(err: unknown): void {
    if (isNetworkError(err)) {
      this._online.set(false);
    }
  }
}
