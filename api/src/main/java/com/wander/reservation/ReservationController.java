package com.wander.reservation;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.wander.reservation.dto.ReservationRequest;
import com.wander.reservation.dto.ReservationView;
import com.wander.reservation.dto.TripReservations;
import com.wander.security.WanderUser;

import jakarta.validation.Valid;

/** A trip's bookings. Operation ids are global on the client, hence the resource names. */
@RestController
@RequestMapping("/api/trips/{tripId}/reservations")
public class ReservationController {

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @GetMapping
    public TripReservations listReservations(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId) {
        return reservations.list(principal.id(), tripId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationView createReservation(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @Valid @RequestBody ReservationRequest request) {
        return reservations.create(principal.id(), tripId, request);
    }

    @PutMapping("/{reservationId}")
    public ReservationView updateReservation(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long reservationId,
            @Valid @RequestBody ReservationRequest request) {
        return reservations.update(principal.id(), tripId, reservationId, request);
    }

    @DeleteMapping("/{reservationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteReservation(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable Long reservationId) {
        reservations.delete(principal.id(), tripId, reservationId);
    }
}
