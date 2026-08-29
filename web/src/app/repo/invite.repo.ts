import { Injectable, effect, inject, signal } from '@angular/core';
import {
  Api,
  CreatedInviteView,
  InvitePreview,
  TripInviteView,
  acceptInvite,
  createInvite,
  listInvites,
  previewInvite,
  revokeInvite,
} from '../api';
import { Connectivity } from '../core/connectivity';
import { OfflineError } from '../core/errors';
import { SessionStore } from '../core/session.store';
import { TripRole } from './member.repo';

/**
 * Invitation links.
 *
 * Deliberately **not** cached offline, unlike the other repos. Two reasons and
 * both are about a link being a credential rather than content: the created token
 * would be written to IndexedDB on the device, which is the one place this
 * application keeps trip data and the one thing the server refuses to store in
 * the clear; and a cached list of links would go on showing an invitation as
 * outstanding after somebody revoked it, which is exactly backwards for a control
 * whose purpose is taking access away.
 *
 * The token from `create` is held in memory only, and only until the owner closes
 * the dialog — the server cannot reissue it.
 */
@Injectable({ providedIn: 'root' })
export class InviteRepo {
  private readonly api = inject(Api);
  private readonly connectivity = inject(Connectivity);
  private readonly session = inject(SessionStore);

  private readonly _invites = signal<TripInviteView[]>([]);
  private readonly _loading = signal(false);
  private readonly _saving = signal(false);
  /** The link just minted, readable once. Never persisted anywhere. */
  private readonly _created = signal<CreatedInviteView | null>(null);
  private _loadedTripId: number | null = null;

  readonly invites = this._invites.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly saving = this._saving.asReadonly();
  readonly created = this._created.asReadonly();

  constructor() {
    // Signing out has to drop the minted token as well as the list. This service
    // is application-scoped, so without it a link created before signing out is
    // still sitting in memory when the next person at the same browser opens a
    // People panel — and that link is a working key to somebody else's trip. The
    // cache clearing in SessionStore.logout does not cover this, because nothing
    // here is ever written to IndexedDB in the first place.
    let lastUserId: number | null = null;
    effect(() => {
      const userId = this.session.user()?.id ?? null;
      if (userId !== lastUserId) {
        lastUserId = userId;
        this.clear();
      }
    });
  }

  async load(tripId: number): Promise<void> {
    this.requireOnline();
    this._loading.set(true);
    try {
      this._invites.set(await this.api.invoke(listInvites, { tripId }));
      this._loadedTripId = tripId;
    } finally {
      this._loading.set(false);
    }
  }

  async refreshIfLoaded(tripId: number): Promise<void> {
    if (this._loadedTripId === tripId) {
      await this.load(tripId);
    }
  }

  /**
   * Mints a link and keeps it in memory for the owner to copy.
   *
   * The list is re-read afterwards like every other write here, so the new row
   * appears with the status the server gave it rather than one guessed locally.
   */
  async create(tripId: number, role: TripRole, expiresInDays?: number): Promise<CreatedInviteView> {
    this.requireOnline();
    this._saving.set(true);
    try {
      const created = await this.api.invoke(createInvite, {
        tripId,
        body: { role, expiresInDays },
      });
      this._created.set(created);
      await this.load(tripId);
      return created;
    } finally {
      this._saving.set(false);
    }
  }

  /** Forgets the one-time token. Called when the owner dismisses it. */
  clearCreated(): void {
    this._created.set(null);
  }

  async revoke(tripId: number, inviteId: number): Promise<void> {
    this.requireOnline();
    this._saving.set(true);
    try {
      await this.api.invoke(revokeInvite, { tripId, inviteId });
      await this.load(tripId);
    } finally {
      this._saving.set(false);
    }
  }

  /** What a link says before it is accepted. */
  preview(token: string): Promise<InvitePreview> {
    this.requireOnline();
    return this.api.invoke(previewInvite, { token });
  }

  /** Joins, and answers with the trip to go to. */
  async accept(token: string): Promise<number> {
    this.requireOnline();
    this._saving.set(true);
    try {
      return (await this.api.invoke(acceptInvite, { token })).tripId;
    } finally {
      this._saving.set(false);
    }
  }

  /** Forgets everything, for a sign-out or a trip that is no longer ours. */
  clear(): void {
    this._invites.set([]);
    this._created.set(null);
    this._loadedTripId = null;
  }

  private requireOnline(): void {
    if (!this.connectivity.online()) {
      throw new OfflineError();
    }
  }
}
