package com.wander.demo;

import java.time.LocalTime;
import java.util.List;

/**
 * What the demo trip is made of. Data only — {@link DemoSeeder} does the writing.
 *
 * It is a table of constants rather than something fetched at boot, and that is
 * deliberate: seeding must work on an instance with no outbound network, must not
 * spend Nominatim's or Commons' donated capacity every time a container restarts,
 * and must produce the same trip every time so a screenshot in README stays true.
 *
 * The references are real. `osmRef` is the geocoder's own id, so the panel's
 * enrichment works exactly as it does for a place somebody searched for, and the
 * photo credits are the ones Commons gave for those files rather than plausible
 * text typed here — a Commons image is licensed *per image*, and an invented
 * author would be a licence breach dressed up as sample data.
 */
final class DemoContent {

    private DemoContent() {
    }

    /** Day index from the trip's start (0-based), so the trip can be re-dated. */
    record DemoPlace(int day, String name, LocalTime at, double lat, double lon,
            String address, String osmRef, String category, String note,
            String photoUrl, String photoThumbUrl, String photoAuthor,
            String photoLicence, String photoSourceUrl) {
    }

    record DemoNote(int day, String note) {
    }

    /** `shares` null means an equal split; otherwise exact amounts by traveller index. */
    /**
     * {@code amountMinor} and any exact {@code shares} are in
     * {@code sourceCurrency} when there is one, exactly as they are in
     * {@code ExpenseRequest} — the trip's own currency otherwise. The seeder
     * converts, so the yen figure is never written down here and cannot drift
     * away from the rate beside it.
     */
    record DemoExpense(String description, long amountMinor, int dayOffset, int paidBy,
            boolean exact, long[] shares, String sourceCurrency, String fxRate) {

        /** The ordinary case: paid in the trip's own currency, nothing to convert. */
        DemoExpense(String description, long amountMinor, int dayOffset, int paidBy,
                boolean exact, long[] shares) {
            this(description, amountMinor, dayOffset, paidBy, exact, shares, null, null);
        }
    }

    record DemoPacking(String description, Integer assignee, boolean packed) {
    }

    record DemoBooking(String kind, String title, String confirmation, String phone,
            String notes, int startDay, String startTime, String startZone,
            Integer endDay, String endTime, String endZone) {
    }

    static final String TRIP_NAME = "Japan in Autumn";
    static final String TRIP_DESTINATION = "Tokyo & Kyoto, Japan";
    static final String TRIP_CURRENCY = "JPY";
    /** Ten days, starting shortly after boot so the forecast has something to say. */
    static final int STARTS_IN_DAYS = 2;
    static final int LENGTH_DAYS = 10;

    private static final String COMMONS = "https://upload.wikimedia.org/wikipedia/commons/";
    private static final String FILE = "https://commons.wikimedia.org/wiki/File:";

    static final List<DemoPlace> PLACES = List.of(
            new DemoPlace(0, "Sensō-ji", LocalTime.of(9, 30), 35.7134032, 139.7955265,
                    "Sensō-ji, 1, Asakusa 2, Asakusa, Taito, Tokyo, 111-0032, Japan",
                    "way/173154847", "place_of_worship",
                    "Nakamise-dori is packed by ten. Go early, come back for the lanterns at dusk.",
                    COMMONS + "4/43/Sensoji_2023.jpg",
                    COMMONS + "thumb/4/43/Sensoji_2023.jpg/960px-Sensoji_2023.jpg",
                    "Akonnchiroll", "CC0", FILE + "Sensoji_2023.jpg"),
            new DemoPlace(0, "Tokyo Skytree", LocalTime.of(16, 0), 35.7100543, 139.8107141,
                    "Tokyo Skytree, 2, Oshiage 1, Oshiage, Sumida, Tokyo, 131-0045, Japan",
                    "way/288269147", "attraction", null,
                    COMMONS + "6/64/Tokyo_Skytree_2023.jpg",
                    COMMONS + "thumb/6/64/Tokyo_Skytree_2023.jpg/960px-Tokyo_Skytree_2023.jpg",
                    "Akonnchiroll", "CC0", FILE + "Tokyo_Skytree_2023.jpg"),
            new DemoPlace(1, "Meiji Jingu", LocalTime.of(10, 0), 35.6748417, 139.6996266,
                    "Meiji Jingu Main Shrine, 1, Yoyogi Kamizono-chō, Shibuya, Tokyo, 151-8557, Japan",
                    "way/469908925", null, null,
                    COMMONS + "e/ed/Courtyard_of_Meiji_Shrine_20190717.jpg",
                    COMMONS + "thumb/e/ed/Courtyard_of_Meiji_Shrine_20190717.jpg"
                            + "/960px-Courtyard_of_Meiji_Shrine_20190717.jpg",
                    "Tokuzo in Edomura", "CC BY-SA 4.0",
                    FILE + "Courtyard_of_Meiji_Shrine_20190717.jpg"),
            new DemoPlace(1, "Shibuya Scramble Crossing", LocalTime.of(18, 30), 35.6594951, 139.7004982,
                    "Shibuya Scramble Crossing, Jingu-dori Street, Dōgenzaka 2, Shibuya, Tokyo, 150-0043, Japan",
                    "way/1335178864", null,
                    "The view from the Starbucks upstairs is the one everybody photographs.",
                    COMMONS + "d/d1/Shibuya_scramble_crossing_during_Halloween_2023%2C_actually_less_"
                            + "crowded_than_usual%2C_high_police_presence_2.jpg",
                    COMMONS + "thumb/d/d1/Shibuya_scramble_crossing_during_Halloween_2023%2C_actually_"
                            + "less_crowded_than_usual%2C_high_police_presence_2.jpg/960px-Shibuya_"
                            + "scramble_crossing_during_Halloween_2023%2C_actually_less_crowded_than_"
                            + "usual%2C_high_police_presence_2.jpg",
                    "Syced", "CC0",
                    FILE + "Shibuya_scramble_crossing_during_Halloween_2023,_actually_less_crowded_"
                            + "than_usual,_high_police_presence_2.jpg"),
            new DemoPlace(2, "Tsukiji Outer Market", LocalTime.of(8, 0), 35.6653884, 139.7704732,
                    "Tsukiji Outer Market, Shin-ohashi-dori, Tsukiji 2, Chuo, Tokyo, 104-0045, Japan",
                    "way/606978548", "marketplace",
                    "Cash. Most of the good stalls have stopped serving by eleven.",
                    COMMONS + "f/fd/2018_Tsukiji_fish_market.jpg",
                    COMMONS + "thumb/f/fd/2018_Tsukiji_fish_market.jpg/960px-2018_Tsukiji_fish_market.jpg",
                    "Kakidai", "CC BY-SA 4.0", FILE + "2018_Tsukiji_fish_market.jpg"),
            new DemoPlace(3, "Fushimi Inari-taisha", LocalTime.of(7, 30), 34.9675192, 135.7797101,
                    "Fushimi Inari-taisha, Fushimi Ward, Kyoto, Kyoto Prefecture, 612-0882, Japan",
                    "way/96291583", "religious",
                    "The full circuit is about two hours. Almost nobody goes past the first gates.",
                    COMMONS + "d/de/Fushimiinari-taisha%2C_naihaiden-1.jpg",
                    COMMONS + "thumb/d/de/Fushimiinari-taisha%2C_naihaiden-1.jpg"
                            + "/960px-Fushimiinari-taisha%2C_naihaiden-1.jpg",
                    "Saigen Jiro", "CC0", FILE + "Fushimiinari-taisha,_naihaiden-1.jpg"),
            new DemoPlace(4, "Kinkaku-ji", LocalTime.of(10, 0), 35.0395293, 135.7295373,
                    "Kinkaku-ji, Kinkakuji-chō, Kita Ward, Kyoto, Kyoto Prefecture, 603-8361, Japan",
                    "way/98115917", "place_of_worship", null,
                    COMMONS + "d/de/Water_reflection_of_Kinkaku-ji_Temple_a_sunny_day%2C_Kyoto%2C_Japan.jpg",
                    COMMONS + "thumb/d/de/Water_reflection_of_Kinkaku-ji_Temple_a_sunny_day%2C_Kyoto%2C_"
                            + "Japan.jpg/960px-Water_reflection_of_Kinkaku-ji_Temple_a_sunny_day%2C_"
                            + "Kyoto%2C_Japan.jpg",
                    "Basile Morin", "CC BY-SA 4.0",
                    FILE + "Water_reflection_of_Kinkaku-ji_Temple_a_sunny_day,_Kyoto,_Japan.jpg"),
            new DemoPlace(4, "Nijō Castle", LocalTime.of(14, 30), 35.0140076, 135.7485369,
                    "Nijō Castle, Matsuyamachi street, Nakagyo Ward, Kyoto, Kyoto Prefecture, Japan",
                    "way/57111281", "attraction", null, null, null, null, null, null),
            new DemoPlace(6, "Arashiyama Bamboo Grove", LocalTime.of(8, 30), 35.0167419, 135.6711482,
                    "Arashiyama Bamboo Grove, Ukyo Ward, Kyoto, Kyoto Prefecture, Japan",
                    "relation/17656638", "attraction",
                    "Before eight or it is a queue, not a grove.", null, null, null, null, null),
            new DemoPlace(8, "Nara Park", LocalTime.of(10, 0), 34.6829008, 135.8545975,
                    "Nara Park, Nara, Nara Prefecture, Japan",
                    "way/456314269", "park", null, null, null, null, null, null));

    static final List<DemoNote> NOTES = List.of(
            new DemoNote(0, "Land 06:20, drop bags at the hotel before check-in. "
                    + "Suica cards at the airport station."),
            new DemoNote(3, "Shinkansen to Kyoto in the morning — reserved seats, car 7. "
                    + "Fushimi Inari after dark is quieter."),
            new DemoNote(8, "Buy the deer crackers. Do not carry the whole packet in the open."));

    // Yen has no minor unit at all, which is why amounts are stored as a BIGINT
    // count of minor units rather than anything with a decimal point in it.
    static final List<DemoExpense> EXPENSES = List.of(
            // The one paid in another currency, and the only realistic place
            // for it: the flights were bought from home, months before anybody
            // was anywhere near a yen. £1,028.00 at 208.63 yen to the pound.
            //
            // The rate is **marked as one a person entered**, and that is the
            // honest encoding rather than a shortcut. A looked-up rate belongs
            // to a published day, and this trip is re-dated on every boot so the
            // day moves — stamping a market quote onto whatever date the seeder
            // happens to produce would be the `places.category` mistake in
            // another costume: a number presented as fact that came from
            // something that does not know. It also keeps the seeder true to
            // its own rule of needing no outbound network.
            new DemoExpense("Flights, London → Haneda", 102800, -80, 0, false, null,
                    "GBP", "208.63"),
            new DemoExpense("JR Pass, 7 days", 50000, -11, 1, false, null),
            new DemoExpense("Hotel, Asakusa (3 nights)", 58200, 0, 0, false, null),
            new DemoExpense("Sushi at Tsukiji", 9400, 2, 1, false, null),
            new DemoExpense("Ryokan, Kyoto (4 nights)", 96000, 3, 0, false, null),
            // An exact split: one of them had the tasting menu and the other did not.
            new DemoExpense("Kaiseki dinner, Gion", 31000, 5, 0, true, new long[] { 19000, 12000 }),
            // An odd amount, on purpose: an equal split of it cannot be halved,
            // so ExpenseSplitter spreads the last yen to the lowest user id and
            // the demo shows 1561/1560 rather than a suspiciously tidy pair.
            // Every other split here divides evenly, so without this one the
            // rule that keeps a split summing to its total is invisible.
            new DemoExpense("Nara day trip, trains", 3121, 8, 1, false, null));

    /** From traveller 1 to traveller 0, mid-trip. A payment is not a cost. */
    static final long PAYMENT_MINOR = 60000;
    static final int PAYMENT_DAY = 6;

    static final List<DemoPacking> PACKING = List.of(
            new DemoPacking("Passports + JR Pass vouchers", 0, true),
            new DemoPacking("Universal power adapter", 0, false),
            new DemoPacking("Pocket wifi router", 1, true),
            new DemoPacking("Painkillers + plasters", 1, false),
            // A null assignee is the shared pile, and that null is meaningful.
            new DemoPacking("Walking shoes", null, false),
            new DemoPacking("Cash — ¥40,000 to start", null, true),
            new DemoPacking("Umbrella (September is wet)", null, false));

    static final List<DemoBooking> BOOKINGS = List.of(
            // The one that crosses zones, which is the whole reason a booking
            // stores an instant and an IANA id rather than a local time.
            new DemoBooking("FLIGHT", "BA005 London Heathrow → Tokyo Haneda", "BA-7QK4ZP",
                    "+44 20 7949 3000", "Terminal 5, bag drop two hours before.",
                    -1, "13:40", "Europe/London", 0, "09:35", "Asia/Tokyo"),
            new DemoBooking("HOTEL", "Asakusa View Hotel", "AVH-882410", "+81 3-3847-1111",
                    "Check-in 15:00. Luggage can be left from 08:00.",
                    0, "15:00", "Asia/Tokyo", null, null, null),
            new DemoBooking("TRAIN", "Nozomi 231, Tokyo → Kyoto", "JR-4471", "",
                    "Car 7, seats 11A/11B. Reserved.",
                    3, "09:03", "Asia/Tokyo", 3, "11:15", "Asia/Tokyo"),
            new DemoBooking("HOTEL", "Ryokan Kikokusō, Kyoto", "KKS-4471", "+81 75-361-0000",
                    "Dinner served 18:30 in-room. Private bath available on request.",
                    3, "16:00", "Asia/Tokyo", null, null, null),
            new DemoBooking("FLIGHT", "BA006 Tokyo Haneda → London Heathrow", "BA-7QK4ZP",
                    "+44 20 7949 3000", "", 9, "11:35", "Asia/Tokyo", 9, "16:05", "Europe/London"));
}
