/*
   The ANA e-ticket extractor, for the variant that prints a four-digit year.

   SPDX-FileCopyrightText: 2024 Volker Krause <vkrause@kde.org>
   SPDX-FileCopyrightText: 2026 the wander authors
   SPDX-License-Identifier: LGPL-2.0-or-later

   A near-verbatim copy of KItinerary's own ana.js, kept deliberately close to
   it so the diff is readable and so this file can be dropped the day upstream
   takes the fix. Two changes, and the first is the whole reason it exists:

    - **The year is four digits, `ddMMMyyyy`.** Upstream matches `\d{2}[A-Z]{3}\d{2}`
      and parses `ddMMMyyhhmm`, which is what a Japan-issued ticket prints. An
      ANA SKY WEB India ticket prints `31OCT2026`, no leg matches, and the script
      returns an empty array — an unreadable confirmation with nothing logged.
    - **The passenger label may carry a fullwidth colon and the name may be on
      the next line.** `PASSENGER：` (U+FF1A) with `AMBASTHA/APRAMEYA MSTR`
      below it, where upstream wants `PASSENGER :` and the name on the same line.
      That one is cosmetic — a missing name costs a name — but it is in the same
      document and fixing it here costs one character class.

   The year is matched as **four digits only, never two-or-four**, and that is
   load-bearing rather than lazy: it makes this extractor and the built-in one
   disjoint, so exactly one of them ever matches a given ticket. Accept both and
   a Japan-issued ticket matches twice and imports every leg twice — which looks
   like a wander bug and is not one.
*/

function extractPdfEticket(pdf, node, barcode) {
    const text = pdf.pages[barcode.location].text;
    const pnr = barcode.content.match(/(\d{13})\/([A-Z0-9]{6})/);
    // Upstream indexes pnr unguarded. It cannot be null there, because the
    // filter that selected this script already matched the same shape — but the
    // filter lives in a separate file, so this is one null check against the two
    // drifting apart.
    if (!pnr)
        return [];

    const pas = text.match(/(?:PASSENGER *[:：]|NAME) *\n? *(\S.*\S)\/(\S.*\S)\n/);

    let reservations = [];
    let idx = 0;
    while (true) {
        const leg = text.substr(idx).match(/\] (\S.*?\S) (?: +(\S+)  )? +([A-Z-0-9]{2})(\d{1,4}) +(\d{2}[A-Z]{3}\d{4}) +[A-Z]{3} +(\d{4}).*\n.*\n+ *(\S.*?\S) (?: +(\S+)  )? +(\d{2}[A-K])? +(\d{2}[A-Z]{3}\d{4}) +[A-Z]{3} +(\d{4}) *(\S.*\S)/);
        if (!leg)
            break;
        idx += leg.index + leg[0].length;
        let res = JsonLd.newFlightReservation();
        res.reservedTicket.ticketNumber = pnr[1];
        res.reservationNumber = pnr[2];
        res.reservationFor.departureAirport.name = leg[1];
        res.reservationFor.departureTerminal = leg[2];
        res.reservationFor.airline.iataCode = leg[3];
        res.reservationFor.flightNumber = leg[4];
        res.reservationFor.departureTime = JsonLd.toDateTime(leg[5] + leg[6], "ddMMMyyyyhhmm", "en");
        res.reservationFor.arrivalAirport.name = leg[7];
        res.reservationFor.arrivalTerminal = leg[8];
        res.airplaneSeat = leg[9];
        res.reservationFor.arrivalTime = JsonLd.toDateTime(leg[10] + leg[11], "ddMMMyyyyhhmm", "en");
        res.reservationFor.airline.name = leg[12];
        // Guarded, unlike upstream: a layout this script parses but whose
        // passenger block it does not is a booking with no name on it, which is
        // a better answer than a script exception and no booking at all.
        if (pas) {
            res.underName.familyName = pas[1];
            res.underName.givenName = pas[2];
        }
        reservations.push(res);
    }
    return reservations;
}
