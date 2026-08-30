package com.wander.reservation.dto;

import java.time.Instant;
import java.time.LocalDateTime;

import com.wander.reservation.Reservation;
import com.wander.reservation.ReservationKind;

import jakarta.validation.constraints.NotNull;

/**
 * A booking as the client draws it.
 *
 * Carries the instant *and* the local wall time, which is redundant on purpose.
 * The instant is the truth and what everything sorts by; the local time is a
 * projection of it through `startZone`, sent along so an edit form can put back
 * exactly what was typed without reconstructing it from an instant and a zone —
 * a conversion that is easy to get subtly wrong in a browser and pointless to do
 * twice.
 */
public record ReservationView(
        @NotNull Long id,
        @NotNull ReservationKind kind,
        @NotNull String title,
        String confirmation,
        String phone,
        String notes,
        @NotNull Instant startsAt,
        @NotNull LocalDateTime startsAtLocal,
        @NotNull String startZone,
        Instant endsAt,
        LocalDateTime endsAtLocal,
        String endZone) {

    public static ReservationView of(Reservation reservation) {
        return new ReservationView(reservation.getId(), reservation.getKind(), reservation.getTitle(),
                reservation.getConfirmation(), reservation.getPhone(), reservation.getNotes(),
                reservation.getStartsAt(), reservation.localStart(), reservation.getStartZone(),
                reservation.getEndsAt(), reservation.localEnd(), reservation.getEndZone());
    }
}
