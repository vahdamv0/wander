package com.wander.bookingimport;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.wander.bookingimport.dto.BookingImportResult;
import com.wander.security.WanderUser;

/**
 * Reading a confirmation file into draft bookings.
 *
 * Sits under the reservations path because that is what it produces, and the
 * operation id says the resource for the usual reason: ng-openapi-gen exports
 * every operation unqualified into one barrel, so {@code import} would collide
 * with the next importer anybody writes.
 *
 * Authenticated and membership-checked like everything else, and deliberately
 * **not** metered. {@code UpstreamQuota} exists for endpoints that spend
 * somebody else's donated capacity — Nominatim, Commons, Open-Meteo — and this
 * one calls nothing outward: it runs a local process for about a tenth of a
 * second, on a trip the caller can already edit. A counter here would be
 * machinery guarding nothing. The limit that does matter is the size of the
 * upload, and that is {@code spring.servlet.multipart}.
 */
@RestController
@RequestMapping("/api/trips/{tripId}/reservations/import")
public class BookingImportController {

    private final BookingImportService imports;

    public BookingImportController(BookingImportService imports) {
        this.imports = imports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BookingImportResult importReservations(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @RequestPart("file") MultipartFile file) {
        try {
            return imports.read(principal.id(), tripId, file.getBytes(),
                    file.getOriginalFilename());
        } catch (IOException ex) {
            // The upload did not finish arriving. A 400 rather than a 500,
            // because there is nothing wrong with this instance and the client's
            // answer is to send it again — IllegalArgumentException is what
            // ApiExceptionHandler already maps to that.
            throw new IllegalArgumentException("That file could not be read from the request");
        }
    }
}
