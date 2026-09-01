import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { messageOf } from '../../core/errors';
import { InstanceConfigStore } from '../../core/instance-config.store';
import { SessionStore } from '../../core/session.store';
import { TripRepo } from '../../repo/trip.repo';

@Component({
  selector: 'app-trips',
  imports: [FormsModule, RouterLink],
  templateUrl: './trips.html',
})
export class TripsPage {
  private readonly repo = inject(TripRepo);
  private readonly config = inject(InstanceConfigStore);
  private readonly session = inject(SessionStore);

  protected readonly trips = this.repo.trips;
  protected readonly loading = this.repo.loading;

  /**
   * The notice for the shared demo account, or null for everybody else.
   *
   * `DemoSweeper` deletes the trips this account creates, and it is the one
   * place in wander where somebody's work disappears without them asking. The
   * project refuses to show stale data without labelling it; deleting data
   * without saying so first is the same promise, so this says so on the page
   * where a trip is created.
   *
   * Both halves have to be present: `demoAccount` is who you are and comes from
   * the session, the interval is a setting and comes from the instance config.
   * Zero minutes — an ordinary instance, or a config that has not answered yet —
   * draws nothing rather than a sentence with a hole in it.
   */
  protected readonly demoNotice = computed(() => {
    const minutes = this.config.demoSweepMinutes();
    if (!this.session.user()?.demoAccount || minutes <= 0) {
      return null;
    }
    return minutes === 1 ? 'every minute' : `every ${minutes} minutes`;
  });

  /** Whether the create form is open. Closed once a trip lands. */
  protected readonly composing = signal(false);

  /**
   * How many skeleton cards to draw while loading. A field rather than an inline
   * array literal in the template: `@for` over a literal containing a comma
   * does not survive the block-syntax parser, and fails at runtime with
   * "newCollection[Symbol.iterator] is not a function".
   */
  protected readonly skeletons = [0, 1];

  protected readonly name = signal('');
  protected readonly destination = signal('');
  protected readonly startDate = signal('');
  protected readonly endDate = signal('');
  protected readonly error = signal<string | null>(null);

  /**
   * The trip's currency, and the one field here that cannot be changed later:
   * expense amounts are stored in it, so a change would not be a relabel.
   *
   * A short list rather than every ISO code — this is a select, not a search box
   * — with the instance's configured default preselected. The server accepts any
   * three-letter code, so an operator whose currency is missing here is not
   * locked out, they just cannot pick it from the menu.
   */
  protected readonly currencies = ['EUR', 'USD', 'GBP', 'CHF', 'SEK', 'NOK', 'DKK', 'PLN', 'CZK',
    'JPY', 'INR', 'AUD', 'CAD', 'NZD', 'SGD', 'ZAR', 'BRL', 'MXN'];
  protected readonly currency = signal('');

  constructor() {
    void this.repo.refresh();
    // The instance's default, once it has answered. Until then the select shows
    // the first option, and the server would apply the same default anyway.
    effect(() => {
      const preferred = this.config.defaultCurrency();
      if (preferred && !untracked(() => this.currency())) {
        this.currency.set(preferred);
      }
    });
  }

  protected async createTrip(): Promise<void> {
    this.error.set(null);
    try {
      await this.repo.create({
        name: this.name(),
        destination: this.destination() || undefined,
        startDate: this.startDate(),
        endDate: this.endDate(),
        currency: this.currency() || undefined,
      });
      this.name.set('');
      this.destination.set('');
      this.startDate.set('');
      this.endDate.set('');
      this.composing.set(false);
    } catch (err: unknown) {
      this.error.set(messageOf(err, 'Could not create the trip.'));
    }
  }

}
