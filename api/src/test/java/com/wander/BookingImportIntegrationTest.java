package com.wander;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.wander.bookingimport.BookingExtractor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Booking import over HTTP, with the document extractor replaced.
 *
 * The mock is not about speed. It is what keeps the suite off a 600MB image: the
 * extractor is a native binary that an ordinary build does not have, so a test
 * depending on it would pass on the release image and fail on a laptop. Replacing
 * it also makes the two things worth asserting reachable — that a recognised
 * document becomes drafts, and that an *unrecognised* one falls through to the
 * calendar reader, which is a path you cannot arrange with a real extractor
 * without finding a document it happens not to know.
 *
 * The load-bearing assertion in this file is {@link #importingWritesNothing()}.
 * Everything else about this feature could work perfectly and it would still be
 * wrong if a parse landed in the database: a misread booking would be pushed onto
 * every other member's screen by live sync within the second, with no undo.
 */
class BookingImportIntegrationTest extends IntegrationTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A flight as {@code kitinerary-extractor} really answers one. */
    private static final String FLIGHT = """
            {
              "@type": "FlightReservation",
              "reservationNumber": "XK7RTQ",
              "reservationFor": {
                "@type": "Flight",
                "flightNumber": "NH106",
                "airline": { "@type": "Airline", "iataCode": "NH", "name": "ANA" },
                "departureAirport": { "@type": "Airport", "iataCode": "LHR" },
                "departureTime": { "@type": "QDateTime", "@value": "2027-07-12T13:05:00+01:00",
                                   "timezone": "Europe/London" },
                "arrivalAirport": { "@type": "Airport", "iataCode": "HND" },
                "arrivalTime": { "@type": "QDateTime", "@value": "2027-07-13T08:55:00+09:00",
                                 "timezone": "Asia/Tokyo" }
              }
            }
            """;

    /**
     * A hotel's calendar attachment — the shape the extractor is measured to
     * ignore, which is the entire reason {@code IcsBookingReader} exists.
     */
    private static final String CALENDAR = """
            BEGIN:VCALENDAR\r
            VERSION:2.0\r
            PRODID:-//Example Hotels//Booking//EN\r
            BEGIN:VEVENT\r
            UID:res-88213@example-hotels.com\r
            DTSTART;TZID=Asia/Tokyo:20270715T150000\r
            DTEND;TZID=Asia/Tokyo:20270719T110000\r
            SUMMARY:Hotel Granbell Kyoto\r
            LOCATION:684 Sanchome, Gionmachi Minamigawa, Kyoto\r
            DESCRIPTION:Confirmation number 88213-ABQ\r
            END:VEVENT\r
            END:VCALENDAR\r
            """;

    @MockitoBean
    private BookingExtractor extractor;

    private Object tripFor(Session owner) {
        return asMap(post(owner, "/api/trips", """
                {"name":"Japan","startDate":"2027-07-12","endDate":"2027-07-20"}
                """).getBody()).get("id");
    }

    /**
     * A multipart upload with the session cookie and the CSRF token a browser
     * would send. Written out here rather than added to the base class because
     * this is the only endpoint in the project that takes a file.
     */
    private ResponseEntity<String> upload(Session session, Object tripId, String filename, String content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        ByteArrayResource part = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                // Without a filename Spring treats the part as a plain field and
                // the extension — which is how the reader is chosen — is lost.
                return filename;
            }
        };
        form.add("file", part);

        return http().post()
                .uri("/api/trips/" + tripId + "/reservations/import")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers(headers -> {
                    headers.add(HttpHeaders.COOKIE, session.cookie() + "; XSRF-TOKEN=" + session.csrf());
                    headers.add("X-XSRF-TOKEN", session.csrf());
                })
                .body(form)
                .retrieve()
                .toEntity(String.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> draftsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("drafts");
    }

    private void extractorReturns(String... nodes) {
        when(extractor.isAvailable()).thenReturn(true);
        List<JsonNode> parsed = java.util.Arrays.stream(nodes).map(JSON::readTree).toList();
        when(extractor.extract(any(), anyString(), any(Instant.class))).thenReturn(parsed);
    }

    private void extractorFindsNothing() {
        when(extractor.isAvailable()).thenReturn(true);
        when(extractor.extract(any(), anyString(), any(Instant.class))).thenReturn(List.of());
    }

    @Test
    void aRecognisedFlightBecomesADraft() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        extractorReturns(FLIGHT);

        ResponseEntity<String> response = upload(owner, tripId, "confirmation.eml", "irrelevant");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> body = asMap(response.getBody());
        assertThat(body).containsEntry("extractorAvailable", true).containsEntry("message", "");
        assertThat(draftsOf(body)).singleElement().satisfies(draft -> {
            assertThat(draft).containsEntry("kind", "FLIGHT");
            assertThat(draft).containsEntry("title", "ANA NH106 · LHR → HND");
            assertThat(draft).containsEntry("confirmation", "XK7RTQ");
            // The wall clock from the ticket, with both zones — the pair the
            // reservations table actually stores.
            assertThat(draft).containsEntry("startDate", "2027-07-12");
            assertThat((String) draft.get("startTime")).startsWith("13:05");
            assertThat(draft).containsEntry("startZone", "Europe/London");
            assertThat(draft).containsEntry("endZone", "Asia/Tokyo");
            assertThat(draft).containsEntry("source", "EXTRACTOR");
        });
    }

    /**
     * The whole design in one assertion. Import answers with candidates and the
     * user saves them through the ordinary booking form, so there is one write
     * path and a misread file costs a correction on screen — not a wrong row that
     * live sync broadcasts to everybody else with no way back.
     */
    @Test
    void importingWritesNothing() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        extractorReturns(FLIGHT);

        assertThat(draftsOf(asMap(upload(owner, tripId, "confirmation.eml", "x").getBody()))).hasSize(1);

        Map<String, Object> listed = asMap(get(owner, "/api/trips/" + tripId + "/reservations").getBody());
        assertThat((List<?>) listed.get("reservations"))
                .as("a parsed booking must not reach the database on its own")
                .isEmpty();
    }

    /** A return trip is two legs, and dropping the second would be the first complaint. */
    @Test
    void everyLegComesBack() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        extractorReturns(FLIGHT, FLIGHT.replace("NH106", "NH105").replace("XK7RTQ", "XK7RTR"));

        assertThat(draftsOf(asMap(upload(owner, tripId, "both-legs.pdf", "x").getBody())))
                .hasSize(2)
                .extracting(draft -> draft.get("confirmation"))
                .containsExactly("XK7RTQ", "XK7RTR");
    }

    /**
     * The measured gap, closed. A plain VEVENT is what the extractor returns
     * nothing for — verified standalone and attached, with a context date and
     * validation disabled — so the calendar reader is asked second.
     */
    @Test
    void aCalendarAttachmentIsReadWhenTheExtractorFindsNothing() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        extractorFindsNothing();

        Map<String, Object> body = asMap(upload(owner, tripId, "reservation.ics", CALENDAR).getBody());

        assertThat(draftsOf(body)).singleElement().satisfies(draft -> {
            assertThat(draft).containsEntry("title", "Hotel Granbell Kyoto");
            assertThat(draft).containsEntry("startDate", "2027-07-15");
            assertThat(draft).containsEntry("startZone", "Asia/Tokyo");
            // Never guessed from the summary, even though it says "Hotel".
            assertThat(draft).containsEntry("kind", "OTHER");
            assertThat(draft).containsEntry("source", "CALENDAR");
            // The confirmation code is left where it was found rather than fished
            // out of prose.
            assertThat(draft.get("confirmation")).isNull();
            assertThat((String) draft.get("notes")).contains("88213-ABQ");
        });
    }

    /**
     * The case that actually happens: nobody has a bare {@code .ics}, they have
     * the email it was attached to. Without the MIME walk this reader would only
     * ever fire on a file almost no one possesses.
     */
    @Test
    void aCalendarInsideAnEmailIsFound() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        extractorFindsNothing();

        String message = """
                From: reservations@example-hotels.com
                To: traveller@example.org
                Subject: Reservation 88213-ABQ confirmed
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="b1"

                --b1
                Content-Type: text/plain; charset=UTF-8

                Your reservation is confirmed.

                --b1
                Content-Type: text/calendar; charset=UTF-8; method=REQUEST
                Content-Disposition: attachment; filename="reservation.ics"

                %s
                --b1--
                """.formatted(CALENDAR);

        assertThat(draftsOf(asMap(upload(owner, tripId, "forwarded.eml", message).getBody())))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft).containsEntry("title", "Hotel Granbell Kyoto");
                    assertThat(draft).containsEntry("source", "CALENDAR");
                });
    }

    /**
     * Nothing recognised is a 200 with an empty list, not an error. An
     * unreadable confirmation is a nicety not delivered — the enrichment rule —
     * and the message is what makes that actionable rather than mysterious.
     */
    @Test
    void anUnrecognisedFileIsAnHonestEmptyAnswer() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        extractorFindsNothing();

        ResponseEntity<String> response = upload(owner, tripId, "ticket.pdf", "%PDF-1.4 nothing useful");
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> body = asMap(response.getBody());
        assertThat(draftsOf(body)).isEmpty();
        assertThat((String) body.get("message")).contains("recognised");
    }

    /** An allow-list, and the refusal names the formats rather than just saying no. */
    @Test
    void anUnsupportedFileIsRefusedWithTheListOfFormats() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);

        ResponseEntity<String> response = upload(owner, tripId, "boarding.docx", "x");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains(".eml").contains(".ics");
        verifyNoInteractions(extractor);
    }

    /** A VIEWER may read a trip and may not put a booking on it, so may not draft one either. */
    @Test
    void aViewerCannotImport() {
        Session owner = register("owner");
        Session viewer = register("viewer");
        Object tripId = tripFor(owner);
        assertThat(post(owner, "/api/trips/" + tripId + "/members", """
                {"email":"%s","role":"VIEWER"}
                """.formatted(viewer.email())).getStatusCode().value()).isEqualTo(201);
        extractorReturns(FLIGHT);

        assertThat(upload(viewer, tripId, "confirmation.eml", "x").getStatusCode().value()).isEqualTo(403);
    }

    /**
     * A non-member gets 404, not 403 — the rule {@code TripAccessService} exists
     * for. Note the check runs before the file is looked at, so the extractor is
     * never handed a stranger's upload.
     */
    @Test
    void aStrangerGetsNotFound() {
        Session owner = register("owner");
        Session stranger = register("stranger");
        Object tripId = tripFor(owner);
        extractorReturns(FLIGHT);

        assertThat(upload(stranger, tripId, "confirmation.eml", "x").getStatusCode().value()).isEqualTo(404);
        verify(extractor, org.mockito.Mockito.never()).extract(any(), anyString(), any(Instant.class));
    }

    /**
     * An instance with no document extractor still imports calendars, and says
     * so. Reporting this as "nothing found" would send somebody looking for a
     * fault in a file that is perfectly fine.
     */
    @Test
    void anInstanceWithoutTheExtractorSaysWhatItCanRead() {
        Session owner = register("owner");
        Object tripId = tripFor(owner);
        when(extractor.isAvailable()).thenReturn(false);
        when(extractor.extract(any(), anyString(), any(Instant.class))).thenReturn(List.of());

        Map<String, Object> body = asMap(upload(owner, tripId, "ticket.pdf", "%PDF-1.4").getBody());
        assertThat(body).containsEntry("extractorAvailable", false);
        assertThat((String) body.get("message")).contains("calendar");

        // And the calendar reader still works there, which is what makes the
        // feature worth offering on that image at all.
        assertThat(draftsOf(asMap(upload(owner, tripId, "reservation.ics", CALENDAR).getBody())))
                .hasSize(1);
    }

    @Test
    void theClientIsToldWhatThisInstanceCanDo() {
        Session owner = register("owner");
        when(extractor.isAvailable()).thenReturn(true);

        assertThat(asMap(get(owner, "/api/config").getBody()))
                .containsEntry("bookingImportEnabled", true)
                .containsEntry("bookingDocumentImport", true);
    }
}
