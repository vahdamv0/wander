import { Injectable, computed, inject, signal } from '@angular/core';
import {
  Api,
  ExpenseRequest,
  ExpenseView,
  TripExpenses,
  createExpense,
  deleteExpense,
  listExpenses,
  updateExpense,
} from '../api';

/**
 * One trip's ledger, as signals.
 *
 * Every write re-reads, like `PlaceRepo`'s non-drag writes and for a sharper
 * reason: a write changes the *balances*, which are arithmetic over every other
 * expense on the trip. Patching the local copy would mean reimplementing the
 * split and the settle-up reduction here, in a second language, with a second
 * chance to round a cent differently.
 */
@Injectable({ providedIn: 'root' })
export class ExpenseRepo {
  private readonly api = inject(Api);

  private readonly _ledger = signal<TripExpenses | null>(null);
  private readonly _loading = signal(false);
  private readonly _saving = signal(false);

  readonly ledger = this._ledger.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly saving = this._saving.asReadonly();

  readonly trip = computed(() => this._ledger()?.trip ?? null);
  readonly expenses = computed(() => this._ledger()?.expenses ?? []);
  readonly summary = computed(() => this._ledger()?.summary ?? null);
  /** The trip's currency, which every amount on the page is formatted in. */
  readonly currency = computed(() => this._ledger()?.trip.currency ?? 'EUR');
  /** Viewers read the ledger and get no controls, exactly as with the itinerary. */
  readonly canEdit = computed(() => {
    const role = this.trip()?.myRole;
    return role === 'OWNER' || role === 'EDITOR';
  });

  async load(tripId: number): Promise<void> {
    // Clear first when switching trips: one trip's expenses under another trip's
    // name is worse than a moment of nothing.
    if (this._ledger()?.trip.id !== tripId) {
      this._ledger.set(null);
    }
    this._loading.set(true);
    try {
      this._ledger.set(await this.api.invoke(listExpenses, { tripId }));
    } finally {
      this._loading.set(false);
    }
  }

  async add(tripId: number, body: ExpenseRequest): Promise<void> {
    await this.write(tripId, () => this.api.invoke(createExpense, { tripId, body }));
  }

  async update(tripId: number, expenseId: number, body: ExpenseRequest): Promise<void> {
    await this.write(tripId, () => this.api.invoke(updateExpense, { tripId, expenseId, body }));
  }

  async remove(tripId: number, expenseId: number): Promise<void> {
    await this.write(tripId, () => this.api.invoke(deleteExpense, { tripId, expenseId }));
  }

  /** The current shape of one expense, for seeding an edit form. */
  find(expenseId: number): ExpenseView | null {
    return this.expenses().find((expense) => expense.id === expenseId) ?? null;
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
