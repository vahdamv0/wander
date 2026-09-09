package com.wander.bookingimport.dto;

/**
 * Which of the two readers produced a draft.
 *
 * It travels to the client because the two are not equally confident and the
 * difference is worth showing: {@code EXTRACTOR} means a document was
 * *recognised* — a vendor's own format, or schema.org markup the sender put
 * there on purpose — so its fields are the sender's own values. {@code CALENDAR}
 * means a plain calendar event, where the only structured facts are the times
 * and everything else is a summary line somebody wrote for a human.
 */
public enum DraftSource {
    /** KItinerary: a vendor extractor, schema.org JSON-LD or microdata, a pass or a barcode. */
    EXTRACTOR,
    /** A plain iCalendar VEVENT, which the extractor ignores. See IcsBookingReader. */
    CALENDAR
}
