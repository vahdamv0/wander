package com.wander.bookingimport;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wander.bookingimport.dto.DraftSource;
import com.wander.bookingimport.dto.ReservationDraft;
import com.wander.reservation.ReservationKind;

/**
 * A plain iCalendar event, read as a draft booking.
 *
 * This exists because of one measured gap, not on principle. KItinerary handles
 * schema.org JSON-LD, schema.org microdata and 349 provider formats, and a
 * hand-written parser competing with any of that would be worse in every way —
 * but a **generic {@code VEVENT} it ignores entirely**, standalone or attached to
 * an email, with a context date supplied and validation disabled. That was
 * verified before this file was written. It matters because a calendar
 * attachment is what a hotel, restaurant or tour operator with no vendor
 * extractor sends, and that long tail is precisely the self-hoster's small local
 * hotel.
 *
 * So the scope is narrow on purpose: the two things a {@code VEVENT} states as
 * fact are **when** and **what it is called**, and those are the only things
 * taken from it.
 *
 * - **The kind is always OTHER.** A summary reading "Hotel Granbell Kyoto" is
 *   prose, and inferring HOTEL from the word would be promoting a guess to a
 *   fact — the same mistake as reading {@code places.category} off a name.
 * - **No confirmation code is fished out of the text.** It is usually right
 *   there in the description, and the description travels verbatim into the
 *   notes, so it is in front of the user either way. Guessing which token is the
 *   reference is how you confidently fill the field in with a flight number.
 * - **A zone is used only when the file names one.** An unrecognised {@code TZID}
 *   leaves it null and the client falls back to the reader's own zone, exactly as
 *   it does for a booking typed from scratch.
 *
 * Pure and static, which is why it is not an implementation of
 * {@link BookingExtractor}: there is nothing external to stand in for, and it
 * earns a unit test for the reason {@code money.spec.ts} and {@code zones.spec.ts}
 * do — a wrong zone here throws nothing, logs nothing and fails no request. It
 * shows up as a booking an hour out, or on the wrong day.
 */
public final class IcsBookingReader {

    /**
     * A calendar can be arbitrarily long and this one arrived from a stranger's
     * mail. Fifty events is far past any real confirmation and well short of
     * anything worth worrying about.
     */
    private static final int MAX_EVENTS = 50;

    /**
     * Outlook writes Windows zone names — {@code TZID="W. Europe Standard Time"}
     * — which are not IANA ids and which {@link ZoneId#of} rejects outright.
     *
     * Deliberately partial. It covers the zones a trip is actually booked in and
     * stops; anything unlisted falls through to a null zone, which the form asks
     * the user about. The alternative to a partial map is not a complete one, it
     * is corporate travel confirmations silently losing their times.
     */
    private static final Map<String, String> WINDOWS_ZONES = Map.ofEntries(
            Map.entry("GMT STANDARD TIME", "Europe/London"),
            Map.entry("GREENWICH STANDARD TIME", "Atlantic/Reykjavik"),
            Map.entry("W. EUROPE STANDARD TIME", "Europe/Berlin"),
            Map.entry("CENTRAL EUROPE STANDARD TIME", "Europe/Budapest"),
            Map.entry("CENTRAL EUROPEAN STANDARD TIME", "Europe/Warsaw"),
            Map.entry("ROMANCE STANDARD TIME", "Europe/Paris"),
            Map.entry("E. EUROPE STANDARD TIME", "Europe/Chisinau"),
            Map.entry("GTB STANDARD TIME", "Europe/Bucharest"),
            Map.entry("FLE STANDARD TIME", "Europe/Helsinki"),
            Map.entry("RUSSIAN STANDARD TIME", "Europe/Moscow"),
            Map.entry("TURKEY STANDARD TIME", "Europe/Istanbul"),
            Map.entry("ISRAEL STANDARD TIME", "Asia/Jerusalem"),
            Map.entry("ARABIAN STANDARD TIME", "Asia/Dubai"),
            Map.entry("INDIA STANDARD TIME", "Asia/Kolkata"),
            Map.entry("SE ASIA STANDARD TIME", "Asia/Bangkok"),
            Map.entry("SINGAPORE STANDARD TIME", "Asia/Singapore"),
            Map.entry("CHINA STANDARD TIME", "Asia/Shanghai"),
            Map.entry("TOKYO STANDARD TIME", "Asia/Tokyo"),
            Map.entry("KOREA STANDARD TIME", "Asia/Seoul"),
            Map.entry("AUS EASTERN STANDARD TIME", "Australia/Sydney"),
            Map.entry("NEW ZEALAND STANDARD TIME", "Pacific/Auckland"),
            Map.entry("EASTERN STANDARD TIME", "America/New_York"),
            Map.entry("CENTRAL STANDARD TIME", "America/Chicago"),
            Map.entry("MOUNTAIN STANDARD TIME", "America/Denver"),
            Map.entry("PACIFIC STANDARD TIME", "America/Los_Angeles"),
            Map.entry("US EASTERN STANDARD TIME", "America/Indiana/Indianapolis"),
            Map.entry("CANADA CENTRAL STANDARD TIME", "America/Regina"),
            Map.entry("SA PACIFIC STANDARD TIME", "America/Bogota"),
            Map.entry("E. SOUTH AMERICA STANDARD TIME", "America/Sao_Paulo"),
            Map.entry("ARGENTINA STANDARD TIME", "America/Argentina/Buenos_Aires"),
            Map.entry("SOUTH AFRICA STANDARD TIME", "Africa/Johannesburg"),
            Map.entry("EGYPT STANDARD TIME", "Africa/Cairo"),
            Map.entry("MOROCCO STANDARD TIME", "Africa/Casablanca"),
            Map.entry("UTC", "UTC"));

    private IcsBookingReader() {
    }

    /** True when these bytes look like a calendar at all — cheap enough to ask before parsing. */
    public static boolean looksLikeCalendar(byte[] content) {
        // Only the head is examined: a VCALENDAR declaration is the first thing
        // in the file, and scanning a whole multi-megabyte upload to answer "is
        // this even worth trying" would be the expensive way to say no.
        int window = Math.min(content.length, 4096);
        return new String(content, 0, window, StandardCharsets.UTF_8).contains("BEGIN:VCALENDAR");
    }

    /** Every usable {@code VEVENT} in the calendar, in file order. */
    public static List<ReservationDraft> read(byte[] content) {
        List<ReservationDraft> drafts = new ArrayList<>();
        Map<String, Property> event = null;

        for (String line : unfold(new String(content, StandardCharsets.UTF_8))) {
            String upper = line.toUpperCase();
            if (upper.startsWith("BEGIN:VEVENT")) {
                event = new LinkedHashMap<>();
            } else if (upper.startsWith("END:VEVENT")) {
                if (event != null) {
                    toDraft(event).ifPresent(drafts::add);
                    event = null;
                }
                if (drafts.size() >= MAX_EVENTS) {
                    break;
                }
            } else if (event != null) {
                Property property = Property.parse(line);
                if (property != null) {
                    // First occurrence wins. A repeated property is malformed,
                    // and the alternative — last wins — makes the answer depend
                    // on how far down the file somebody appended something.
                    event.putIfAbsent(property.name(), property);
                }
            }
        }
        return drafts;
    }

    /**
     * Content lines, with folding undone.
     *
     * RFC 5545 wraps long lines at 75 octets and marks the continuation with a
     * leading space or tab, so a {@code LOCATION} with a full street address
     * routinely arrives in three pieces. Parsing before unfolding gets you a
     * property whose value stops mid-word and two lines that look like unknown
     * properties.
     */
    static List<String> unfold(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : text.split("\r\n|\n|\r")) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                current.append(raw, 1, raw.length());
            } else {
                if (current.length() > 0) {
                    lines.add(current.toString());
                }
                current.setLength(0);
                current.append(raw);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static java.util.Optional<ReservationDraft> toDraft(Map<String, Property> event) {
        Moment start = moment(event.get("DTSTART"));
        if (start == null || start.date() == null) {
            // No start is no booking. A VEVENT without one is either malformed
            // or a recurrence rule we are not in the business of expanding.
            return java.util.Optional.empty();
        }
        Moment end = endOf(event, start);

        String summary = text(event.get("SUMMARY"));
        String location = text(event.get("LOCATION"));
        String description = text(event.get("DESCRIPTION"));

        String title = !summary.isBlank() ? summary
                : !location.isBlank() ? location
                        : "Booking";

        return java.util.Optional.of(new ReservationDraft(
                // Never inferred from the summary. See the class comment.
                ReservationKind.OTHER,
                trimTo(title, 160),
                null,
                null,
                // Verbatim, and this is where a confirmation code the user wants
                // will be sitting. Location first: it is the shorter and the more
                // likely to be the thing they are looking for.
                trimTo(joinNonBlank(location, description), 2000),
                start.date(), start.time(), start.zone(),
                end == null ? null : end.date(),
                end == null ? null : end.time(),
                end == null ? null : end.zone(),
                DraftSource.CALENDAR));
    }

    /**
     * When the event ends: {@code DTEND} if present, otherwise {@code DTSTART}
     * plus {@code DURATION}.
     *
     * **A date-only {@code DTEND} is exclusive**, which is the detail worth
     * getting right. RFC 5545 says a four-night hotel stay from the 15th is
     * written {@code DTEND;VALUE=DATE:20261019}, meaning the event covers up to
     * but not including the 19th. Take that literally into a booking and every
     * imported stay checks out a day late — a mistake nobody would look for,
     * because the number in the file matches the number on the screen. A
     * {@code DTEND} that carries a time is an instant and needs no such
     * adjustment.
     */
    private static Moment endOf(Map<String, Property> event, Moment start) {
        Moment end = moment(event.get("DTEND"));
        if (end != null) {
            if (end.time() == null && end.date() != null && end.date().isAfter(start.date())) {
                return new Moment(end.date().minusDays(1), null, end.zone());
            }
            return end;
        }

        Duration duration = duration(text(event.get("DURATION")));
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return null;
        }
        if (start.time() == null) {
            // An all-day event with a duration in days: same exclusive-end
            // reasoning, so a P4D event starting on the 15th ends on the 18th.
            long days = Math.max(duration.toDays(), 1);
            return new Moment(start.date().plusDays(days - 1), null, start.zone());
        }
        java.time.LocalDateTime finish = start.date().atTime(start.time()).plus(duration);
        return new Moment(finish.toLocalDate(), finish.toLocalTime(), start.zone());
    }

    /**
     * A {@code DTSTART}/{@code DTEND} value, in any of the three forms the
     * specification allows — and they mean genuinely different things:
     *
     * <ul>
     * <li>{@code ;TZID=Asia/Tokyo:20261015T150000} — a wall clock in a named
     *     zone. The best case, and exactly the pair this project stores.</li>
     * <li>{@code :20261015T150000Z} — an instant in UTC. The zone is {@code UTC},
     *     which is true but is rarely where the booking is; the reader sees a
     *     correct moment shown in the wrong place, and can change it.</li>
     * <li>{@code :20261015T150000} — floating: a wall clock with no zone at all,
     *     meaning "local time wherever you are". Left null rather than being
     *     resolved here, because the server is not where the reader is.</li>
     * </ul>
     */
    private static Moment moment(Property property) {
        if (property == null) {
            return null;
        }
        String value = property.value().trim();
        if (value.isEmpty()) {
            return null;
        }

        boolean dateOnly = "DATE".equalsIgnoreCase(property.param("VALUE")) || value.length() == 8;
        try {
            if (dateOnly) {
                return new Moment(parseDate(value), null, zoneOf(property));
            }
            int t = value.indexOf('T');
            if (t != 8) {
                return null;
            }
            LocalDate date = parseDate(value.substring(0, 8));
            String clock = value.substring(t + 1);
            boolean utc = clock.endsWith("Z");
            if (utc) {
                clock = clock.substring(0, clock.length() - 1);
            }
            if (clock.length() < 4) {
                return null;
            }
            LocalTime time = LocalTime.of(Integer.parseInt(clock.substring(0, 2)),
                    Integer.parseInt(clock.substring(2, 4)),
                    clock.length() >= 6 ? Integer.parseInt(clock.substring(4, 6)) : 0);
            // An explicit Z wins over any TZID: the two together are malformed,
            // and the Z is the half that is unambiguous.
            return new Moment(date, time, utc ? "UTC" : zoneOf(property));
        } catch (DateTimeException | NumberFormatException | IndexOutOfBoundsException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(String basic) {
        return LocalDate.of(Integer.parseInt(basic.substring(0, 4)),
                Integer.parseInt(basic.substring(4, 6)),
                Integer.parseInt(basic.substring(6, 8)));
    }

    /**
     * The {@code TZID} as an IANA id, or null.
     *
     * Three shapes reach this. A plain id, which is the easy case. A Windows name
     * from Outlook, which needs the table above. And Mozilla's
     * {@code /mozilla.org/20070129_1/Europe/Berlin}, where the id is the last two
     * segments and the rest is provenance — Thunderbird and anything descended
     * from it writes this, so it is worth the two lines.
     */
    static String zoneOf(Property property) {
        String tzid = property.param("TZID");
        if (tzid == null || tzid.isBlank()) {
            return null;
        }
        String cleaned = tzid.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.startsWith("/")) {
            String[] segments = cleaned.split("/");
            if (segments.length >= 2) {
                cleaned = segments[segments.length - 2] + "/" + segments[segments.length - 1];
            }
        }

        String windows = WINDOWS_ZONES.get(cleaned.toUpperCase());
        if (windows != null) {
            return windows;
        }
        try {
            // Validated rather than trusted, for the same reason
            // ReservationService validates one: an unknown zone makes the whole
            // record undisplayable, and here the honest answer is simply "no
            // zone" rather than a 400 on an import that otherwise worked.
            return ZoneId.of(cleaned).getId();
        } catch (DateTimeException ex) {
            return null;
        }
    }

    /**
     * An iCalendar duration: {@code P4D}, {@code PT90M}, {@code P1DT2H30M},
     * {@code P2W}.
     *
     * Hand-rolled because {@link Duration#parse} rejects the date part
     * ({@code P4D} throws) and {@link java.time.Period#parse} rejects the time
     * part, while iCalendar freely writes both in one value.
     */
    static Duration duration(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim().toUpperCase();
        boolean negative = text.startsWith("-");
        if (negative || text.startsWith("+")) {
            text = text.substring(1);
        }
        if (!text.startsWith("P")) {
            return null;
        }
        text = text.substring(1);

        int t = text.indexOf('T');
        String datePart = t < 0 ? text : text.substring(0, t);
        String timePart = t < 0 ? "" : text.substring(t + 1);

        Duration total = Duration.ZERO;
        try {
            if (!datePart.isEmpty()) {
                if (datePart.endsWith("W")) {
                    total = total.plusDays(7L * Long.parseLong(datePart.substring(0, datePart.length() - 1)));
                } else if (datePart.endsWith("D")) {
                    total = total.plusDays(Long.parseLong(datePart.substring(0, datePart.length() - 1)));
                } else {
                    return null;
                }
            }
            if (!timePart.isEmpty()) {
                total = total.plus(Duration.parse("PT" + timePart));
            }
        } catch (DateTimeException | NumberFormatException ex) {
            return null;
        }
        return negative ? total.negated() : total;
    }

    /** A TEXT value with its escapes undone, or empty. */
    private static String text(Property property) {
        if (property == null) {
            return "";
        }
        return unescape(property.value()).trim();
    }

    /**
     * RFC 5545 escaping. {@code \n} and {@code \N} are both a newline, and the
     * backslash case has to be handled in the same pass — replacing sequentially
     * turns a literal {@code \\n} into a newline.
     */
    static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n', 'N' -> out.append('\n');
                case '\\', ',', ';', ':', '"' -> out.append(next);
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }

    private static String joinNonBlank(String first, String second) {
        if (first.isBlank()) {
            return second;
        }
        if (second.isBlank()) {
            return first;
        }
        return first + "\n\n" + second;
    }

    private static String trimTo(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** A date with an optional time and an optional named zone. */
    private record Moment(LocalDate date, LocalTime time, String zone) {
    }

    /**
     * One content line: {@code NAME;PARAM=VALUE:the value}.
     *
     * Parameters are split off before the colon is looked for, because a value
     * routinely contains colons — a {@code DESCRIPTION} with a URL in it, most
     * obviously — and splitting on the first colon in the whole line is only
     * correct because parameters cannot contain an unquoted one.
     */
    record Property(String name, Map<String, String> params, String value) {

        static Property parse(String line) {
            int colon = indexOfValueColon(line);
            if (colon < 0) {
                return null;
            }
            String head = line.substring(0, colon);
            String value = line.substring(colon + 1);

            String[] pieces = head.split(";");
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 1; i < pieces.length; i++) {
                int equals = pieces[i].indexOf('=');
                if (equals > 0) {
                    params.put(pieces[i].substring(0, equals).trim().toUpperCase(),
                            pieces[i].substring(equals + 1).trim());
                }
            }
            return new Property(pieces[0].trim().toUpperCase(), params, value);
        }

        /** The colon that starts the value, skipping any inside a quoted parameter. */
        private static int indexOfValueColon(String line) {
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                } else if (c == ':' && !quoted) {
                    return i;
                }
            }
            return -1;
        }

        String param(String key) {
            return params.get(key);
        }
    }
}
