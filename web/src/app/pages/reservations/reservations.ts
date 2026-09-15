import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ReservationDraft, ReservationView } from '../../api';
import { ageLabel, messageOf } from '../../core/errors';
import { InstanceConfigStore } from '../../core/instance-config.store';
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
  private readonly config = inject(InstanceConfigStore);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly tripId = input.required<string>();

  protected readonly trip = this.repo.trip;
  protected readonly reservations = this.repo.reservations;
  protected readonly loading = this.repo.loading;
  protected readonly saving = this.repo.saving;
  protected readonly importing = this.repo.importing;
  protected readonly canEdit = this.repo.canEdit;
  protected readonly syncStatus = this.sync.status;
  /** Set when this page is showing a copy from the device rather than the server. */
  protected readonly savedAt = this.repo.savedAt;

  protected readonly error = signal<string | null>(null);
  protected readonly editing = signal<number | 'new' | null>(null);

  protected readonly kinds: Kind[] = ['FLIGHT', 'TRAIN', 'BUS', 'FERRY', 'CAR', 'HOTEL',
    'RESTAURANT', 'ACTIVITY', 'OTHER'];
  /** The viewer's own zone: the default for a new booking, and the one left unlabelled. */
  protected readonly myZone = browserZone();

  /**
   * Zones an import supplied that this browser does not list.
   *
   * The server sends an IANA id wherever the file named one, but a document that
   * gave only an offset comes back as "+09:00" — true, displayable, and not in
   * `Intl.supportedValuesOf`. Without adding it the select would silently show
   * something else while the model held the offset, which is the quiet way to
   * move a booking by nine hours.
   */
  private readonly importedZones = signal<string[]>([]);

  protected readonly zones = computed(() => {
    const known = allZones();
    const extra = this.importedZones().filter((zone) => !known.includes(zone));
    return extra.length ? [...extra, ...known] : known;
  });

  protected readonly draftKind = signal<Kind>('FLIGHT');
  protected readonly draftTitle = signal('');
  protected readonly draftConfirmation = signal('');
  protected readonly draftPhone = signal('');
  protected readonly draftNotes = signal('');
  protected readonly draftStartDate = signal('');
  protected readonly draftStartTime = signal('');
  protected readonly draftStartZone = signal(this.myZone);
  protected readonly draftEndDate = signal('');
  protected readonly draftEndTime = signal('');
  protected readonly draftEndZone = signal(this.myZone);

  /**
   * Drafts read out of a file and not yet saved.
   *
   * They live here rather than being written anywhere, which is the whole design
   * of import: the server parses and answers with candidates, and a booking only
   * exists once the ordinary form saves one. A confirmation with an outbound and
   * a return leg produces two, and both stay on offer until each has been used —
   * losing the return leg would be the first thing anybody noticed.
   */
  protected readonly importedDrafts = signal<ReservationDraft[]>([]);
  /** Which draft the open form was prefilled from, so a save can retire just that one. */
  private readonly activeDraft = signal<ReservationDraft | null>(null);
  protected readonly importedFrom = signal('');
  /** The server's own words when a file yielded nothing. */
  protected readonly importMessage = signal('');
  /** What the file did not say, for the form to admit rather than paper over. */
  protected readonly prefillNote = signal('');

  protected readonly canImport = computed(
    () => this.canEdit() && this.config.bookingImportEnabled(),
  );
  protected readonly importAccept = this.config.bookingImportAccept;

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
    this.confirmingRemoval.set(null);
    this.draftKind.set('FLIGHT');
    this.draftTitle.set('');
    this.draftConfirmation.set('');
    this.draftPhone.set('');
    this.draftNotes.set('');
    this.draftStartDate.set(this.trip()?.startDate ?? '');
    this.draftStartTime.set('09:00');
    this.draftStartZone.set(this.myZone);
    this.draftEndDate.set('');
    this.draftEndTime.set('');
    this.draftEndZone.set(this.myZone);
    // A blank form is not prefilled from anything, so neither the note nor the
    // link back to a draft applies — but drafts still on offer are left alone.
    this.prefillNote.set('');
    this.activeDraft.set(null);
    this.editing.set('new');
  }

  /**
   * The number as something a dialler will accept.
   *
   * The stored string is whatever somebody typed — "+81 3-4333-1234 (front
   * desk)" is a realistic and useful thing to have written down — and RFC 3966
   * wants none of that. So the displayed text stays as typed and only the href
   * is reduced: everything from the first bracket is dropped, because it is a
   * remark rather than digits, and what is left keeps a leading plus and its
   * numbers. Nothing is inferred — no country code is added to a bare local
   * number, because guessing which country somebody meant is exactly the way to
   * dial a stranger at 1am.
   */
  protected telHref(phone: string): string {
    const dialable = phone.split('(')[0].replace(/[^\d+]/g, '');
    return 'tel:' + dialable;
  }

  /**
   * Reads the chosen file and offers what came back.
   *
   * One draft goes straight into the form — an extra click to confirm something
   * the page is about to show anyway is a click for nothing. Several are listed,
   * because picking which leg to enter first is a real choice and guessing it
   * would put the return flight in the form.
   *
   * The input is cleared either way, or choosing the same file twice fires no
   * `change` event and the page looks broken.
   */
  protected async onFileChosen(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }

    this.error.set(null);
    this.importMessage.set('');
    this.importedDrafts.set([]);
    this.prefillNote.set('');
    this.importedFrom.set(file.name);

    try {
      const result = await this.repo.importFile(this.id(), file);
      this.importedZones.update((known) => [...known, ...zonesIn(result.drafts)]);
      if (result.drafts.length === 1) {
        this.useDraft(result.drafts[0]);
      } else {
        this.importedDrafts.set(result.drafts);
        this.importMessage.set(result.message);
      }
    } catch (err: unknown) {
      this.importedFrom.set('');
      this.error.set(messageOf(err, 'That file could not be read.'));
    }
  }

  /**
   * Puts a draft into the form.
   *
   * It fills the same signals `openNew` and `openEdit` fill, so there is one
   * editor and one save path — the import is a prefill, not a second way to
   * create a booking. Whatever the file did not say is left blank rather than
   * invented, and `prefillNote` says which parts those were: a form that quietly
   * guessed a departure time would be believed.
   */
  protected useDraft(draft: ReservationDraft): void {
    this.error.set(null);
    this.draftKind.set(draft.kind);
    this.draftTitle.set(draft.title);
    this.draftConfirmation.set(draft.confirmation ?? '');
    this.draftPhone.set(draft.phone ?? '');
    this.draftNotes.set(draft.notes ?? '');
    this.draftStartDate.set(draft.startDate ?? '');
    this.draftStartTime.set(clockOf(draft.startTime));
    this.draftStartZone.set(draft.startZone ?? this.myZone);
    this.draftEndDate.set(draft.endDate ?? '');
    this.draftEndTime.set(clockOf(draft.endTime));
    this.draftEndZone.set(draft.endZone ?? draft.startZone ?? this.myZone);
    this.importedZones.update((known) => [...known, ...zonesIn([draft])]);
    this.prefillNote.set(noteFor(draft));
    this.activeDraft.set(draft);
    this.editing.set('new');
  }

  /** Stops offering what is left, without touching anything already saved. */
  protected dismissImport(): void {
    this.importedDrafts.set([]);
    this.importMessage.set('');
    this.importedFrom.set('');
    this.prefillNote.set('');
    this.activeDraft.set(null);
  }

  protected openEdit(booking: ReservationView): void {
    this.error.set(null);
    this.confirmingRemoval.set(null);
    this.draftKind.set(booking.kind);
    this.draftTitle.set(booking.title);
    this.draftConfirmation.set(booking.confirmation ?? '');
    this.draftPhone.set(booking.phone ?? '');
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
    this.prefillNote.set('');
    // Deliberately not clearing importedDrafts: abandoning one leg must not throw
    // the other away, and the file is not still open on the user's screen.
    this.activeDraft.set(null);
  }

  protected async save(): Promise<void> {
    const body = {
      kind: this.draftKind(),
      title: this.draftTitle().trim(),
      confirmation: this.draftConfirmation().trim() || undefined,
      phone: this.draftPhone().trim() || undefined,
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
      // Saved, so this draft is spent — the rest of the confirmation stays on
      // offer, which is what makes a two-leg import two saves rather than one
      // and a retype.
      const used = this.activeDraft();
      if (used) {
        this.importedDrafts.update((drafts) => drafts.filter((draft) => draft !== used));
        this.activeDraft.set(null);
        this.prefillNote.set('');
      }
      this.editing.set(null);
    });
  }

  /**
   * Which booking's × has been pressed once and is waiting to be meant.
   *
   * The same argument as a place row and an expense: the × sits beside Edit,
   * removing takes the notes and the confirmation reference with it, there is
   * no undo, and live sync puts it on everybody else's screen within the
   * second. One id rather than a set — asking about two at once is not a state
   * worth having.
   */
  protected readonly confirmingRemoval = signal<number | null>(null);

  protected askRemove(booking: ReservationView): void {
    this.confirmingRemoval.set(booking.id);
  }

  protected cancelRemove(): void {
    this.confirmingRemoval.set(null);
  }

  protected async remove(booking: ReservationView): Promise<void> {
    this.confirmingRemoval.set(null);
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

/**
 * "13:05:00" as the "13:05" a time input wants.
 *
 * The server sends a `LocalTime`, which Jackson writes with seconds. A booking
 * has none worth keeping, and the extra pair would be carried back into
 * `startsAtLocal` on save for no reason.
 */
function clockOf(time: string | undefined): string {
  return (time ?? '').slice(0, 5);
}

/** Every zone a set of drafts mentions, so the picker can offer them. */
function zonesIn(drafts: ReservationDraft[]): string[] {
  return drafts.flatMap((draft) => [draft.startZone, draft.endZone])
    .filter((zone): zone is string => !!zone);
}

/**
 * What the file did not say.
 *
 * Worth a sentence because the gaps are the part a reader would otherwise not
 * notice until the form refused to submit — and because a zone defaulted to the
 * reader's own is a guess that looks exactly like a fact. A calendar event is
 * called out on its own: it states when something happens and nothing else, so
 * its kind is always OTHER and its reference is sitting in the notes rather than
 * in the field for it.
 */
function noteFor(draft: ReservationDraft): string {
  const missing: string[] = [];
  if (!draft.startTime) {
    missing.push('no start time');
  }
  // An all-day calendar entry gives a date at each end and a clock at neither,
  // so the end has to be named separately — the form will not submit with a date
  // and no time, and being told only about the start would leave the reader
  // hunting for why.
  if (draft.endDate && !draft.endTime) {
    missing.push('no end time');
  }
  if (!draft.startZone) {
    missing.push('no time zone, so your own is filled in');
  }
  const parts: string[] = [];
  if (draft.source === 'CALENDAR') {
    parts.push(
      'This came from a calendar event, which says when but not what kind of ' +
        'booking it is — set the type, and check the notes for a reference number.',
    );
  }
  if (missing.length) {
    parts.push('The file gave ' + missing.join(', ') + '.');
  }
  return parts.length ? parts.join(' ') + ' Check it before saving.' : '';
}

/** "2027-07-12T09:15:00" as the two halves a date input and a time input want. */
function splitLocal(local: string | undefined): [string, string] {
  if (!local) {
    return ['', ''];
  }
  const [date, time] = local.split('T');
  return [date, (time ?? '').slice(0, 5)];
}
