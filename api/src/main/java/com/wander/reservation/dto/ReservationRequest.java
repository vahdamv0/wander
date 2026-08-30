package com.wander.reservation.dto;

import java.time.LocalDateTime;

import com.wander.reservation.ReservationKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A booking as somebody enters it: the wall-clock time from the ticket, plus the
 * zone that time is in.
 *
 * **The client does not compute the instant.** It sends "09:15" and
 * "Europe/London" and the server does the conversion, because Java carries the
 * full IANA database and doing the arithmetic in a browser without a library is
 * exactly the kind of fiddly that produces an hour's error twice a year. The
 * response carries the instant back, and the browser formats *that* in the zone —
 * which is the direction browsers are good at.
 */
public record ReservationRequest(
        @NotNull ReservationKind kind,
        @NotBlank @Size(max = 160) String title,
        @Size(max = 80) String confirmation,
        /** A number to ring, as typed — free text, and never reformatted. */
        @Size(max = 40) String phone,
        @Size(max = 2000) String notes,
        /** The time on the ticket, with no offset attached. */
        @NotNull LocalDateTime startsAtLocal,
        /** An IANA zone id — "Europe/London". Validated against the tz database. */
        @NotBlank @Size(max = 64) String startZone,
        /** Null for a booking with no end worth recording, like a restaurant table. */
        LocalDateTime endsAtLocal,
        /** Defaults to the start's zone, which is right for everything that does not fly. */
        @Size(max = 64) String endZone) {
}
