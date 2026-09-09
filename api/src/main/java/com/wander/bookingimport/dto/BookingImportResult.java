package com.wander.bookingimport.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * What one uploaded file yielded.
 *
 * A list, because one confirmation is routinely several bookings — a return
 * flight is two legs, and a calendar attachment can hold many events. Dropping
 * the return leg would be the first thing anybody complained about.
 *
 * An empty list is a normal answer, not an error, which is why {@code message}
 * exists: nothing recognised the file, and the honest thing is to say which
 * readers looked. {@code extractorAvailable} is how that message can be truthful
 * on an image built without the document extractor — there, a calendar
 * attachment still works and a PDF cannot, and the difference is worth naming
 * rather than reporting as "no bookings found".
 */
public record BookingImportResult(
        @NotNull List<ReservationDraft> drafts,
        /** False when this instance has no document extractor installed. */
        @NotNull boolean extractorAvailable,
        /** Empty when something was found; otherwise why not. */
        @NotNull String message) {
}
