import { Injectable, inject, signal } from '@angular/core';
import {
  AddMemberRequest,
  Api,
  TripMemberView,
  addMember,
  changeMemberRole,
  listMembers,
  removeMember,
} from '../api';

/**
 * The role names, taken from the generated model rather than written out again —
 * ng-openapi-gen inlines the Java enum as a union instead of exporting a type,
 * and a hand-kept copy would be the second definition CLAUDE.md warns about.
 */
export type TripRole = TripMemberView['role'];

/**
 * Who is on one trip. Like the other repos this is the only thing that talks
 * HTTP, so offline support lands here rather than in a component.
 *
 * Every write re-reads the list instead of patching it, the way PlaceRepo's
 * non-drag writes do: a transfer of ownership changes *two* rows, and guessing
 * which locally would be reimplementing the server's rule.
 */
@Injectable({ providedIn: 'root' })
export class MemberRepo {
  private readonly api = inject(Api);

  private readonly _members = signal<TripMemberView[]>([]);
  private readonly _loading = signal(false);
  private readonly _saving = signal(false);
  /** Which trip's list we hold, so a live update knows whether anyone is looking. */
  private _loadedTripId: number | null = null;

  readonly members = this._members.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly saving = this._saving.asReadonly();

  async load(tripId: number): Promise<void> {
    this._loading.set(true);
    try {
      this._members.set(await this.api.invoke(listMembers, { tripId }));
      this._loadedTripId = tripId;
    } finally {
      this._loading.set(false);
    }
  }

  /**
   * Re-reads only if this trip's list has actually been loaded. The People panel
   * is collapsed until somebody opens it, and a live update is no reason to
   * start fetching a list nobody is looking at.
   */
  async refreshIfLoaded(tripId: number): Promise<void> {
    if (this._loadedTripId === tripId) {
      await this.load(tripId);
    }
  }

  async add(tripId: number, body: AddMemberRequest): Promise<void> {
    await this.write(tripId, () => this.api.invoke(addMember, { tripId, body }));
  }

  /**
   * A new role for one member. `OWNER` is a transfer: the server makes the
   * target the owner and demotes the caller, which is why the re-read matters.
   */
  async changeRole(tripId: number, userId: number, role: TripRole): Promise<void> {
    await this.write(tripId, () =>
      this.api.invoke(changeMemberRole, { tripId, userId, body: { role } }),
    );
  }

  /** Removes somebody else. */
  async remove(tripId: number, userId: number): Promise<void> {
    await this.write(tripId, () => this.api.invoke(removeMember, { tripId, userId }));
  }

  /**
   * Leaves the trip yourself. The same endpoint as `remove`, but deliberately
   * without the re-read: the trip is a 404 for us the moment the call succeeds,
   * so reloading the list would turn a success into an error on screen.
   */
  async leave(tripId: number, userId: number): Promise<void> {
    this._saving.set(true);
    try {
      await this.api.invoke(removeMember, { tripId, userId });
      this.clear();
    } finally {
      this._saving.set(false);
    }
  }

  /** Forgets the list, for when the trip is no longer ours to see. */
  clear(): void {
    this._members.set([]);
    this._loadedTripId = null;
  }

  private async write(tripId: number, call: () => Promise<unknown>): Promise<void> {
    this._saving.set(true);
    try {
      await call();
      await this.load(tripId);
    } finally {
      this._saving.set(false);
    }
  }
}
