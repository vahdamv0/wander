package com.wander.weather;

/**
 * WMO weather codes in words.
 *
 * On the server rather than in the client, for the same reason the tile
 * attribution is: a bare code is meaningless without this table, and one copy of
 * it is better than one per client. The client picks an icon from the code's
 * band — that half genuinely is presentation.
 *
 * Grouped rather than exhaustive. The standard distinguishes "light" from
 * "moderate" drizzle; a day card that does is a day card nobody reads.
 */
final class WeatherCodes {

    private WeatherCodes() {
    }

    static String describe(int code) {
        return switch (code) {
            case 0 -> "Clear";
            case 1 -> "Mostly clear";
            case 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45, 48 -> "Fog";
            case 51, 53, 55, 56, 57 -> "Drizzle";
            case 61, 63, 66 -> "Rain";
            case 65, 67 -> "Heavy rain";
            case 71, 73, 75, 77, 85, 86 -> "Snow";
            case 80, 81 -> "Showers";
            case 82 -> "Heavy showers";
            case 95, 96, 99 -> "Thunderstorm";
            // Not "Unknown": the code came from a real forecast and the day is
            // not blank, so saying nothing about the conditions is more honest
            // than inventing a category for them.
            default -> "";
        };
    }
}
