package com.wander.bookingimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wander.bookingimport.dto.DraftSource;
import com.wander.bookingimport.dto.ReservationDraft;
import com.wander.reservation.ReservationKind;

/**
 * The calendar reader, which is here for the same reason {@code money.spec.ts}
 * and {@code zones.spec.ts} are: **every failure in this file is silent.**
 *
 * A misread zone throws nothing, logs nothing and fails no request — it produces
 * a booking an hour out. An exclusive end date read literally produces a hotel
 * stay that checks out a day late. Both look completely correct on screen,
 * because the number shown is the number that was parsed. An integration test
 * over HTTP would assert that a draft came back and would pass through either
 * bug, so the assertions that matter live here.
 */
class IcsBookingReaderTest {

    private static List<ReservationDraft> read(String calendar) {
        return IcsBookingReader.read(calendar.getBytes(StandardCharsets.UTF_8));
    }

    private static String event(String... lines) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\nBEGIN:VEVENT\r\n"
                + String.join("\r\n", lines) + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    @Test
    void readsTheWallClockAndTheNamedZone() {
        List<ReservationDraft> drafts = read(event(
                "UID:1@test",
                "DTSTART;TZID=Asia/Tokyo:20261015T150000",
                "DTEND;TZID=Asia/Tokyo:20261019T110000",
                "SUMMARY:Hotel Granbell Kyoto"));

        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.startDate()).isEqualTo(LocalDate.of(2026, 10, 15));
            assertThat(draft.startTime()).isEqualTo(LocalTime.of(15, 0));
            assertThat(draft.startZone()).isEqualTo("Asia/Tokyo");
            assertThat(draft.endDate()).isEqualTo(LocalDate.of(2026, 10, 19));
            assertThat(draft.endTime()).isEqualTo(LocalTime.of(11, 0));
            assertThat(draft.endZone()).isEqualTo("Asia/Tokyo");
            assertThat(draft.title()).isEqualTo("Hotel Granbell Kyoto");
            assertThat(draft.source()).isEqualTo(DraftSource.CALENDAR);
        });
    }

    /**
     * The kind is never guessed. "Hotel" is in the summary and the answer is still
     * OTHER, because a summary is prose somebody wrote for a human and reading a
     * category out of it is the {@code places.category} mistake — a decorative
     * label promoted to a load-bearing one.
     */
    @Test
    void neverInfersTheKindFromTheSummary() {
        assertThat(read(event("DTSTART;TZID=Asia/Tokyo:20261015T150000",
                "SUMMARY:Hotel Granbell Kyoto — flight NH106 connection")))
                .singleElement()
                .extracting(ReservationDraft::kind)
                .isEqualTo(ReservationKind.OTHER);
    }

    /**
     * A date-only DTEND is **exclusive** per RFC 5545, so a stay written as the
     * 15th to the 19th ends on the 18th. This is the assertion most worth having
     * in the whole file: get it wrong and every imported hotel stay is a day
     * long, in a way nobody would think to check.
     */
    @Test
    void treatsAnAllDayEndAsExclusive() {
        assertThat(read(event("DTSTART;VALUE=DATE:20261015",
                "DTEND;VALUE=DATE:20261019",
                "SUMMARY:Apartment")))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.startDate()).isEqualTo(LocalDate.of(2026, 10, 15));
                    assertThat(draft.startTime()).isNull();
                    assertThat(draft.endDate()).isEqualTo(LocalDate.of(2026, 10, 18));
                    assertThat(draft.endTime()).isNull();
                });
    }

    /** A one-day all-day event must not end the day before it starts. */
    @Test
    void keepsASingleAllDayEventOnItsOwnDay() {
        assertThat(read(event("DTSTART;VALUE=DATE:20261015",
                "DTEND;VALUE=DATE:20261016",
                "SUMMARY:Museum")))
                .singleElement()
                .extracting(ReservationDraft::endDate)
                .isEqualTo(LocalDate.of(2026, 10, 15));
    }

    /** A timed DTEND is an instant and gets no exclusive-end adjustment. */
    @Test
    void doesNotAdjustATimedEnd() {
        assertThat(read(event("DTSTART;TZID=Europe/Paris:20261015T190000",
                "DTEND;TZID=Europe/Paris:20261016T010000",
                "SUMMARY:Dinner")))
                .singleElement()
                .extracting(ReservationDraft::endDate)
                .isEqualTo(LocalDate.of(2026, 10, 16));
    }

    /**
     * A trailing Z is UTC, and the zone is reported as "UTC" rather than "Z" —
     * both are valid to ZoneId, but the browser formats with Intl and Intl does
     * not accept "Z" as a time zone. The honest-looking one is the one that
     * blanks the time on the page.
     */
    @Test
    void readsAUtcInstantAsUtc() {
        assertThat(read(event("DTSTART:20261015T060000Z", "SUMMARY:Transfer")))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.startTime()).isEqualTo(LocalTime.of(6, 0));
                    assertThat(draft.startZone()).isEqualTo("UTC");
                });
    }

    /** Floating time: a wall clock with no zone. Left null — the server is not where the reader is. */
    @Test
    void leavesAFloatingTimeWithoutAZone() {
        assertThat(read(event("DTSTART:20261015T090000", "SUMMARY:Pickup")))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.startTime()).isEqualTo(LocalTime.of(9, 0));
                    assertThat(draft.startZone()).isNull();
                });
    }

    /** Outlook writes Windows zone names, which ZoneId rejects outright. */
    @Test
    void mapsAWindowsZoneName() {
        assertThat(read(event("DTSTART;TZID=\"W. Europe Standard Time\":20261015T090000",
                "SUMMARY:Meeting")))
                .singleElement()
                .extracting(ReservationDraft::startZone)
                .isEqualTo("Europe/Berlin");
    }

    /** Thunderbird prefixes the id with its own provenance. */
    @Test
    void readsAMozillaPrefixedZone() {
        assertThat(read(event("DTSTART;TZID=/mozilla.org/20070129_1/Europe/Berlin:20261015T090000",
                "SUMMARY:Meeting")))
                .singleElement()
                .extracting(ReservationDraft::startZone)
                .isEqualTo("Europe/Berlin");
    }

    /**
     * An unrecognised zone is null, not a substitute. The time is still right as
     * a wall clock, and the form asks which zone it is in — inventing one would
     * silently move the booking.
     */
    @Test
    void leavesAnUnknownZoneNull() {
        assertThat(read(event("DTSTART;TZID=Mars/Olympus:20261015T090000", "SUMMARY:Launch")))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.startTime()).isEqualTo(LocalTime.of(9, 0));
                    assertThat(draft.startZone()).isNull();
                });
    }

    /**
     * Folded lines. RFC 5545 wraps at 75 octets with a leading space, so a full
     * street address routinely arrives in pieces — parse before unfolding and you
     * get a value that stops mid-word plus two junk properties.
     */
    @Test
    void unfoldsWrappedLines() {
        assertThat(read(event("DTSTART;VALUE=DATE:20261015",
                "SUMMARY:Hotel Granbell Kyoto",
                "LOCATION:684 Sanchome\\, Gionmachi",
                "  Minamigawa\\, Higashiyama-ku\\, Kyoto")))
                .singleElement()
                .extracting(ReservationDraft::notes)
                .isEqualTo("684 Sanchome, Gionmachi Minamigawa, Higashiyama-ku, Kyoto");
    }

    /** A value can contain colons — a URL in a description is the usual case. */
    @Test
    void keepsColonsInsideAValue() {
        assertThat(read(event("DTSTART;VALUE=DATE:20261015",
                "SUMMARY:Tour",
                "DESCRIPTION:Details at https://example.org/b/1")))
                .singleElement()
                .extracting(ReservationDraft::notes)
                .isEqualTo("Details at https://example.org/b/1");
    }

    /** Escapes are undone in one pass, or a literal backslash-n becomes a newline. */
    @Test
    void undoesTextEscapes() {
        assertThat(read(event("DTSTART;VALUE=DATE:20261015",
                "SUMMARY:Tour",
                "DESCRIPTION:Ref 88213-ABQ\\nDesk: gate 4\\; ask for Ana")))
                .singleElement()
                .extracting(ReservationDraft::notes)
                .isEqualTo("Ref 88213-ABQ\nDesk: gate 4; ask for Ana");
    }

    /**
     * The confirmation code is left in the notes rather than fished out of them.
     * It is plainly visible either way, and guessing which token is the reference
     * is how the field gets confidently filled in with a flight number.
     */
    @Test
    void doesNotGuessAConfirmationCode() {
        assertThat(read(event("DTSTART;VALUE=DATE:20261015",
                "SUMMARY:Hotel",
                "DESCRIPTION:Confirmation number 88213-ABQ")))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.confirmation()).isNull();
                    assertThat(draft.notes()).contains("88213-ABQ");
                });
    }

    /** A return trip is two events, and losing the second would be the first complaint. */
    @Test
    void readsEveryEvent() {
        String calendar = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                DTSTART;TZID=Europe/London:20261014T130500
                SUMMARY:Outbound
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;TZID=Asia/Tokyo:20261028T101500
                SUMMARY:Return
                END:VEVENT
                END:VCALENDAR
                """;
        assertThat(read(calendar)).extracting(ReservationDraft::title)
                .containsExactly("Outbound", "Return");
    }

    /** An event with no start is not a booking. */
    @Test
    void skipsAnEventWithNoStart() {
        assertThat(read(event("SUMMARY:Something", "DESCRIPTION:No dates here"))).isEmpty();
    }

    @Test
    void fallsBackToTheLocationWhenThereIsNoSummary() {
        assertThat(read(event("DTSTART;VALUE=DATE:20261015", "LOCATION:Gate 4, Terminal 2")))
                .singleElement()
                .extracting(ReservationDraft::title)
                .isEqualTo("Gate 4, Terminal 2");
    }

    /** DURATION instead of DTEND, which Google Calendar exports use. */
    @Test
    void appliesADurationWhenThereIsNoEnd() {
        assertThat(read(event("DTSTART;TZID=Europe/Paris:20261015T190000",
                "DURATION:PT1H30M",
                "SUMMARY:Dinner")))
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.endDate()).isEqualTo(LocalDate.of(2026, 10, 15));
                    assertThat(draft.endTime()).isEqualTo(LocalTime.of(20, 30));
                });
    }

    /** An all-day duration is exclusive too: P4D from the 15th ends on the 18th. */
    @Test
    void appliesAnAllDayDurationExclusively() {
        assertThat(read(event("DTSTART;VALUE=DATE:20261015", "DURATION:P4D", "SUMMARY:Apartment")))
                .singleElement()
                .extracting(ReservationDraft::endDate)
                .isEqualTo(LocalDate.of(2026, 10, 18));
    }

    @Test
    void parsesTheDurationFormsThatMatter() {
        assertThat(IcsBookingReader.duration("PT90M").toMinutes()).isEqualTo(90);
        assertThat(IcsBookingReader.duration("P2W").toDays()).isEqualTo(14);
        assertThat(IcsBookingReader.duration("P1DT2H").toHours()).isEqualTo(26);
        assertThat(IcsBookingReader.duration("nonsense")).isNull();
        assertThat(IcsBookingReader.duration("")).isNull();
    }

    @Test
    void recognisesACalendarWithoutParsingIt() {
        assertThat(IcsBookingReader.looksLikeCalendar("BEGIN:VCALENDAR\r\n".getBytes(StandardCharsets.UTF_8)))
                .isTrue();
        assertThat(IcsBookingReader.looksLikeCalendar("%PDF-1.4".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    /** Garbage in is an empty list, never an exception: the upload came from a stranger's mail. */
    @Test
    void survivesRubbish() {
        assertThat(IcsBookingReader.read(new byte[] { 0, 1, 2, 3 })).isEmpty();
        assertThat(read("BEGIN:VEVENT\r\nDTSTART;TZID=:\r\nEND:VEVENT")).isEmpty();
        assertThat(read("")).isEmpty();
    }
}
