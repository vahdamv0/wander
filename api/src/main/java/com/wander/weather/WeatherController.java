package com.wander.weather;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wander.security.WanderUser;
import com.wander.weather.dto.TripWeather;

/**
 * The forecast for a trip's days.
 *
 * A request of its own rather than a field on {@code TripItinerary}, unlike the
 * day notes and the day's cost. Those are already in the database when the page
 * asks; this one may have to talk to a weather service first, and hanging that
 * off the itinerary would put an upstream on the critical path of the most-read
 * endpoint in the application. The trip page draws, then the temperatures appear.
 *
 * Authenticated like everything else, and the membership check is inside the
 * service — a proxy that answered anonymously would hand this instance's rate
 * budget to anybody, exactly as the geocoder proxy would.
 */
@RestController
@RequestMapping("/api/trips/{tripId}/weather")
public class WeatherController {

    private final WeatherService weather;

    public WeatherController(WeatherService weather) {
        this.weather = weather;
    }

    /**
     * Named for its resource. ng-openapi-gen exports every operation unqualified
     * into one barrel, so a plain `get` here would collide with another
     * controller's.
     */
    @GetMapping
    public TripWeather getTripWeather(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId) {
        return weather.forTrip(principal.id(), tripId);
    }
}
