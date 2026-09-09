package com.wander.bookingimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wander.bookingimport.dto.DraftSource;
import com.wander.bookingimport.dto.ReservationDraft;
import com.wander.reservation.ReservationKind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The schema.org mapping, from recorded extractor output.
 *
 * The documents here are real answers from {@code kitinerary-extractor 6.7.2},
 * not invented ones — the {@code QDateTime} wrapper with its resolved
 * {@code timezone} is the engine's own shape, and it is the whole reason nothing
 * in this project ships a table of IATA codes. If a future version stopped
 * naming the zone, these tests are what would notice.
 *
 * No network, no binary, no Spring: the mapper is a pure function over JSON, and
 * the interesting failures are all about which field a time came from and
 * whether a zone survived.
 */
class BookingDraftMapperTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static List<ReservationDraft> map(String json) {
        JsonNode node = JSON.readTree(json);
        return BookingDraftMapper.map(List.of(node));
    }

    /**
     * A flight, as the extractor really answers it. Two zones, because it lands
     * somewhere else — which is the case {@code end_zone} exists for and the one
     * a hand-written parser could never get right.
     */
    @Test
    void mapsAFlightWithBothZones() {
        List<ReservationDraft> drafts = map("""
                {
                  "@type": "FlightReservation",
                  "reservationNumber": "XK7RTQ",
                  "reservationFor": {
                    "@type": "Flight",
                    "flightNumber": "NH106",
                    "airline": { "@type": "Airline", "iataCode": "NH", "name": "ANA" },
                    "departureAirport": { "@type": "Airport", "iataCode": "LHR", "name": "Heathrow" },
                    "departureTime": {
                      "@type": "QDateTime",
                      "@value": "2026-10-14T13:05:00+01:00",
                      "timezone": "Europe/London"
                    },
                    "arrivalAirport": { "@type": "Airport", "iataCode": "HND", "name": "Haneda" },
                    "arrivalTime": {
                      "@type": "QDateTime",
                      "@value": "2026-10-15T08:55:00+09:00",
                      "timezone": "Asia/Tokyo"
                    }
                  }
                }
                """);

        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.kind()).isEqualTo(ReservationKind.FLIGHT);
            assertThat(draft.title()).isEqualTo("ANA NH106 · LHR → HND");
            assertThat(draft.confirmation()).isEqualTo("XK7RTQ");
            // The wall clock on the ticket, kept as such rather than converted.
            assertThat(draft.startDate()).isEqualTo(LocalDate.of(2026, 10, 14));
            assertThat(draft.startTime()).isEqualTo(LocalTime.of(13, 5));
            assertThat(draft.startZone()).isEqualTo("Europe/London");
            assertThat(draft.endDate()).isEqualTo(LocalDate.of(2026, 10, 15));
            assertThat(draft.endTime()).isEqualTo(LocalTime.of(8, 55));
            assertThat(draft.endZone()).isEqualTo("Asia/Tokyo");
            assertThat(draft.source()).isEqualTo(DraftSource.EXTRACTOR);
        });
    }

    /**
     * A lodging keeps its times on the reservation, not on the thing reserved,
     * which is why the mapper looks in both places. Reading only one would drop
     * every hotel or every flight, depending which.
     */
    @Test
    void mapsALodgingFromItsOwnCheckinFields() {
        assertThat(map("""
                {
                  "@type": "LodgingReservation",
                  "reservationNumber": "88213-ABQ",
                  "checkinTime": { "@type": "QDateTime", "@value": "2026-10-15T15:00:00+09:00",
                                   "timezone": "Asia/Tokyo" },
                  "checkoutTime": { "@type": "QDateTime", "@value": "2026-10-19T11:00:00+09:00",
                                    "timezone": "Asia/Tokyo" },
                  "reservationFor": {
                    "@type": "LodgingBusiness",
                    "name": "Hotel Granbell Kyoto",
                    "telephone": "+81 75-533-1111",
                    "address": { "@type": "PostalAddress", "streetAddress": "684 Sanchome",
                                 "addressLocality": "Kyoto", "addressCountry": "JP" }
                  }
                }
                """)).singleElement().satisfies(draft -> {
            assertThat(draft.kind()).isEqualTo(ReservationKind.HOTEL);
            assertThat(draft.title()).isEqualTo("Hotel Granbell Kyoto");
            assertThat(draft.phone()).isEqualTo("+81 75-533-1111");
            assertThat(draft.startTime()).isEqualTo(LocalTime.of(15, 0));
            assertThat(draft.endDate()).isEqualTo(LocalDate.of(2026, 10, 19));
            assertThat(draft.notes()).contains("684 Sanchome", "Kyoto");
        });
    }

    /**
     * An offset with no named zone becomes UTC when it is zero — never "Z".
     * Both are valid to ZoneId, but the browser formats with Intl, which rejects
     * "Z" as a time zone, so the plausible-looking choice is the one that blanks
     * the time on somebody's screen.
     */
    @Test
    void namesAZeroOffsetUtcRatherThanZ() {
        assertThat(map("""
                { "@type": "EventReservation",
                  "reservationFor": { "@type": "Event", "name": "Gallery",
                                      "startDate": "2026-10-15T10:00:00Z" } }
                """)).singleElement().extracting(ReservationDraft::startZone).isEqualTo("UTC");
    }

    /** A non-zero offset with no name is kept as the offset: it is all the document gave. */
    @Test
    void keepsANamelessOffsetAsAnOffset() {
        assertThat(map("""
                { "@type": "EventReservation",
                  "reservationFor": { "@type": "Event", "name": "Gallery",
                                      "startDate": "2026-10-15T10:00:00+09:00" } }
                """)).singleElement().extracting(ReservationDraft::startZone).isEqualTo("+09:00");
    }

    /** A plain local date-time has no zone, and none is invented for it. */
    @Test
    void leavesALocalTimeWithoutAZone() {
        assertThat(map("""
                { "@type": "FoodEstablishmentReservation",
                  "startTime": "2026-10-15T19:30:00",
                  "reservationFor": { "@type": "FoodEstablishment", "name": "Kikunoi" } }
                """)).singleElement().satisfies(draft -> {
            assertThat(draft.kind()).isEqualTo(ReservationKind.RESTAURANT);
            assertThat(draft.startTime()).isEqualTo(LocalTime.of(19, 30));
            assertThat(draft.startZone()).isNull();
        });
    }

    /** A date with no time is a date with no time. Midnight would be an invented departure hour. */
    @Test
    void keepsADateWithNoTime() {
        assertThat(map("""
                { "@type": "EventReservation",
                  "reservationFor": { "@type": "Event", "name": "Festival", "startDate": "2026-10-15" } }
                """)).singleElement().satisfies(draft -> {
            assertThat(draft.startDate()).isEqualTo(LocalDate.of(2026, 10, 15));
            assertThat(draft.startTime()).isNull();
        });
    }

    /** A train names stations rather than airports, and the route still reads. */
    @Test
    void mapsATrainRoute() {
        assertThat(map("""
                { "@type": "TrainReservation",
                  "reservationFor": { "@type": "TrainTrip", "trainNumber": "ICE 596",
                    "departureStation": { "@type": "TrainStation", "name": "Berlin Hbf" },
                    "arrivalStation": { "@type": "TrainStation", "name": "München Hbf" },
                    "departureTime": { "@type": "QDateTime", "@value": "2026-10-15T08:34:00+02:00",
                                       "timezone": "Europe/Berlin" } } }
                """)).singleElement().satisfies(draft -> {
            assertThat(draft.kind()).isEqualTo(ReservationKind.TRAIN);
            assertThat(draft.title()).isEqualTo("ICE 596 · Berlin Hbf → München Hbf");
        });
    }

    /** Seat and class have no columns, so they go where a booking's free text already lives. */
    @Test
    void putsTicketDetailsInTheNotes() {
        assertThat(map("""
                { "@type": "FlightReservation",
                  "reservedTicket": { "@type": "Ticket",
                    "ticketedSeat": { "@type": "Seat", "seatNumber": "41K", "seatingType": "Economy" } },
                  "reservationFor": { "@type": "Flight", "flightNumber": "NH106",
                    "departureTime": "2026-10-14T13:05:00+01:00" } }
                """)).singleElement().extracting(ReservationDraft::notes)
                .asString().contains("Seat 41K").contains("Economy");
    }

    /** An unknown type is OTHER — cosmetic if wrong, a claim if invented. */
    @Test
    void fallsBackToOtherForAnUnknownType() {
        assertThat(map("""
                { "@type": "BoatReservation",
                  "reservationFor": { "@type": "BoatTrip", "name": "Hydrofoil",
                    "departureTime": "2026-10-15T09:00:00+02:00" } }
                """)).singleElement().extracting(ReservationDraft::kind).isEqualTo(ReservationKind.FERRY);

        assertThat(map("""
                { "@type": "SomethingNobodyHasHeardOf",
                  "startTime": "2026-10-15T09:00:00+02:00",
                  "reservationFor": { "name": "Mystery" } }
                """)).singleElement().extracting(ReservationDraft::kind).isEqualTo(ReservationKind.OTHER);
    }

    /**
     * A node with no time at all is dropped rather than becoming a draft that
     * offers nothing but a title. The extractor emits partial results for
     * documents it half-recognised, and the user has the file open in front of
     * them.
     */
    @Test
    void dropsANodeWithNoTime() {
        assertThat(map("""
                { "@type": "LodgingReservation", "reservationNumber": "X",
                  "reservationFor": { "@type": "LodgingBusiness", "name": "Somewhere" } }
                """)).isEmpty();
    }

    @Test
    void ignoresRubbishNodes() {
        assertThat(BookingDraftMapper.map(List.of(JSON.readTree("[]")))).isEmpty();
        assertThat(BookingDraftMapper.map(List.of(JSON.readTree("\"text\"")))).isEmpty();
        assertThat(BookingDraftMapper.map(List.of())).isEmpty();
    }
}
