package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.wander.bookingimport.BookingExtractor;

/**
 * An instance with booking import switched off.
 *
 * Its own context, like {@link WeatherDisabledIntegrationTest}, because the
 * switch is read at configuration time. Two halves that have to agree, and that
 * is the point of the test: the endpoint refuses, **and** the client is told not
 * to draw the button. Only one of those failing is the bug that ships — an Import
 * control that is there and then errors reads as a broken instance rather than a
 * configured one.
 *
 * This switch exists for the operator who would rather nothing on their box
 * parsed an uploaded file at all. It is the only way to get that: the calendar
 * reader is pure Java and is otherwise always present, so "off" cannot mean
 * "extractor missing".
 */
@TestPropertySource(properties = "wander.booking-import.enabled=false")
class BookingImportDisabledIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    private BookingExtractor extractor;

    @Test
    void theEndpointRefusesAndNothingIsParsed() {
        Session owner = register("owner");
        Object tripId = asMap(post(owner, "/api/trips", """
                {"name":"Japan","startDate":"2027-07-12","endDate":"2027-07-20"}
                """).getBody()).get("id");

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource("BEGIN:VCALENDAR".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "reservation.ics";
            }
        });

        ResponseEntity<String> response = http().post()
                .uri("/api/trips/" + tripId + "/reservations/import")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(headers -> {
                    headers.add(HttpHeaders.COOKIE, owner.cookie() + "; XSRF-TOKEN=" + owner.csrf());
                    headers.add("X-XSRF-TOKEN", owner.csrf());
                })
                .body(form)
                .retrieve()
                .toEntity(String.class);

        // 503, the same answer place search gives when it is switched off: the
        // request was fine and this instance does not offer the feature.
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(extractor);
    }

    @Test
    void theClientIsToldNotToOfferIt() {
        Session owner = register("owner");

        assertThat(asMap(get(owner, "/api/config").getBody()))
                .containsEntry("bookingImportEnabled", false)
                .containsEntry("bookingDocumentImport", false);
    }

    /** Switched off means the extractor is not even probed for at startup. */
    @Test
    void theExtractorIsNeverAsked() {
        register("owner");
        org.mockito.Mockito.verify(extractor, org.mockito.Mockito.never())
                .extract(any(), anyString(), any(Instant.class));
    }
}
