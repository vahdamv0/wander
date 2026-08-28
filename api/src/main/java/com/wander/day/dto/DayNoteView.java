package com.wander.day.dto;

import java.time.LocalDate;

import com.wander.day.DayNote;

import jakarta.validation.constraints.NotNull;

/** A day's note after a write. `note` is null when the day has none. */
public record DayNoteView(
        @NotNull LocalDate dayDate,
        String note) {

    public static DayNoteView of(DayNote note) {
        return new DayNoteView(note.getDayDate(), note.getNote());
    }

    /** The answer to a write that cleared the day. */
    public static DayNoteView empty(LocalDate dayDate) {
        return new DayNoteView(dayDate, null);
    }
}
