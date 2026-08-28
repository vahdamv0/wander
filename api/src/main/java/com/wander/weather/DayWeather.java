package com.wander.weather;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import com.wander.trip.Trip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * The stored forecast for one day of one trip.
 *
 * Two things make a row stale and both are checked: age, because a forecast
 * changes through the day, and the point it was fetched for, because the day's
 * first place can move. A row that only knew its age would keep showing Osaka's
 * weather for a day now spent in Kanazawa.
 */
@Entity
@Table(name = "day_weather")
public class DayWeather {

    /**
     * How close two points have to be to count as the same forecast. Roughly a
     * kilometre — nudging a pin across a city does not change the weather, and
     * treating every metre as a new location would refetch on every edit.
     */
    private static final double SAME_PLACE_DEGREES = 0.01;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "day_date", nullable = false)
    private LocalDate dayDate;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(name = "temp_max_c", nullable = false)
    private double tempMaxC;

    @Column(name = "temp_min_c", nullable = false)
    private double tempMinC;

    @Column(name = "weather_code", nullable = false)
    private int weatherCode;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    protected DayWeather() {
        // JPA
    }

    public DayWeather(Trip trip, LocalDate dayDate) {
        this.trip = trip;
        this.dayDate = dayDate;
    }

    public void replaceWith(double latitude, double longitude,
            WeatherClient.DailyForecast forecast) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.tempMaxC = forecast.tempMaxC();
        this.tempMinC = forecast.tempMinC();
        this.weatherCode = forecast.weatherCode();
        this.fetchedAt = Instant.now();
    }

    /** True when this row can still be shown as it is. */
    public boolean isFreshFor(double latitude, double longitude, Duration ttl) {
        return Math.abs(this.latitude - latitude) < SAME_PLACE_DEGREES
                && Math.abs(this.longitude - longitude) < SAME_PLACE_DEGREES
                && fetchedAt.isAfter(Instant.now().minus(ttl));
    }

    public LocalDate getDayDate() {
        return dayDate;
    }

    public double getTempMaxC() {
        return tempMaxC;
    }

    public double getTempMinC() {
        return tempMinC;
    }

    public int getWeatherCode() {
        return weatherCode;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
