package com.wander.day.dto;

import jakarta.validation.constraints.Size;

/**
 * The whole note, replacing whatever was there. An absent or blank note clears
 * the day: it is the same intent as "no note", and a client should not need a
 * second endpoint to express it.
 */
public record DayNoteRequest(
        @Size(max = 4000) String note) {
}
