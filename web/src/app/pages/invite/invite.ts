import { Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { InvitePreview } from '../../api';
import { messageOf } from '../../core/errors';
import { InviteRepo } from '../../repo/invite.repo';

/**
 * What somebody sees when they open an invitation link.
 *
 * This page is behind `authGuard` like everything else, which is the whole design
 * decision here. The alternative was an anonymous preview endpoint so a stranger
 * could read "Ana invited you to Lisbon" before signing up — and that would have
 * made a second public endpoint in an application whose security rests on there
 * being essentially none. `/api/config/sign-in` exists for one boolean and is
 * documented as the exception; a second one is how an exception becomes a habit.
 *
 * The cost is one extra step: link -> register -> back here. The guard carries
 * `returnUrl`, so the journey resumes on its own rather than depositing a new
 * account on the trip list wondering what happened to the invitation.
 */
@Component({
  selector: 'app-invite',
  imports: [RouterLink],
  templateUrl: './invite.html',
})
export class InvitePage {
  private readonly invites = inject(InviteRepo);
  private readonly router = inject(Router);

  /** From the route — withComponentInputBinding is on. */
  readonly token = input.required<string>();

  protected readonly preview = signal<InvitePreview | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly joining = signal(false);

  protected readonly roleWords = computed(() => {
    switch (this.preview()?.role) {
      case 'EDITOR':
        return 'You will be able to edit the itinerary.';
      case 'VIEWER':
        return 'You will be able to see the trip, but not change it.';
      default:
        return '';
    }
  });

  constructor() {
    // Not an effect on the input: this runs once, and re-running it on any
    // signal read would re-fetch an invitation that is single-use.
    queueMicrotask(() => void this.loadPreview());
  }

  private async loadPreview(): Promise<void> {
    this.loading.set(true);
    try {
      this.preview.set(await this.invites.preview(this.token()));
    } catch (err: unknown) {
      // A token that does not exist is a 404, and it is deliberately the same
      // answer as one that never did — so the message here cannot be more
      // specific than the server was willing to be.
      this.error.set(messageOf(err, 'This invitation link is not valid.'));
    } finally {
      this.loading.set(false);
    }
  }

  protected async join(): Promise<void> {
    this.joining.set(true);
    this.error.set(null);
    try {
      const tripId = await this.invites.accept(this.token());
      await this.router.navigate(['/trips', tripId]);
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'This invitation could not be used.'));
      // The link may have been revoked or spent while this page sat open, so
      // re-read rather than leaving a Join button that will fail again.
      await this.loadPreview();
    } finally {
      this.joining.set(false);
    }
  }

  protected async goToTrip(): Promise<void> {
    const tripId = this.preview()?.tripId;
    await this.router.navigate(tripId ? ['/trips', tripId] : ['/trips']);
  }
}
