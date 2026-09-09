package com.wander.bookingimport;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Turning an uploaded confirmation into schema.org reservation nodes.
 *
 * The seam the tests replace, exactly as {@code GeocoderClient},
 * {@code EnrichmentClient}, {@code WeatherClient} and {@code MailClient} are for
 * their upstreams. This one is not an HTTP call — it is a subprocess — but the
 * argument is identical: the suite must not depend on a 600MB image having been
 * built, and there has to be exactly one thing to hand a canned document to.
 *
 * There is one implementation and no plan for a second. It is an interface for
 * testability and for honest degradation, not because format handling is
 * polymorphic — the only reader that is *not* behind here is
 * {@link IcsBookingReader}, and that is deliberate: it is a pure function over
 * bytes with nothing external to stand in for.
 *
 * **Nothing here throws for a document it cannot read.** An unrecognised file
 * yields an empty list, which is the enrichment rule again — a confirmation this
 * instance cannot parse is a nicety not delivered, not a 502 on somebody's trip.
 * The only failure worth reporting upward is the feature being unavailable, and
 * {@link #isAvailable()} answers that before anybody uploads anything.
 */
public interface BookingExtractor {

    /**
     * Whether this instance can extract documents at all.
     *
     * False on an image built without the extractor, which is a supported
     * configuration: the calendar reader still works, so import remains useful,
     * and {@code BookingImportResult.extractorAvailable} carries the distinction
     * to the client so its "nothing found" message can be true.
     */
    boolean isAvailable();

    /**
     * Reservation nodes found in {@code content}, or an empty list.
     *
     * @param filename     the upload's name — the extension is how the extractor
     *                     decides what it is looking at, so it is load-bearing
     *                     rather than decorative.
     * @param contextDate  when the confirmation was received, used to resolve
     *                     dates written without a year. A ticket saying "14 Oct"
     *                     is next October or last October depending on this, and
     *                     nothing in the document itself says which.
     */
    List<JsonNode> extract(byte[] content, String filename, Instant contextDate);
}
