package com.wander.weather.dto;

import java.time.LocalDate;

import com.wander.weather.DayWeather;

import jakarta.validation.constraints.NotNull;

/**
 * One day's forecast.
 *
 * Temperatures are sent unrounded: rounding is a display choice and the client
 * makes it, which also means a future "show me Fahrenheit" costs nothing here.
 */
public record DayWeatherView(
        @NotNull LocalDate date,
        @NotNull double tempMaxC,
        @NotNull double tempMinC,
        /** A WMO code. The client picks an icon from it. */
        @NotNull int weatherCode,
        /** The code in words, or blank for a code this application has no wording for. */
        @NotNull String summary) {

    public static DayWeatherView of(DayWeather weather, String summary) {
        return new DayWeatherView(weather.getDayDate(), weather.getTempMaxC(),
                weather.getTempMinC(), weather.getWeatherCode(), summary);
    }
}
