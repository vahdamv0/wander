import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ReservationView } from '../../api';
import { ageLabel, messageOf } from '../../core/errors';
import { SessionStore } from '../../core/session.store';
import { TripChange, TripSyncService } from '../../core/trip-sync';
import { allZones, browserZone, dayInZone, timeInZone, zoneAbbreviation } from '../../core/zones';
import { ReservationRepo } from '../../repo/reservation.repo';

const REFRESH_ATTEMPTS = 4;
const REFRESH_BACKOFF_MS = 500;

type Kind = ReservationView['kind'];

/**
 * The trip's bookings, soonest first.
 *
 * Times are shown in **the zone they happen in**, not the viewer's — a train at
 * 07:40 is at 07:40 wherever you are reading about it. The zone is only labelled
 * when it differs from the viewer's own, because on a trip at home it would be
 * noise on every row.
 */
@Component({
  selector: 'app-reservations',
  imports: [FormsModule, NgTemplateOutlet, RouterLink],
  templateUrl: './reservations.html',
})
export class ReservationsPage {
  private readonly repo = inject(ReservationRepo);
  private readonly session = inject(SessionStore);
  private readonly sync = inject(TripSyncService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly tripId = input.required<string>();

  protected readonly trip = this.repo.trip;
  protected readonly reservations = this.repo.reservations;
  protected readonly loading = this.repo.loading;
  protected readonly saving = this.repo.saving;
  protected readonly canEdit = this.repo.canEdit;
  protected readonly syncStatus = this.sync.status;
  /** Set when this page is showing a copy from the device rather than the server. */
  protected readonly savedAt = this.repo.savedAt;

  protected readonly error = signal<string | null>(null);
  protected readonly editing = signal<number | 'new' | null>(null);

  protected readonly kinds: Kind[] = ['FLIGHT', 'TRAIN', 'BUS', 'FERRY', 'CAR', 'HOTEL',
    'RESTAURANT', 'ACTIVITY', 'OTHER'];
  protected readonly zones = allZones();
  /** The viewer's own zone: the default for a new booking, and the one left unlabelled. */
  protected readonly myZone = browserZone();

  protected readonly draftKind = signal<Kind>('FLIGHT');
  protected readonly draftTitle = signal('');
  protected readonly draftConfirmation = signal('');
  protected readonly draftNotes = signal('');
  protected readonly draftStartDate = signal('');
  protected readonly draftStartTime = signal('');
  protected readonly draftStartZone = signal(this.myZone);
  protected readonly draftEndDate = signal('');
  protected readonly draftEndTime = signal('');
  protected readonly draftEndZone = signal(this.myZone);

  protected readonly canSubmit = computed(
    () =>
      !this.saving() &&
      !!this.draftTitle().trim() &&
      !!this.draftStartDate() &&
      !!this.draftStartTime() &&
      // An end is all-or-nothing: a date with no time is not a moment.
      (!this.draftEndDate() || !!this.draftEndTime()),
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

  protected kindLabel(kind: Kind): string {
    return kind.charAt(0) + kind.slice(1).toLowerCase();
  }

  protected day(booking: ReservationView): string {
    return dayInZone(booking.startsAt, booking.startZone);
  }

  protected time(booking: ReservationView): string {
    return timeInZone(booking.startsAt, booking.startZone);
  }

  protected endLabel(booking: ReservationView): string | null {
    if (!booking.endsAt || !booking.endZone) {
      return null;
    }
    const sameDay = dayInZone(booking.endsAt, booking.endZone) === this.day(booking);
    const clock = timeInZone(booking.endsAt, booking.endZone);
    const label = sameDay ? clock : `${dayInZone(booking.endsAt, booking.endZone)}, ${clock}`;
    // The arrival zone is worth showing whenever it differs from the departure's,
    // even if the viewer happens to be in it — that is the flight case.
    return booking.endZone === booking.startZone
      ? label
      : `${label} ${zoneAbbreviation(booking.endsAt, booking.endZone)}`;
  }

  /** Only worth saying when it is not where the reader is. */
  protected zoneLabel(booking: ReservationView): string | null {
    return booking.startZone === this.myZone
      ? null
      : zoneAbbreviation(booking.startsAt, booking.startZone);
  }

  protected openNew(): void {
    this.error.set(null);
    this.draftKind.set('FLIGHT');
    this.draftTitle.set('');
    this.draftConfirmation.set('');
    this.draftNotes.set('');
    this.draftStartDate.set(this.trip()?.startDate ?? '');
    this.draftStartTime.set('09:00');
    this.draftStartZone.set(this.myZone);
    this.draftEndDate.set('');
    this.draftEndTime.set('');
    this.draftEndZone.set(this.myZone);
    this.editing.set('new');
  }

  protected openEdit(booking: ReservationView): void {
    this.error.set(null);
    this.draftKind.set(booking.kind);
    this.draftTitle.set(booking.title);
    this.draftConfirmation.set(booking.confirmation ?? '');
    this.draftNotes.set(booking.notes ?? '');
    // The local wall time comes from the server precisely so this puts back what
    // was typed, rather than reconstructing it from an instant and a zone.
    const [startDate, startTime] = splitLocal(booking.startsAtLocal);
    this.draftStartDate.set(startDate);
    this.draftStartTime.set(startTime);
    this.draftStartZone.set(booking.startZone);
    const [endDate, endTime] = splitLocal(booking.endsAtLocal);
    this.draftEndDate.set(endDate);
    this.draftEndTime.set(endTime);
    this.draftEndZone.set(booking.endZone ?? booking.startZone);
    this.editing.set(booking.id);
  }

  protected cancel(): void {
    this.editing.set(null);
    this.error.set(null);
  }

  protected async save(): Promise<void> {
    const body = {
      kind: this.draftKind(),
      title: this.draftTitle().trim(),
      confirmation: this.draftConfirmation().trim() || undefined,
      notes: this.draftNotes().trim() || undefined,
      startsAtLocal: `${this.draftStartDate()}T${this.draftStartTime()}:00`,
      startZone: this.draftStartZone(),
      endsAtLocal: this.draftEndDate()
        ? `${this.draftEndDate()}T${this.draftEndTime()}:00`
        : undefined,
      endZone: this.draftEndDate() ? this.draftEndZone() : undefined,
    };

    await this.guard(async () => {
      const open = this.editing();
      if (open === 'new') {
        await this.repo.add(this.id(), body);
      } else if (typeof open === 'number') {
        await this.repo.update(this.id(), open, body);
      }
      this.editing.set(null);
    });
  }

  protected async remove(booking: ReservationView): Promise<void> {
    await this.guard(() => this.repo.remove(this.id(), booking.id));
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
              this.error.set('These bookings may be out of date — could not reach the server.');
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

/** "2027-07-12T09:15:00" as the two halves a date input and a time input want. */
function splitLocal(local: string | undefined): [string, string] {
  if (!local) {
    return ['', ''];
  }
  const [date, time] = local.split('T');
  return [date, (time ?? '').slice(0, 5)];
}
