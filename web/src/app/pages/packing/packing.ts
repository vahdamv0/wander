import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { PackingGroup, PackingItemView } from '../../api';
import { ageLabel, messageOf } from '../../core/errors';
import { SessionStore } from '../../core/session.store';
import { TripChange, TripSyncService } from '../../core/trip-sync';
import { PackingRepo } from '../../repo/packing.repo';

/** See TripPage: a pushed re-read has nobody watching it, so it retries itself. */
const REFRESH_ATTEMPTS = 4;
const REFRESH_BACKOFF_MS = 500;

/**
 * What to bring, in sections: the shared pile first, then one per person.
 *
 * The section is the assignment. Typing into somebody's box adds the item to
 * them, which is faster than picking a name from a select and is the reason the
 * add box is repeated per section rather than sitting once at the top.
 */
@Component({
  selector: 'app-packing',
  imports: [FormsModule, RouterLink],
  templateUrl: './packing.html',
})
export class PackingPage {
  private readonly repo = inject(PackingRepo);
  private readonly session = inject(SessionStore);
  private readonly sync = inject(TripSyncService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly tripId = input.required<string>();

  protected readonly trip = this.repo.trip;
  protected readonly groups = this.repo.groups;
  protected readonly totalCount = this.repo.totalCount;
  protected readonly packedCount = this.repo.packedCount;
  protected readonly loading = this.repo.loading;
  protected readonly saving = this.repo.saving;
  protected readonly canEdit = this.repo.canEdit;
  protected readonly syncStatus = this.sync.status;
  /** Set when this page is showing a copy from the device rather than the server. */
  protected readonly savedAt = this.repo.savedAt;

  protected readonly error = signal<string | null>(null);

  /** Which section's add box holds what. Keyed by user id, with 0 for the shared pile. */
  protected readonly drafts = signal<Record<number, string>>({});
  /** The item being renamed, by id. */
  protected readonly editing = signal<number | null>(null);
  protected readonly draftDescription = signal('');

  protected readonly allPacked = computed(
    () => this.totalCount() > 0 && this.packedCount() === this.totalCount(),
  );

  constructor() {
    queueMicrotask(() => void this.reload());
    queueMicrotask(() => this.sync.watch(this.id()));

    effect(() => {
      const change = this.sync.lastChange();
      if (change && change.tripId === this.id()) {
        untracked(() => void this.onRemoteChange(change));
      }
    });

    effect(() => {
      if (this.sync.reconnected() > 0) {
        untracked(() => void this.refreshFromServer());
      }
    });

    this.destroyRef.onDestroy(() => this.sync.stop());
  }

  private id(): number {
    return Number(this.tripId());
  }

  /** 0 stands in for the shared pile, which has no user id of its own. */
  protected keyOf(group: PackingGroup): number {
    return group.userId ?? 0;
  }

  protected labelOf(group: PackingGroup): string {
    if (group.userId === null || group.userId === undefined) {
      return 'Everyone';
    }
    return group.displayName ?? 'Somebody';
  }

  protected isMe(group: PackingGroup): boolean {
    return group.userId === this.session.user()?.id;
  }

  protected draftFor(group: PackingGroup): string {
    return this.drafts()[this.keyOf(group)] ?? '';
  }

  protected setDraft(group: PackingGroup, value: string): void {
    this.drafts.update((drafts) => ({ ...drafts, [this.keyOf(group)]: value }));
  }

  protected async add(group: PackingGroup): Promise<void> {
    const description = this.draftFor(group).trim();
    if (!description) {
      return;
    }
    await this.guard(async () => {
      await this.repo.add(this.id(), {
        description,
        // The section is the assignment; the shared pile sends null.
        assigneeUserId: group.userId ?? undefined,
      });
      this.setDraft(group, '');
    });
  }

  protected async toggle(item: PackingItemView): Promise<void> {
    // Optimistic in the repo, so nothing here waits before the box changes.
    await this.guard(() => this.repo.setPacked(this.id(), item.id, !item.packed));
  }

  protected openEdit(item: PackingItemView): void {
    this.error.set(null);
    this.draftDescription.set(item.description);
    this.editing.set(item.id);
  }

  protected cancelEdit(): void {
    this.editing.set(null);
  }

  protected async saveEdit(item: PackingItemView): Promise<void> {
    const description = this.draftDescription().trim();
    if (!description) {
      return;
    }
    await this.guard(async () => {
      await this.repo.update(this.id(), item.id, {
        description,
        assigneeUserId: item.assigneeUserId ?? undefined,
      });
      this.editing.set(null);
    });
  }

  /** Moves an item to another section, including back to the shared pile. */
  protected async reassign(item: PackingItemView, userId: string): Promise<void> {
    const assigneeUserId = userId === '' ? undefined : Number(userId);
    if ((item.assigneeUserId ?? undefined) === assigneeUserId) {
      return;
    }
    await this.guard(() =>
      this.repo.update(this.id(), item.id, { description: item.description, assigneeUserId }),
    );
  }

  protected async remove(item: PackingItemView): Promise<void> {
    await this.guard(() => this.repo.remove(this.id(), item.id));
  }

  private async onRemoteChange(change: TripChange): Promise<void> {
    const mine = change.actorUserId === this.session.user()?.id;
    if (mine && this.saving()) {
      return;
    }
    if (change.kind === 'TRIP_DELETED') {
      await this.router.navigate(['/trips']);
      return;
    }
    if (change.kind === 'MEMBERS' && change.revokedUserId === this.session.user()?.id) {
      await this.router.navigate(['/trips']);
      return;
    }
    // A member change moves the sections themselves, so both kinds land here.
    await this.refreshFromServer();
  }

  private async reload(): Promise<void> {
    try {
      await this.repo.load(this.id());
    } catch {
      this.error.set('Could not load this trip.');
    }
  }

  private refreshing = false;
  private refreshAgain = false;

  /** Same shape as TripPage.refreshFromServer, and for the same reason. */
  private async refreshFromServer(): Promise<void> {
    if (this.refreshing) {
      this.refreshAgain = true;
      return;
    }
    this.refreshing = true;
    try {
      do {
        this.refreshAgain = false;
        for (let attempt = 0; ; attempt++) {
          try {
            await this.repo.load(this.id());
            this.error.set(null);
            break;
          } catch {
            if (attempt >= REFRESH_ATTEMPTS - 1) {
              this.error.set('This list may be out of date — could not reach the server.');
              break;
            }
            await new Promise((resolve) => setTimeout(resolve, REFRESH_BACKOFF_MS * 2 ** attempt));
          }
        }
      } while (this.refreshAgain);
    } finally {
      this.refreshing = false;
    }
  }

  protected savedLabel(savedAt: number): string {
    return `Saved copy · ${ageLabel(savedAt)}`;
  }

  private async guard(action: () => Promise<void>): Promise<void> {
    this.error.set(null);
    try {
      await action();
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'That did not work. Try again.'));
    }
  }
}
