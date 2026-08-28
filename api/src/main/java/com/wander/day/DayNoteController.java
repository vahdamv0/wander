package com.wander.day;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wander.day.dto.DayNoteRequest;
import com.wander.day.dto.DayNoteView;
import com.wander.security.WanderUser;

import jakarta.validation.Valid;

/**
 * A day is addressed by its date, because that is what a derived day has
 * instead of an id. The method name is the operation id and ng-openapi-gen
 * exports it unqualified, so it is `putDayNote`, not `put`.
 */
@RestController
@RequestMapping("/api/trips/{tripId}/days")
public class DayNoteController {

    private final DayNoteService notes;

    public DayNoteController(DayNoteService notes) {
        this.notes = notes;
    }

    /** Upsert, and a blank note clears the day. Reads come with the itinerary. */
    @PutMapping("/{date}/note")
    public DayNoteView putDayNote(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody DayNoteRequest request) {
        return notes.put(principal.id(), tripId, date, request);
    }
}
