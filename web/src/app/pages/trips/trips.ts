import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TripRepo } from '../../repo/trip.repo';

@Component({
  selector: 'app-trips',
  imports: [FormsModule],
  templateUrl: './trips.html',
})
export class TripsPage {
  private readonly repo = inject(TripRepo);

  protected readonly trips = this.repo.trips;
  protected readonly loading = this.repo.loading;

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

  constructor() {
    void this.repo.refresh();
  }

  protected async createTrip(): Promise<void> {
    this.error.set(null);
    try {
      await this.repo.create({
        name: this.name(),
        destination: this.destination() || undefined,
        startDate: this.startDate(),
        endDate: this.endDate(),
      });
      this.name.set('');
      this.destination.set('');
      this.startDate.set('');
      this.endDate.set('');
      this.composing.set(false);
    } catch (err: unknown) {
      const body = (err as { error?: { message?: string } } | null)?.error;
      this.error.set(body?.message ?? 'Could not create the trip.');
    }
  }

}
