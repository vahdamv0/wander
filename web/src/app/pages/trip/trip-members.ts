import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TripMemberView } from '../../api';
import { messageOf } from '../../core/errors';
import { SessionStore } from '../../core/session.store';
import { InviteRepo } from '../../repo/invite.repo';
import { MemberRepo, TripRole } from '../../repo/member.repo';

/**
 * The people on a trip, and — for the owner — the controls to change who they
 * are.
 *
 * Collapsed by default, and it fetches nothing until it is opened: on most
 * trips the answer is "just me", and the itinerary is what the page is for. The
 * count therefore appears on the summary line only once the list has been
 * loaded — showing it sooner would mean a second request on every trip view.
 *
 * Membership is by email and the person must already have an account here, so
 * "not found" is a real and common answer — it is shown as the server's own
 * message rather than translated into something vaguer.
 */
@Component({
  selector: 'app-trip-members',
  imports: [FormsModule],
  templateUrl: './trip-members.html',
})
export class TripMembers {
  private readonly repo = inject(MemberRepo);
  private readonly invites = inject(InviteRepo);
  private readonly session = inject(SessionStore);

  readonly tripId = input.required<number>();
  /** The caller's own role on this trip, from the itinerary the page already has. */
  readonly myRole = input.required<TripRole | undefined>();

  /**
   * Raised when the caller's own rights changed — a transfer demotes them — so
   * the page can re-read the itinerary rather than keep offering edit controls
   * they no longer have.
   */
  readonly rolesChanged = output<void>();
  /** Raised after leaving: the trip is a 404 for us now, so the page navigates. */
  readonly left = output<void>();

  protected readonly members = this.repo.members;
  protected readonly loading = this.repo.loading;
  protected readonly saving = this.repo.saving;

  protected readonly open = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly isOwner = computed(() => this.myRole() === 'OWNER');
  protected readonly myUserId = computed(() => this.session.user()?.id ?? null);

  protected readonly draftEmail = signal('');
  protected readonly draftRole = signal<TripRole>('EDITOR');

  protected readonly inviteList = this.invites.invites;
  protected readonly createdInvite = this.invites.created;
  protected readonly inviteRole = signal<TripRole>('EDITOR');
  /** Set once the link has been copied, so the button can say so. */
  protected readonly copied = signal(false);

  /** The roles an owner may hand out directly. OWNER is a transfer, not an add. */
  protected readonly assignable: TripRole[] = ['EDITOR', 'VIEWER'];

  /**
   * What a member's role select offers. OWNER is in here because picking it is
   * the transfer — the only way ownership moves. A collection on the component
   * rather than a literal in the template: `@for` over an inline array does not
   * survive the block-syntax parser.
   */
  protected readonly rowRoles: TripRole[] = ['OWNER', 'EDITOR', 'VIEWER'];

  protected toggle(): void {
    const opening = !this.open();
    this.open.set(opening);
    if (opening) {
      void this.guard(async () => {
        await this.repo.load(this.tripId());
        // Only the owner may list invitations at all, so asking as anybody else
        // would be a guaranteed 403 painted over the panel.
        if (this.isOwner()) {
          await this.invites.load(this.tripId());
        }
      });
    }
  }

  /**
   * The full URL to send somebody.
   *
   * Built here rather than on the server, which knows only the path: behind the
   * reverse proxy it sees an internal hostname, so a link it composed would point
   * at something unreachable. The browser is the only party that knows the address
   * this instance was actually reached on.
   */
  protected inviteUrl(path: string): string {
    return new URL(path, window.location.origin).toString();
  }

  protected async createInvite(): Promise<void> {
    this.copied.set(false);
    await this.guard(async () => {
      await this.invites.create(this.tripId(), this.inviteRole());
    });
  }

  protected async copyInvite(path: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.inviteUrl(path));
      this.copied.set(true);
    } catch {
      // Clipboard access can be refused, and the link is on screen to be
      // selected by hand — so this is not worth an error banner.
      this.copied.set(false);
    }
  }

  protected dismissCreated(): void {
    this.invites.clearCreated();
    this.copied.set(false);
  }

  protected async revokeInvite(inviteId: number): Promise<void> {
    await this.guard(() => this.invites.revoke(this.tripId(), inviteId));
  }

  protected inviteStatusLabel(status: string): string {
    switch (status) {
      case 'PENDING':
        return 'Waiting';
      case 'ACCEPTED':
        return 'Used';
      case 'REVOKED':
        return 'Revoked';
      default:
        return 'Expired';
    }
  }

  protected isMe(member: TripMemberView): boolean {
    return member.userId === this.myUserId();
  }

  protected roleLabel(role: TripRole): string {
    return role === 'OWNER' ? 'Owner' : role === 'EDITOR' ? 'Editor' : 'Viewer';
  }

  protected async add(): Promise<void> {
    const email = this.draftEmail().trim();
    if (!email) {
      return;
    }
    await this.guard(async () => {
      await this.repo.add(this.tripId(), { email, role: this.draftRole() });
      this.draftEmail.set('');
    });
  }

  protected async changeRole(member: TripMemberView, role: string): Promise<void> {
    if (role === member.role) {
      return;
    }
    await this.guard(async () => {
      await this.repo.changeRole(this.tripId(), member.userId, role as TripRole);
      // A transfer changed our own role too, so the rest of the page is stale.
      this.rolesChanged.emit();
    });
  }

  protected async remove(member: TripMemberView): Promise<void> {
    await this.guard(() => this.repo.remove(this.tripId(), member.userId));
  }

  protected async leave(): Promise<void> {
    const me = this.myUserId();
    if (me === null) {
      return;
    }
    await this.guard(async () => {
      await this.repo.leave(this.tripId(), me);
      this.left.emit();
    });
  }

  /** Shows the server's own message: "Already a member", "No account with that email". */
  private async guard(action: () => Promise<void>): Promise<void> {
    this.error.set(null);
    try {
      await action();
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'That did not work. Try again.'));
    }
  }
}
