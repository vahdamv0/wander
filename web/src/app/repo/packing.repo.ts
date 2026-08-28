import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Api,
  PackingItemRequest,
  TripPacking,
  createPackingItem,
  deletePackingItem,
  listPacking,
  setPackingItemPacked,
  updatePackingItem,
} from '../api';
import { Connectivity } from '../core/connectivity';
import { OfflineError } from '../core/errors';
import { OfflineCache, cacheKeys } from '../core/offline-cache';
import { SessionStore } from '../core/session.store';

/**
 * One trip's packing list, grouped by whose it is.
 *
 * The grouping arrives from the server, because the server is what knows the
 * member list — a member who has added nothing still gets a section, and this
 * repo would otherwise have to fetch the members to work that out.
 */
@Injectable({ providedIn: 'root' })
export class PackingRepo {
  private readonly api = inject(Api);
  private readonly cache = inject(OfflineCache);
  private readonly session = inject(SessionStore);
  private readonly connectivity = inject(Connectivity);

  private readonly _packing = signal<TripPacking | null>(null);
  private readonly _loading = signal(false);
  /** When this came from the device rather than the server. Null when fresh. */
  private readonly _savedAt = signal<number | null>(null);
  private readonly _saving = signal(false);

  readonly packing = this._packing.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly savedAt = this._savedAt.asReadonly();
  readonly saving = this._saving.asReadonly();

  readonly trip = computed(() => this._packing()?.trip ?? null);
  readonly groups = computed(() => this._packing()?.groups ?? []);
  readonly totalCount = computed(() => this._packing()?.totalCount ?? 0);
  readonly packedCount = computed(() => this._packing()?.packedCount ?? 0);
  readonly canEdit = computed(() => {
    const role = this.trip()?.myRole;
    return role === 'OWNER' || role === 'EDITOR';
  });

  async load(tripId: number): Promise<void> {
    if (this._packing()?.trip.id !== tripId) {
      this._packing.set(null);
    }
    this._loading.set(true);
    try {
      const read = await this.cache.readThrough(this.session.user()?.id ?? null,
        cacheKeys.packing(tripId), () => this.api.invoke(listPacking, { tripId }));
      this._packing.set(read.body);
      this._savedAt.set(read.savedAt);
    } finally {
      this._loading.set(false);
    }
  }

  async add(tripId: number, body: PackingItemRequest): Promise<void> {
    await this.write(tripId, () => this.api.invoke(createPackingItem, { tripId, body }));
  }

  async update(tripId: number, itemId: number, body: PackingItemRequest): Promise<void> {
    await this.write(tripId, () => this.api.invoke(updatePackingItem, { tripId, itemId, body }));
  }

  async remove(tripId: number, itemId: number): Promise<void> {
    await this.write(tripId, () => this.api.invoke(deletePackingItem, { tripId, itemId }));
  }

  /**
   * Ticking, and the second optimistic write in this project after
   * `PlaceRepo.move`. The justification is the same one: the checkbox has already
   * changed under the user's finger, so waiting for the round trip makes it flick
   * back and then forward again. The local copy moves first, the server is told,
   * and a failure puts the old value back.
   *
   * Unlike `move` it does **not** re-read afterwards: the server's answer for a
   * tick is exactly what was sent, so a second request would only be a way to
   * make the checkbox stutter.
   */
  async setPacked(tripId: number, itemId: number, packed: boolean): Promise<void> {
    // Before the optimistic tick, so the box does not flick on and off.
    this.requireOnline();
    const before = this._packing();
    this.applyPacked(itemId, packed);
    this._saving.set(true);
    try {
      await this.api.invoke(setPackingItemPacked, { tripId, itemId, body: { packed } });
    } catch (err) {
      if (before) {
        this._packing.set(before);
      }
      throw err;
    } finally {
      this._saving.set(false);
    }
  }

  /** Flips one item and the two counts that depend on it, without a round trip. */
  private applyPacked(itemId: number, packed: boolean): void {
    this._packing.update((current) => {
      if (!current) {
        return current;
      }
      // The name comes along with the tick. Without it the row says "packed" and
      // not by whom until something else forces a re-read — which on a shared item
      // is the half that actually answers "so who has the tent?".
      const byName = this.session.user()?.displayName;
      let moved = false;
      const groups = current.groups.map((group) => {
        if (!group.items.some((item) => item.id === itemId)) {
          return group;
        }
        moved = true;
        const items = group.items.map((item) =>
          item.id === itemId
            ? { ...item, packed, packedByName: packed ? byName : undefined }
            : item,
        );
        return { ...group, items, packedCount: items.filter((item) => item.packed).length };
      });
      if (!moved) {
        return current;
      }
      return {
        ...current,
        groups,
        packedCount: groups.reduce((total, group) => total + group.packedCount, 0),
      };
    });
  }


  /** Nothing is queued offline, so a write that cannot be sent is refused outright. */
  private requireOnline(): void {
    if (!this.connectivity.online()) {
      throw new OfflineError();
    }
  }

  private async write(tripId: number, call: () => Promise<unknown>): Promise<void> {
    this.requireOnline();
    this._saving.set(true);
    try {
      await call();
      await this.load(tripId);
    } finally {
      this._saving.set(false);
    }
  }
}
