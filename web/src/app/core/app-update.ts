import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { SwUpdate } from '@angular/service-worker';

/**
 * Whether a newer build is sitting in the cache, waiting to be run.
 *
 * ngsw serves a new build on the next *load*, which was fine while wander was
 * only ever a tab. An installed window has no reload button and stays open for
 * the length of a holiday, so without a prompt somebody sits on the build they
 * installed with no way to find out.
 *
 * It asks rather than reloading — a reload throws away whatever is in a form,
 * and ignoring it costs nothing, since the next load applies the build anyway.
 */
@Injectable({ providedIn: 'root' })
export class AppUpdate {
  /** Six hours. Long enough to be invisible, short enough to catch a release. */
  private static readonly CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

  private readonly updates = inject(SwUpdate);
  private readonly _ready = signal(false);

  /** A new build is downloaded and will run on the next activation. */
  readonly ready = this._ready.asReadonly();

  constructor() {
    // False under `ng serve` and wherever there is no worker support.
    if (!this.updates.isEnabled) {
      return;
    }

    const subscription = this.updates.versionUpdates.subscribe((event) => {
      if (event.type === 'VERSION_READY') {
        this._ready.set(true);
      }
    });

    // The page is already broken by this point, so a reload is the recovery
    // rather than an interruption.
    const unrecoverable = this.updates.unrecoverable.subscribe(() => location.reload());

    // ngsw checks on load and never again, and the whole problem is a window
    // that does not load. A phone suspends timers but does fire visibilitychange.
    const timer = setInterval(() => void this.check(), AppUpdate.CHECK_INTERVAL_MS);
    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        void this.check();
      }
    };
    document.addEventListener('visibilitychange', onVisible);

    inject(DestroyRef).onDestroy(() => {
      subscription.unsubscribe();
      unrecoverable.unsubscribe();
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    });
  }

  /** Activation swaps the cache; only a reload makes the page run it. */
  async apply(): Promise<void> {
    try {
      await this.updates.activateUpdate();
    } finally {
      // Even if activation failed: a reload picks up a pending version anyway.
      location.reload();
    }
  }

  /** Never throws: a failed check means offline, which here is ordinary. */
  private async check(): Promise<void> {
    try {
      await this.updates.checkForUpdate();
    } catch {
      // Offline, or the deploy is mid-flight. The next check does the job.
    }
  }
}
