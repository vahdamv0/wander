import {
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ExchangeRateView, ExpenseView, SettlementView } from '../../api';
import { formatMoney, parseMoney, toAmountInput } from '../../core/money';
import { ageLabel, messageOf } from '../../core/errors';
import { SessionStore } from '../../core/session.store';
import { TripChange, TripSyncService } from '../../core/trip-sync';
import { ExpenseRepo } from '../../repo/expense.repo';
import { FxRepo } from '../../repo/fx.repo';
import { MemberRepo } from '../../repo/member.repo';
import { InstanceConfigStore } from '../../core/instance-config.store';

/** See TripPage: a pushed re-read has nobody watching it, so it retries itself. */
const REFRESH_ATTEMPTS = 4;
const REFRESH_BACKOFF_MS = 500;

/**
 * How long the form waits before asking what a foreign amount converts to.
 *
 * Debounced in the component, next to the keystrokes it throttles, exactly as
 * the place search's is. The preview is a server call because the client does no
 * money arithmetic, so without this every digit of an amount would be a request.
 */
const QUOTE_DEBOUNCE_MS = 400;

/** One participant as the form deals with them: in or out, and for how much. */
interface Participant {
  userId: number;
  displayName: string;
  included: boolean;
  /** Only meaningful in EXACT mode; free text so a half-typed amount is not lost. */
  amount: string;
}

/**
 * The trip's ledger: what was spent, who paid, and who owes whom.
 *
 * Its own page rather than another panel on the trip page, which already carries
 * a map, a day list and a People panel.
 *
 * **No money arithmetic happens here.** Totals, balances and the settle-up
 * suggestions all arrive computed from the server, because there should be one
 * implementation of that and it should be the one with tests around it. What this
 * page does own is the conversion between minor units and what somebody types,
 * which lives in `core/money.ts`.
 */
@Component({
  selector: 'app-expenses',
  imports: [FormsModule, NgTemplateOutlet, RouterLink],
  templateUrl: './expenses.html',
})
export class ExpensesPage {
  private readonly repo = inject(ExpenseRepo);
  private readonly members = inject(MemberRepo);
  private readonly fx = inject(FxRepo);
  private readonly config = inject(InstanceConfigStore);
  private readonly session = inject(SessionStore);
  private readonly sync = inject(TripSyncService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly tripId = input.required<string>();

  protected readonly trip = this.repo.trip;
  /** Expenses and payments together — the ledger shows both, marked differently. */
  protected readonly entries = this.repo.entries;
  protected readonly summary = this.repo.summary;
  protected readonly loading = this.repo.loading;
  protected readonly saving = this.repo.saving;
  protected readonly canEdit = this.repo.canEdit;
  protected readonly currency = this.repo.currency;
  protected readonly syncStatus = this.sync.status;
  /** Set when this page is showing a copy from the device rather than the server. */
  protected readonly savedAt = this.repo.savedAt;

  protected readonly error = signal<string | null>(null);
  /** Open form: 'new', an expense id, or null. */
  protected readonly editing = signal<number | 'new' | null>(null);
  /** Whether the record-a-payment form is open. Separate from `editing`: they are different things. */
  protected readonly paying = signal(false);

  protected readonly payFrom = signal<number | null>(null);
  protected readonly payTo = signal<number | null>(null);
  protected readonly payAmount = signal('');
  protected readonly payDate = signal('');
  protected readonly payNote = signal('');

  protected readonly draftDescription = signal('');
  protected readonly draftAmount = signal('');
  protected readonly draftDate = signal('');
  protected readonly draftPaidBy = signal<number | null>(null);
  protected readonly draftMode = signal<'EQUAL' | 'EXACT'>('EQUAL');
  protected readonly draftParticipants = signal<Participant[]>([]);
  /**
   * What was actually paid in. Defaults to the trip's, which is the common case
   * and the one that costs nothing.
   */
  protected readonly draftCurrency = signal('');
  /** A rate somebody typed, as text. Empty means "look one up". */
  protected readonly draftRate = signal('');
  /** The server's answer for the current currency, date and amount. */
  protected readonly draftQuote = signal<ExchangeRateView | null>(null);
  /** True once a lookup has been tried and come back with nothing. */
  protected readonly quoteFailed = signal(false);

  protected readonly payCurrency = signal('');
  protected readonly payRate = signal('');
  protected readonly payQuote = signal<ExchangeRateView | null>(null);
  protected readonly payQuoteFailed = signal(false);

  protected readonly currencyOptions = this.fx.options;
  protected readonly commonCurrencies = computed(() => {
    const available = new Set(this.currencyOptions());
    const trip = this.currency();
    const pinned: string[] = [];
    for (const code of [trip, 'USD', 'EUR', 'INR']) {
      // The trip's own is offered whether or not a rate can be found for it —
      // it needs none, being the currency everything is already stored in.
      if (code && !pinned.includes(code) && (code === trip || available.has(code))) {
        pinned.push(code);
      }
    }
    return pinned;
  });

  protected readonly otherCurrencies = computed(() => {
    const pinned = new Set(this.commonCurrencies());
    return this.currencyOptions().filter((code) => !pinned.has(code));
  });
  protected readonly rateAttribution = this.config.rateAttribution;
  protected readonly rateAttributionUrl = this.config.rateAttributionUrl;

  /** Whether the expense form is dealing in something other than the trip's currency. */
  protected readonly draftIsForeign = computed(
    () => !!this.draftCurrency() && this.draftCurrency() !== this.currency(),
  );
  protected readonly payIsForeign = computed(
    () => !!this.payCurrency() && this.payCurrency() !== this.currency(),
  );

  /**
   * Whether the form has a rate to convert with — either one it fetched or one
   * somebody typed. A foreign expense with neither cannot be saved, and the
   * button says so rather than letting the server refuse it.
   */
  protected readonly draftHasRate = computed(
    () => !this.draftIsForeign() || !!this.draftRate().trim() || !!this.draftQuote(),
  );
  protected readonly payHasRate = computed(
    () => !this.payIsForeign() || !!this.payRate().trim() || !!this.payQuote(),
  );

  /** People who may be in a split: the current members. */
  protected readonly candidates = computed(() => this.members.members());

  protected readonly includedCount = computed(
    () => this.draftParticipants().filter((participant) => participant.included).length,
  );

  /**
   * What the typed amount comes to, or null if it is not a usable number. Also
   * the guard on the submit button — an unparseable amount cannot be sent.
   */
  protected readonly draftAmountMinor = computed(() =>
    // Parsed in the currency it was typed in: the yen has no decimal places, so
    // reading "8000" against the trip's euros would make it ¥80.
    parseMoney(this.draftAmount(), this.entryCurrency()),
  );

  /** The currency the amount and the shares are being typed in. */
  protected readonly entryCurrency = computed(() => this.draftCurrency() || this.currency());
  protected readonly payEntryCurrency = computed(() => this.payCurrency() || this.currency());

  /**
   * For an exact split: the total still unallocated. Shown while typing, because
   * the server will refuse a split that does not add up and it is unkind to
   * discover that on submit.
   */
  protected readonly remainingMinor = computed(() => {
    const total = this.draftAmountMinor();
    if (total === null || this.draftMode() !== 'EXACT') {
      return null;
    }
    let allocated = 0;
    for (const participant of this.draftParticipants()) {
      if (!participant.included) {
        continue;
      }
      const amount = parseMoney(participant.amount || '0', this.entryCurrency());
      if (amount === null) {
        return null;
      }
      allocated += amount;
    }
    return total - allocated;
  });

  protected readonly payAmountMinor = computed(() =>
    parseMoney(this.payAmount(), this.payEntryCurrency()),
  );

  protected readonly canRecordPayment = computed(() => {
    const amount = this.payAmountMinor();
    return (
      !this.saving() &&
      amount !== null &&
      amount >= 1 &&
      this.payFrom() !== null &&
      this.payTo() !== null &&
      // Paying yourself is not settling up, and the server refuses it anyway.
      this.payFrom() !== this.payTo() &&
      !!this.payDate() &&
      // A foreign payment with no rate cannot be converted, and the server would
      // refuse it — better to say so on the button than after the click.
      this.payHasRate()
    );
  });

  protected readonly canSubmit = computed(() => {
    if (this.saving() || !this.draftDescription().trim() || !this.draftDate()) {
      return false;
    }
    const amount = this.draftAmountMinor();
    if (amount === null || amount < 1 || this.draftPaidBy() === null || !this.includedCount()) {
      return false;
    }
    if (!this.draftHasRate()) {
      return false;
    }
    return this.draftMode() === 'EQUAL' || this.remainingMinor() === 0;
  });

  constructor() {
    queueMicrotask(() => void this.reload());
    queueMicrotask(() => this.sync.watch(this.id()));
    queueMicrotask(() => void this.fx.load());

    // The expense form's preview. Reacts to the four things that change what a
    // rate answer would be — and to nothing else, so retyping a description does
    // not ask again.
    effect(() => {
      const currency = this.draftCurrency();
      const on = this.draftDate();
      const amount = this.draftAmountMinor();
      const typed = this.draftRate().trim();
      untracked(() => this.scheduleQuote('draft', currency, on, amount, typed));
    });

    effect(() => {
      const currency = this.payCurrency();
      const on = this.payDate();
      const amount = this.payAmountMinor();
      const typed = this.payRate().trim();
      untracked(() => this.scheduleQuote('pay', currency, on, amount, typed));
    });

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

  private quoteTimers: Record<'draft' | 'pay', ReturnType<typeof setTimeout> | null> = {
    draft: null,
    pay: null,
  };

  /**
   * Asks the server what an amount converts to, once the typing settles.
   *
   * A **server** call rather than a multiplication here, which is the rule the
   * whole money feature turns on: a preview computed in TypeScript would be a
   * second implementation of the conversion, and it would announce itself by
   * disagreeing with the saved figure by a cent.
   *
   * A rate somebody typed short-circuits it entirely — there is nothing to look
   * up, and asking anyway would spend this instance's quota to answer a question
   * that has already been answered.
   */
  private scheduleQuote(
    which: 'draft' | 'pay',
    currency: string,
    on: string,
    amountMinor: number | null,
    typedRate: string,
  ): void {
    const quote = which === 'draft' ? this.draftQuote : this.payQuote;
    const failed = which === 'draft' ? this.quoteFailed : this.payQuoteFailed;
    const existing = this.quoteTimers[which];
    if (existing) {
      clearTimeout(existing);
      this.quoteTimers[which] = null;
    }

    const trip = this.currency();
    if (!currency || currency === trip || !on || typedRate) {
      quote.set(null);
      failed.set(false);
      return;
    }

    this.quoteTimers[which] = setTimeout(() => {
      void this.fx
        .quote(currency, trip, on, amountMinor ?? undefined)
        .then((answer) => {
          quote.set(answer);
          // Distinguished from "not asked yet", because the two want different
          // things on screen: a spinner, or a box to type the rate into.
          failed.set(answer === null);
        });
    }, QUOTE_DEBOUNCE_MS);
  }

  protected money(minorUnits: number): string {
    return formatMoney(minorUnits, this.currency());
  }

  /** An amount in whatever it was actually paid in. */
  protected sourceMoney(minorUnits: number, currency: string): string {
    return formatMoney(minorUnits, currency);
  }

  /**
   * How an expense's conversion reads on the row: what the rate was and where it
   * came from.
   *
   * A typed rate says so instead of naming a date, because the two are different
   * claims — a looked-up rate is a market reference for a day, and a typed one is
   * what somebody's card charged.
   */
  protected rateLabel(expense: ExpenseView): string {
    if (!expense.sourceCurrency || !expense.fxRate) {
      return '';
    }
    const rate = `1 ${expense.sourceCurrency} = ${expense.fxRate} ${this.currency()}`;
    return expense.fxManual
      ? `${rate} · rate you entered`
      : `${rate} · ${this.dateLabel(expense.fxQuotedOn ?? expense.spentOn)}`;
  }

  /**
   * Same shape as the trip page's day labels, plus the year — an expense can be
   * months outside the trip, so "10 Jun" alone would be ambiguous. The
   * `T00:00:00` matters: a bare date string is parsed as UTC and then shown in
   * local time, which moves it a day west of Greenwich.
   */
  protected dateLabel(date: string): string {
    return new Date(date + 'T00:00:00').toLocaleDateString(undefined, {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    });
  }

  /** Balances are signed; the label says which way round it is. */
  protected owedLabel(netMinor: number): string {
    if (netMinor === 0) {
      return 'settled up';
    }
    return netMinor > 0
      ? `is owed ${this.money(netMinor)}`
      : `owes ${this.money(-netMinor)}`;
  }

  protected isMe(userId: number): boolean {
    return userId === this.session.user()?.id;
  }

  protected openNew(): void {
    this.error.set(null);
    this.confirmingRemoval.set(null);
    this.paying.set(false);
    this.draftDescription.set('');
    this.draftAmount.set('');
    // Today, which is what somebody entering an expense as it happens wants —
    // and a date they can change for the flight they booked in March.
    this.draftDate.set(new Date().toISOString().slice(0, 10));
    this.draftPaidBy.set(this.session.user()?.id ?? null);
    this.draftMode.set('EQUAL');
    this.draftCurrency.set(this.currency());
    this.draftRate.set('');
    this.draftQuote.set(null);
    this.quoteFailed.set(false);
    this.draftParticipants.set(
      this.candidates().map((member) => ({
        userId: member.userId,
        displayName: member.displayName,
        // Everybody in by default: the common expense is a shared one.
        included: true,
        amount: '',
      })),
    );
    this.editing.set('new');
  }

  /**
   * Opens the payment form, optionally filled in from a suggested transfer.
   *
   * Prefilling from a suggestion is the path that matters: the summary already
   * says "Bob pays Alice €40", and making somebody retype that is how a ledger
   * stops being kept up to date.
   */
  protected startPayment(suggestion?: SettlementView): void {
    this.error.set(null);
    this.confirmingRemoval.set(null);
    this.editing.set(null);
    this.payFrom.set(suggestion?.fromUserId ?? this.session.user()?.id ?? null);
    this.payTo.set(suggestion?.toUserId ?? null);
    // A suggestion is already in the trip's currency — it is a balance, and
    // balances are only ever kept in one.
    this.payAmount.set(
      suggestion ? toAmountInput(suggestion.amountMinor, this.currency()) : '',
    );
    this.payDate.set(new Date().toISOString().slice(0, 10));
    this.payNote.set('');
    this.payCurrency.set(this.currency());
    this.payRate.set('');
    this.payQuote.set(null);
    this.payQuoteFailed.set(false);
    this.paying.set(true);
  }

  protected cancelPayment(): void {
    this.paying.set(false);
    this.error.set(null);
  }

  protected async savePayment(): Promise<void> {
    const amountMinor = this.payAmountMinor();
    const fromUserId = this.payFrom();
    const toUserId = this.payTo();
    if (amountMinor === null || fromUserId === null || toUserId === null) {
      return;
    }
    await this.guard(async () => {
      await this.repo.pay(this.id(), {
        fromUserId,
        toUserId,
        amountMinor,
        currency: this.payIsForeign() ? this.payEntryCurrency() : undefined,
        fxRate: this.payIsForeign() ? this.payRate().trim() || undefined : undefined,
        paidOn: this.payDate(),
        note: this.payNote().trim() || undefined,
      });
      this.paying.set(false);
    });
  }

  /** Who received the money in a payment — its single share. */
  protected recipientOf(payment: ExpenseView): string {
    return payment.shares[0]?.displayName ?? '';
  }

  protected openEdit(expense: ExpenseView): void {
    this.error.set(null);
    this.confirmingRemoval.set(null);
    this.draftDescription.set(expense.description);
    // Reopened in the currency it was entered in, showing what was really paid.
    // Putting the converted figure back in the box would rewrite the expense as
    // a euro one the moment somebody saved a spelling correction.
    const currency = expense.sourceCurrency ?? this.currency();
    this.draftCurrency.set(currency);
    this.draftRate.set('');
    this.draftQuote.set(null);
    this.quoteFailed.set(false);
    this.draftAmount.set(
      toAmountInput(expense.sourceAmountMinor ?? expense.amountMinor, currency),
    );
    this.draftDate.set(expense.spentOn);
    this.draftPaidBy.set(expense.paidByUserId);
    // Reopened in the mode it was saved in, which is why the server stores it.
    this.draftMode.set(expense.splitMode);

    // The stored shares are in the trip's currency; the form types in the
    // expense's. For an unconverted expense those are the same thing, and for a
    // converted one the exact amounts are re-derived from what is on screen —
    // which is why reopening a converted EXACT split starts from the shares as
    // stored and lets the person restate them.
    const shares = new Map(expense.shares.map((share) => [share.userId, share.amountMinor]));
    // Anybody in the split stays in it even if they have since left the trip;
    // dropping them silently would change the numbers behind somebody's back.
    const people: Participant[] = this.candidates().map((member) => ({
      userId: member.userId,
      displayName: member.displayName,
      included: shares.has(member.userId),
      amount: shares.has(member.userId)
        ? toAmountInput(shares.get(member.userId)!, this.currency())
        : '',

    }));
    for (const share of expense.shares) {
      if (!people.some((person) => person.userId === share.userId)) {
        people.push({
          userId: share.userId,
          displayName: share.displayName,
          included: true,
          amount: toAmountInput(share.amountMinor, this.currency()),
        });
      }
    }
    this.draftParticipants.set(people);
    this.editing.set(expense.id);
  }

  protected cancel(): void {
    this.editing.set(null);
    this.error.set(null);
  }

  protected toggleParticipant(userId: number): void {
    this.draftParticipants.update((people) =>
      people.map((person) =>
        person.userId === userId ? { ...person, included: !person.included } : person,
      ),
    );
  }

  protected setParticipantAmount(userId: number, amount: string): void {
    this.draftParticipants.update((people) =>
      people.map((person) => (person.userId === userId ? { ...person, amount } : person)),
    );
  }

  /** Spreads the amount evenly into the exact-mode boxes, as a starting point. */
  protected prefillEqually(): void {
    const total = this.draftAmountMinor();
    const included = this.draftParticipants().filter((person) => person.included);
    if (total === null || !included.length) {
      return;
    }
    // The same rule the server uses, so the boxes start out adding up: the
    // remainder goes a minor unit at a time to the lowest user ids.
    const ordered = [...included].sort((a, b) => a.userId - b.userId);
    const base = Math.floor(total / ordered.length);
    const remainder = total % ordered.length;
    const amounts = new Map<number, number>();
    ordered.forEach((person, index) => {
      amounts.set(person.userId, index < remainder ? base + 1 : base);
    });
    this.draftParticipants.update((people) =>
      people.map((person) =>
        amounts.has(person.userId)
          ? { ...person, amount: toAmountInput(amounts.get(person.userId)!, this.entryCurrency()) }
          : person,
      ),
    );
  }

  protected async save(): Promise<void> {
    const amountMinor = this.draftAmountMinor();
    const paidByUserId = this.draftPaidBy();
    if (amountMinor === null || paidByUserId === null) {
      return;
    }

    const shares = this.draftParticipants()
      .filter((person) => person.included)
      .map((person) => ({
        userId: person.userId,
        amountMinor:
          this.draftMode() === 'EXACT'
            ? (parseMoney(person.amount || '0', this.entryCurrency()) ?? 0)
            : undefined,
      }));

    const body = {
      description: this.draftDescription().trim(),
      amountMinor,
      // Sent only when it is not the trip's, so an ordinary expense's request is
      // exactly the request it was before this feature existed.
      currency: this.draftIsForeign() ? this.entryCurrency() : undefined,
      fxRate: this.draftIsForeign() ? this.draftRate().trim() || undefined : undefined,
      spentOn: this.draftDate(),
      paidByUserId,
      splitMode: this.draftMode(),
      shares,
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

  /**
   * Which entry's × has been pressed once and is waiting to be meant.
   *
   * Removing one is irreversible and it is not a private act: a deleted expense
   * moves everybody's balance on the trip, and live sync puts that on their
   * screens within the second. The × also sits beside Edit, so the miss costs
   * a ledger entry nobody can get back. One id rather than a set, as the place
   * row does it: asking about two at once is not a state worth having.
   */
  protected readonly confirmingRemoval = signal<number | null>(null);

  protected askRemove(expense: ExpenseView): void {
    this.confirmingRemoval.set(expense.id);
  }

  protected cancelRemove(): void {
    this.confirmingRemoval.set(null);
  }

  protected async remove(expense: ExpenseView): Promise<void> {
    this.confirmingRemoval.set(null);
    await this.guard(() => this.repo.remove(this.id(), expense.id));
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
    // A member change moves who may be in a split, and a role change moves
    // whether we may write at all — so both kinds land here.
    await this.refreshFromServer();
  }

  private async reload(): Promise<void> {
    try {
      await Promise.all([this.repo.load(this.id()), this.members.load(this.id())]);
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
            await this.members.load(this.id());
            await this.repo.load(this.id());
            this.error.set(null);
            break;
          } catch {
            if (attempt >= REFRESH_ATTEMPTS - 1) {
              this.error.set('These figures may be out of date — could not reach the server.');
              break;
            }
            await new Promise((resolve) =>
              setTimeout(resolve, REFRESH_BACKOFF_MS * 2 ** attempt),
            );
          }
        }
      } while (this.refreshAgain);
    } finally {
      this.refreshing = false;
    }
  }

  /** Shows the server's own message: it is the authority on a split that does not add up. */
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
