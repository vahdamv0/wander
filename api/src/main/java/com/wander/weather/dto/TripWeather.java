package com.wander.weather.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * What is known about the weather on this trip's days.
 *
 * Only days there is a forecast for appear. Days beyond the forecast horizon,
 * days with no located place, and every day of a trip next summer are simply
 * absent — the client shows nothing for them rather than a placeholder implying
 * that data is on its way, because for a trip in nine months it is not.
 *
 * `attribution` is non-empty whenever `days` is: CC BY 4.0 obliges it, and
 * sending it with the data rather than compiling it into the client is the same
 * rule the tile attribution follows — an operator pointing this at their own
 * weather service changes one setting and the credit follows.
 */
public record TripWeather(
        /** False when the operator has weather switched off; the client draws nothing. */
        @NotNull boolean available,
        @NotNull String attribution,
        @NotNull String attributionUrl,
        @NotNull List<DayWeatherView> days) {

    public static TripWeather unavailable() {
        return new TripWeather(false, "", "", List.of());
    }
}
