package com.wander.trip;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wander.security.WanderUser;
import com.wander.trip.dto.CreateTripRequest;
import com.wander.trip.dto.TripSummary;

import jakarta.validation.Valid;

/**
 * Every handler here takes the caller's id from the session principal and hands
 * it to the service, which resolves membership before touching anything. No
 * handler reads a trip by id without that step.
 */
@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final TripService trips;

    public TripController(TripService trips) {
        this.trips = trips;
    }

    @GetMapping
    public List<TripSummary> list(@AuthenticationPrincipal WanderUser principal) {
        return trips.listForUser(principal.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TripSummary create(@AuthenticationPrincipal WanderUser principal,
            @Valid @RequestBody CreateTripRequest request) {
        return trips.create(principal.id(), request);
    }

    @GetMapping("/{tripId}")
    public TripSummary get(@AuthenticationPrincipal WanderUser principal, @PathVariable Long tripId) {
        return trips.get(principal.id(), tripId);
    }

    @DeleteMapping("/{tripId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal WanderUser principal, @PathVariable Long tripId) {
        trips.delete(principal.id(), tripId);
    }
}
