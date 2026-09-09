package com.wander.bookingimport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wander.bookingimport.dto.BookingImportResult;
import com.wander.bookingimport.dto.ReservationDraft;
import com.wander.common.FeatureDisabledException;
import com.wander.config.WanderProperties;
import com.wander.trip.TripAccessService;
import com.wander.trip.TripRole;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * Reading a confirmation file into draft bookings.
 *
 * **This service writes nothing.** It is the whole shape of the feature: parse,
 * answer with candidates, and let the client prefill the booking form the
 * reservations page already has so the user saves through the ordinary
 * {@code createReservation}. There is therefore one write path, one set of
 * validation rules, no {@code TripChanges} event to publish, and nothing to roll
 * back — and a misread file costs a correction on screen instead of a wrong row
 * that live sync puts on everybody else's screen within the second. It takes
 * {@code requireRole} anyway, because offering somebody a draft of a booking on a
 * trip they may not edit is still telling them about the trip.
 *
 * Two readers, tried in order, and the order is the confidence ranking.
 * {@link BookingExtractor} goes first: a recognised document's fields are the
 * sender's own values. {@link IcsBookingReader} is the fallback and only for
 * calendar data, because a plain {@code VEVENT} is the one machine-readable
 * confirmation the extractor ignores — measured, not assumed. There is no third
 * reader that scrapes prose out of whatever is left, and that is a decision: the
 * only thing it could honestly produce is a title and a pile of text, and a
 * draft that confidently names the wrong departure time is worse than an honest
 * "nothing here was recognised" beside a form.
 */
@Service
public class BookingImportService {

    private static final Logger log = LoggerFactory.getLogger(BookingImportService.class);

    private static final TripRole[] CAN_EDIT = { TripRole.OWNER, TripRole.EDITOR };

    /**
     * What may be uploaded.
     *
     * An allow-list rather than a sniff, for two reasons. The extension is how
     * the extractor decides what it is looking at, so it has to be pasted into a
     * temp file name — and validating it here is what makes that safe. And a
     * refusal naming the formats is a better answer than handing an unknown file
     * to a Qt process and reporting whatever comes back.
     */
    private static final Set<String> ACCEPTED = Set.of(".eml", ".pdf", ".html", ".htm", ".txt", ".ics", ".pkpass");

    /** A confirmation email with a hundred nested parts is not a confirmation email. */
    private static final int MAX_MIME_PARTS = 50;
    private static final int MAX_MIME_DEPTH = 8;

    private final BookingExtractor extractor;
    private final TripAccessService access;
    private final WanderProperties.BookingImport settings;

    public BookingImportService(BookingExtractor extractor, TripAccessService access,
            WanderProperties properties) {
        this.extractor = extractor;
        this.access = access;
        this.settings = properties.bookingImport();
    }

    @Transactional(readOnly = true)
    public BookingImportResult read(Long userId, Long tripId, byte[] content, String filename) {
        if (!settings.enabled()) {
            throw new FeatureDisabledException("Booking import is turned off on this instance");
        }
        access.requireRole(tripId, userId, CAN_EDIT);

        String extension = KItineraryBookingExtractor.extensionOf(filename);
        if (!ACCEPTED.contains(extension)) {
            throw new IllegalArgumentException(
                    "Cannot read a %s file. Upload the confirmation as .eml, .pdf, .html, .txt, .ics or .pkpass"
                            .formatted(extension.isBlank() ? "file with no extension" : extension));
        }
        if (content.length == 0) {
            throw new IllegalArgumentException("That file is empty");
        }

        List<ReservationDraft> drafts = BookingDraftMapper.map(
                extractor.extract(content, filename, Instant.now()));

        if (drafts.isEmpty()) {
            drafts = fromCalendar(content, extension);
        }

        return new BookingImportResult(drafts, extractor.isAvailable(),
                drafts.isEmpty() ? nothingFound(extension) : "");
    }

    /**
     * The calendar fallback: the file itself when it is a {@code .ics}, or the
     * calendar parts of an email.
     *
     * The second half is the case that actually happens. A hotel does not send
     * you a bare {@code .ics} — it sends an email with one attached, and that
     * whole message is what somebody saves and uploads. Reaching into the MIME
     * tree for {@code text/calendar} is what makes the gap this reader exists for
     * reachable at all; without it the reader would only ever fire on a file
     * almost nobody has.
     */
    private List<ReservationDraft> fromCalendar(byte[] content, String extension) {
        if (".ics".equals(extension)) {
            return IcsBookingReader.read(content);
        }
        if (!".eml".equals(extension)) {
            return List.of();
        }

        List<ReservationDraft> drafts = new ArrayList<>();
        for (byte[] calendar : calendarParts(content)) {
            drafts.addAll(IcsBookingReader.read(calendar));
        }
        return drafts;
    }

    /**
     * Every {@code text/calendar} part in a message.
     *
     * Uses the {@code jakarta.mail} that is already on the classpath — the mail
     * starter brought it for sending a reset link, and a MIME parser is a MIME
     * parser. Nothing here throws upward: an email this cannot walk is a file
     * that yields no bookings, which is a normal answer.
     */
    private List<byte[]> calendarParts(byte[] content) {
        List<byte[]> found = new ArrayList<>();
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(content));
            collectCalendars(message, found, new int[] { 0 }, 0);
        } catch (MessagingException | IOException | RuntimeException ex) {
            log.debug("Could not walk the uploaded message: {}", ex.getMessage());
        }
        return found;
    }

    private void collectCalendars(Part part, List<byte[]> found, int[] visited, int depth)
            throws MessagingException, IOException {
        if (depth > MAX_MIME_DEPTH || visited[0]++ > MAX_MIME_PARTS) {
            return;
        }

        if (part.isMimeType("text/calendar") || part.isMimeType("application/ics")) {
            found.add(part.getInputStream().readAllBytes());
            return;
        }
        // A forwarded confirmation nests the original as message/rfc822, and the
        // attachment is inside that rather than beside it.
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collectCalendars(multipart.getBodyPart(i), found, visited, depth + 1);
            }
        } else if (part.getContent() instanceof Part nested) {
            collectCalendars(nested, found, visited, depth + 1);
        }
    }

    /**
     * Why nothing came back, in terms of what the reader can do about it.
     *
     * The distinction that matters is whether this instance has a document
     * extractor at all: without one, a PDF was never going to work and a calendar
     * attachment still will, and reporting that as "no bookings found" would send
     * somebody looking for a problem in their file. With one, the honest answer is
     * that 349 providers were tried and none matched — which is a real outcome for
     * a small hotel's own template, not a failure to be retried.
     */
    private String nothingFound(String extension) {
        if (!extractor.isAvailable()) {
            return ".ics".equals(extension) || ".eml".equals(extension)
                    ? "No calendar event was found in that file. This instance reads calendar attachments only — "
                            + "a build with the document extractor can also read PDF and HTML confirmations."
                    : "This instance reads calendar attachments only, so a %s cannot be read here. "
                            .formatted(extension) + "Forward the confirmation as .eml if it has a calendar "
                            + "attachment, or add the booking by hand.";
        }
        return "Nothing in that file was recognised as a booking. Not every provider's confirmation can be read — "
                + "add it by hand, and a calendar attachment (.ics) usually works when a PDF does not.";
    }

    /** Whether the feature is on at all, for {@code /api/config}. */
    public boolean enabled() {
        return settings.enabled();
    }

    /** Whether documents can be read, as opposed to calendars only. */
    public boolean extractorAvailable() {
        return settings.enabled() && extractor.isAvailable();
    }

    /** Lowercased, dot-prefixed, for the client's file picker. */
    public static List<String> acceptedExtensions() {
        return ACCEPTED.stream().sorted().map(ext -> ext.toLowerCase(Locale.ROOT)).toList();
    }
}
