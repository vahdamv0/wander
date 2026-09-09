package com.wander.bookingimport;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.wander.bookingimport.dto.DraftSource;
import com.wander.bookingimport.dto.ReservationDraft;
import com.wander.reservation.ReservationKind;

import tools.jackson.databind.JsonNode;

/**
 * A schema.org reservation node, as a draft booking.
 *
 * One mapper for everything {@link BookingExtractor} produces, because
 * everything it produces is the same vocabulary whether it came from a vendor
 * extractor, an email's JSON-LD, its microdata, a boarding-pass barcode or an
 * Apple Wallet pass. That is the property that made borrowing the engine worth
 * it: 349 formats arrive here as nine types.
 *
 * Two things it will not do. It does not read prose — every field comes from a
 * named property, so a title is assembled from an airline and a flight number
 * rather than found in a sentence. And it does not fill a gap: a node with no
 * time yields a draft with no time, and the form asks. The alternative on both
 * counts is a confident wrong answer, which for a booking means somebody at an
 * airport at the wrong hour.
 */
final class BookingDraftMapper {

    private BookingDraftMapper() {
    }

    /**
     * Every node that yields something usable.
     *
     * A node with no start is dropped rather than becoming a draft with an empty
     * date: the extractor emits partial results for documents it half-recognised,
     * and a row offering nothing but a title is worse than not offering it — the
     * user has the file open in front of them.
     */
    static List<ReservationDraft> map(List<JsonNode> nodes) {
        List<ReservationDraft> drafts = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node != null && node.isObject()) {
                toDraft(node).ifPresent(drafts::add);
            }
        }
        return drafts;
    }

    private static Optional<ReservationDraft> toDraft(JsonNode node) {
        String type = text(node, "@type");
        JsonNode subject = node.path("reservationFor");

        Moment start = firstMoment(node, subject, startFields(type));
        if (start == null) {
            return Optional.empty();
        }
        Moment end = firstMoment(node, subject, endFields(type));

        return Optional.of(new ReservationDraft(
                kindOf(type),
                trimTo(titleOf(type, node, subject), 160, "Booking"),
                trimTo(text(node, "reservationNumber"), 80, null),
                trimTo(phoneOf(node, subject), 40, null),
                trimTo(notesOf(type, node, subject), 2000, null),
                start.date(), start.time(), start.zone(),
                end == null ? null : end.date(),
                end == null ? null : end.time(),
                end == null ? null : end.zone(),
                DraftSource.EXTRACTOR));
    }

    /**
     * OTHER for anything unrecognised, and that is the safe direction: the kind
     * only picks an icon and a label, so a wrong one is cosmetic while an invented
     * one is a claim. Nothing branches on it — which is exactly why OTHER is an
     * adequate escape hatch here as it is everywhere else.
     */
    private static ReservationKind kindOf(String type) {
        return switch (type) {
            case "FlightReservation" -> ReservationKind.FLIGHT;
            case "TrainReservation" -> ReservationKind.TRAIN;
            case "BusReservation" -> ReservationKind.BUS;
            case "BoatReservation" -> ReservationKind.FERRY;
            case "RentalCarReservation", "TaxiReservation" -> ReservationKind.CAR;
            case "LodgingReservation" -> ReservationKind.HOTEL;
            case "FoodEstablishmentReservation" -> ReservationKind.RESTAURANT;
            case "EventReservation" -> ReservationKind.ACTIVITY;
            default -> ReservationKind.OTHER;
        };
    }

    /**
     * Where each type keeps its start, most specific first.
     *
     * The vocabulary genuinely differs per type — a flight departs, a hotel is
     * checked into, a car is picked up — and a single "startTime" would have to
     * be guessed at for most of them. The generic pair is kept at the end of each
     * list because some extractors emit it alongside the specific one, and one is
     * better than none.
     */
    private static List<String> startFields(String type) {
        return switch (type) {
            case "FlightReservation", "TrainReservation", "BusReservation", "BoatReservation" ->
                List.of("departureTime", "startTime", "startDate");
            case "LodgingReservation" -> List.of("checkinTime", "startTime", "startDate");
            case "RentalCarReservation", "TaxiReservation" -> List.of("pickupTime", "startTime", "startDate");
            default -> List.of("startTime", "startDate", "checkinTime", "departureTime");
        };
    }

    private static List<String> endFields(String type) {
        return switch (type) {
            case "FlightReservation", "TrainReservation", "BusReservation", "BoatReservation" ->
                List.of("arrivalTime", "endTime", "endDate");
            case "LodgingReservation" -> List.of("checkoutTime", "endTime", "endDate");
            case "RentalCarReservation", "TaxiReservation" -> List.of("dropoffTime", "endTime", "endDate");
            default -> List.of("endTime", "endDate", "checkoutTime", "arrivalTime");
        };
    }

    /**
     * A named property, looked for on the reservation and then on the thing
     * reserved.
     *
     * Both, because the vocabulary puts them in different places and extractors
     * are not consistent about it: a lodging reservation carries its own
     * {@code checkinTime}, while a flight's times belong to the {@code Flight}.
     */
    private static Moment firstMoment(JsonNode node, JsonNode subject, List<String> fields) {
        for (String field : fields) {
            Moment moment = moment(node.get(field));
            if (moment != null) {
                return moment;
            }
            moment = moment(subject.get(field));
            if (moment != null) {
                return moment;
            }
        }
        return null;
    }

    /**
     * A schema.org date-time, in either of the two shapes that arrive.
     *
     * A plain ISO string is the vocabulary's own form. The other is KDE's
     * {@code QDateTime} wrapper, and it is the more valuable of the two: it
     * carries {@code timezone} beside the value, resolved by the engine from its
     * own airport and address databases. That named zone is what makes an
     * imported flight correct — an offset says what the clock read, but only a
     * zone can put "09:15" back on the screen, and the pair of them is precisely
     * what {@code reservations} stores.
     */
    private static Moment moment(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        String value;
        String named = null;
        if (node.isObject()) {
            value = text(node, "@value");
            named = blankToNull(text(node, "timezone"));
        } else if (node.isString()) {
            value = node.asString();
        } else {
            return null;
        }
        if (value == null || value.isBlank()) {
            return null;
        }

        String zone = validZone(named);
        try {
            // With an offset: the local half is the wall clock on the ticket, and
            // it is kept as such rather than converted — this project stores what
            // was printed, plus where.
            OffsetDateTime offset = OffsetDateTime.parse(value);
            return new Moment(offset.toLocalDate(), offset.toLocalTime(),
                    zone != null ? zone : zoneFor(offset.getOffset()));
        } catch (DateTimeException ignored) {
            // Not an offset form; fall through.
        }
        try {
            LocalDateTime local = LocalDateTime.parse(value);
            return new Moment(local.toLocalDate(), local.toLocalTime(), zone);
        } catch (DateTimeException ignored) {
            // Nor a local date-time.
        }
        try {
            return new Moment(LocalDate.parse(value), null, zone);
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    /**
     * A zone name for an offset, used only when the source named none.
     *
     * Zero becomes {@code UTC} rather than {@code Z}: both are valid to
     * {@link ZoneId}, but the browser formats with {@code Intl}, which does not
     * accept {@code Z} as a time zone — so the honest-looking one is the one that
     * would blank the time on the page. A non-zero offset is kept as an offset,
     * which is all the source gave us; it displays correctly and the picker lets
     * the user name the real zone if they care.
     */
    private static String zoneFor(ZoneOffset offset) {
        return offset.getTotalSeconds() == 0 ? "UTC" : offset.getId();
    }

    /** Checked against the tz database rather than trusted, as everywhere else here. */
    private static String validZone(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(id).getId();
        } catch (DateTimeException ex) {
            return null;
        }
    }

    /**
     * What the row will be called.
     *
     * Assembled from named fields, never lifted from a sentence. For anything
     * that travels, the route is part of the name: "NH106 · LHR → HND" is what
     * makes a list of four flights readable, and the alternative is four rows
     * called "ANA".
     */
    private static String titleOf(String type, JsonNode node, JsonNode subject) {
        return switch (type) {
            case "FlightReservation" -> join(" · ",
                    join(" ", text(subject.path("airline"), "name"), text(subject, "flightNumber")),
                    route(code(subject.path("departureAirport")), code(subject.path("arrivalAirport"))));
            case "TrainReservation" -> join(" · ",
                    join(" ", text(subject, "trainName"), text(subject, "trainNumber")),
                    route(text(subject.path("departureStation"), "name"),
                            text(subject.path("arrivalStation"), "name")));
            case "BusReservation" -> join(" · ",
                    join(" ", text(subject, "busName"), text(subject, "busNumber")),
                    route(text(subject.path("departureBusStop"), "name"),
                            text(subject.path("arrivalBusStop"), "name")));
            case "BoatReservation" -> join(" · ", text(subject, "name"),
                    route(text(subject.path("departureBoatTerminal"), "name"),
                            text(subject.path("arrivalBoatTerminal"), "name")));
            case "RentalCarReservation" -> join(" · ",
                    firstNonBlank(text(subject.path("rentalCompany"), "name"), text(subject, "name")),
                    join(" ", text(subject, "make"), text(subject, "model")));
            case "TaxiReservation" -> firstNonBlank(text(subject.path("provider"), "name"), "Taxi");
            default -> text(subject, "name");
        };
    }

    /**
     * An airport's IATA code in preference to its name.
     *
     * "LHR → HND" is shorter than "Heathrow → Haneda" and is what the ticket
     * says; the name is the fallback for the extractors that omit the code.
     */
    private static String code(JsonNode airport) {
        return firstNonBlank(text(airport, "iataCode"), text(airport, "name"));
    }

    private static String route(String from, String to) {
        if (from.isBlank() || to.isBlank()) {
            return firstNonBlank(from, to);
        }
        return from + " → " + to;
    }

    /**
     * A number to ring, from wherever the document put it — and never
     * reformatted, which is the same rule the field itself carries. The engine
     * has libphonenumber behind it and normalises these to E.164, so what arrives
     * is already dialable; the point stands that nothing here should second-guess
     * it.
     */
    private static String phoneOf(JsonNode node, JsonNode subject) {
        return firstNonBlank(text(subject, "telephone"),
                text(node.path("pickupLocation"), "telephone"),
                text(subject.path("address"), "telephone"),
                text(node.path("provider"), "telephone"));
    }

    /**
     * The facts that have nowhere else to go: an address, and the details printed
     * on a ticket that people actually look for.
     *
     * Seat and coach are here rather than in fields of their own because
     * {@code reservations} has no columns for them and inventing two would be a
     * migration in service of a label. They are worth keeping — "Seat 41K" is the
     * second thing anybody checks — and notes is where a booking's free text
     * already lives.
     */
    private static String notesOf(String type, JsonNode node, JsonNode subject) {
        List<String> lines = new ArrayList<>();

        String address = addressOf(subject.path("address"));
        if (!address.isBlank()) {
            lines.add(address);
        }
        String pickup = addressOf(node.path("pickupLocation").path("address"));
        if (!pickup.isBlank()) {
            lines.add("Pick-up: " + pickup);
        }
        String dropoff = addressOf(node.path("dropoffLocation").path("address"));
        if (!dropoff.isBlank()) {
            lines.add("Drop-off: " + dropoff);
        }

        String ticket = join(" · ",
                labelled("Seat", text(node.path("reservedTicket"), "ticketedSeat", "seatNumber")),
                labelled("Coach", text(node.path("reservedTicket"), "ticketedSeat", "seatSection")),
                labelled("Class", firstNonBlank(text(node, "class"),
                        text(node.path("reservedTicket").path("ticketedSeat"), "seatingType"))),
                labelled("Programme", text(node.path("programMembershipUsed"), "membershipNumber")));
        if (!ticket.isBlank()) {
            lines.add(ticket);
        }

        String url = firstNonBlank(text(subject, "url"), text(node, "url"));
        if (!url.isBlank()) {
            lines.add(url);
        }

        return String.join("\n", lines);
    }

    private static String addressOf(JsonNode address) {
        if (address.isString()) {
            return address.asString();
        }
        return join(", ", text(address, "streetAddress"), text(address, "addressLocality"),
                text(address, "postalCode"), text(address, "addressCountry"));
    }

    private static String labelled(String label, String value) {
        return value.isBlank() ? "" : label + " " + value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? "" : value.asString().trim();
    }

    /** A two-hop read, for the nested shapes {@code reservedTicket.ticketedSeat.seatNumber} takes. */
    private static String text(JsonNode node, String first, String second) {
        return node == null ? "" : text(node.path(first), second);
    }

    private static String join(String separator, String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part.trim());
            }
        }
        return String.join(separator, present);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String trimTo(String value, int max, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private record Moment(LocalDate date, LocalTime time, String zone) {
    }
}
