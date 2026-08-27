import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { SessionStore } from '../../core/session.store';
import { TripRepo } from '../../repo/trip.repo';

@Component({
  selector: 'app-trips',
  imports: [FormsModule],
  templateUrl: './trips.html',
})
export class TripsPage {
  private readonly repo = inject(TripRepo);
  private readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  protected readonly trips = this.repo.trips;
  protected readonly loading = this.repo.loading;
  protected readonly user = this.session.user;

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
    } catch (err: unknown) {
      const body = (err as { error?: { message?: string } } | null)?.error;
      this.error.set(body?.message ?? 'Could not create the trip.');
    }
  }

  protected async signOut(): Promise<void> {
    await this.session.logout();
    await this.router.navigate(['/login']);
  }
}
