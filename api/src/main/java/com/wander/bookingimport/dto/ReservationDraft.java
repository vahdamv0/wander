package com.wander.bookingimport.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import com.wander.reservation.ReservationKind;

import jakarta.validation.constraints.NotNull;

/**
 * A booking as a file suggests it, on its way to the form — **never to the
 * database**.
 *
 * Importing writes nothing. It answers with these, the client prefills the
 * booking form it already has, and the user saves through the ordinary
 * {@code createReservation}. So there is one write path, one set of validation
 * rules, and a misread file costs a correction on screen rather than a wrong row
 * that live sync pushes onto everybody else's screen within the second.
 *
 * **Date and time are separate, and neither is required.** That is the whole
 * reason this is not shaped like {@link com.wander.reservation.dto.ReservationRequest}:
 * a {@code LocalDateTime} cannot say "the 15th, time unknown", which is exactly
 * what an all-day calendar entry gives you and exactly what a printed ticket with
 * no time on it gives you. Forcing a midnight in would be inventing a departure
 * hour, so the fields stay apart and the form is left with a blank to fill —
 * which the form already refuses to submit without. They also line up one-to-one
 * with the page's existing draft signals, so prefilling is assignment rather than
 * parsing.
 *
 * A null zone means nothing in the file named one. The client falls back to the
 * reader's own zone, as it does for a booking typed from scratch; the server
 * never guesses one from a city name.
 */
public record ReservationDraft(
        /** OTHER unless the source actually said what kind of booking it is. */
        @NotNull ReservationKind kind,
        @NotNull String title,
        String confirmation,
        String phone,
        String notes,
        LocalDate startDate,
        LocalTime startTime,
        /** An IANA id, or a bare offset when the source gave one without naming a zone. */
        String startZone,
        LocalDate endDate,
        LocalTime endTime,
        String endZone,
        @NotNull DraftSource source) {
}
