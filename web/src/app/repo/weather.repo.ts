import { Injectable, computed, inject, signal } from '@angular/core';
import { Api, DayWeatherView, TripWeather, getTripWeather } from '../api';

/**
 * The forecast for a trip's days.
 *
 * **The one read that is not cached offline**, and deliberately. Everything else
 * in `repo/` goes through `OfflineCache` so a trip you have opened stays readable
 * without a connection; a forecast does not survive that treatment. It is a
 * prediction with a shelf life of hours, and a card showing Tuesday's guess about
 * Thursday with no way to say how old it is would be worse than an empty space.
 * So: no connection, no weather, and the itinerary is unaffected.
 *
 * Failure is silent for the same reason. The server already swallows an upstream
 * outage; this swallows the rest, because there is no version of "the forecast
 * service is unreachable" that belongs in front of somebody planning a trip.
 */
@Injectable({ providedIn: 'root' })
export class WeatherRepo {
  private readonly api = inject(Api);

  private readonly _byDate = signal<Map<string, DayWeatherView>>(new Map());
  private readonly _attribution = signal<TripWeather | null>(null);

  readonly byDate = this._byDate.asReadonly();
  /** Non-empty only when there is at least one day to credit. CC BY 4.0. */
  readonly attribution = computed(() => {
    const weather = this._attribution();
    return weather && weather.days.length ? weather : null;
  });

  async refresh(tripId: number): Promise<void> {
    try {
      const weather = await this.api.invoke(getTripWeather, { tripId });
      this._byDate.set(new Map(weather.days.map((day) => [day.date, day])));
      this._attribution.set(weather);
    } catch {
      // See the class comment: no forecast is a normal state of the world.
      this.clear();
    }
  }

  /** Called when the trip page leaves, so the next trip does not inherit this one's. */
  clear(): void {
    this._byDate.set(new Map());
    this._attribution.set(null);
  }
}
