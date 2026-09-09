package com.wander.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/*
 * Instance configuration. Every field is env-var settable so an operator can run
 * the container without editing a file — see compose.yaml.
 */
@ConfigurationProperties("wander")
public record WanderProperties(

        /* Reported in the geocoder User-Agent, which is how a blocked instance gets identified. */
        @DefaultValue("dev") String version,

        /*
         * The commit this image was built from, baked in by CI as a build
         * argument. Blank for a build made any other way, and blank is shown as
         * nothing rather than as "unknown".
         *
         * It is the half of the version that actually moves: `version` is a
         * number somebody edits once a release, while a self-hosted instance is
         * updated by pulling `latest` — so "which build am I on" can only be
         * answered by the commit.
         */
        @DefaultValue("") String buildRef,

        /*
         * Where the source for *this* instance lives, linked from the sign-in
         * page and the account menu.
         *
         * It is a setting rather than a constant, and the licence is the reason.
         * wander is AGPL-3.0-or-later, and section 13 says an instance offered
         * to others over a network owes **its users** the source of the version
         * they are actually talking to. Compiling upstream's URL in would
         * satisfy nobody: the first operator to change a line would be shipping
         * a link to code their instance is not running, which is worse than no
         * link at all because it looks like compliance. So: modify wander, point
         * this at your fork.
         *
         * Blank hides the link, the same way a blank {@code buildRef} draws
         * nothing. That is for a private instance nobody else is offered — on a
         * public one, blanking it is most likely a licence violation rather than
         * a preference, and README says so.
         *
         * No {@code @DefaultValue} here, deliberately: {@code application.yml}
         * always binds this key, so a literal in this annotation could never
         * fire — it was a second copy of the URL that a rename would silently
         * leave stale, with nothing failing to say so. The one copy lives in
         * {@code application.yml}, beside the environment variable that
         * overrides it.
         */
        String sourceUrl,

        /*
         * Self-signup, and it is **off by default** because the default has to be
         * the safe answer for the deployment that is exposed to the internet: an
         * open instance hands this machine's Nominatim, Commons and Open-Meteo
         * budget — all of them somebody else's donated capacity — to anybody who
         * finds the hostname.
         *
         * Off does not mean closed. A valid invitation link still admits its
         * holder, so the link -> register -> accept journey works exactly as it
         * did; see {@code UserAccountService.register}. Without that, "off" would
         * have meant an instance nobody but the first-boot admin could ever join,
         * because nothing here sends mail and there is no other way to make an
         * account.
         */
        @DefaultValue("false") boolean registrationEnabled,

        /*
         * The currency a new trip gets when its creator does not choose one. An
         * operator setting rather than a compiled-in constant for the same reason
         * the tile URL is: this project has no idea where its instances are.
         */
        @DefaultValue("EUR") String currency,

        @DefaultValue Admin admin,

        @DefaultValue Demo demo,

        @DefaultValue Login login,

        @DefaultValue Mail mail,

        @DefaultValue Quota quota,

        @DefaultValue Csp csp,

        @DefaultValue Geocoding geocoding,

        @DefaultValue Enrichment enrichment,

        @DefaultValue Weather weather,

        @DefaultValue BookingImport bookingImport,

        @DefaultValue MapTiles map) {

    /*
     * First-boot admin. Both blank means the account is still created, with a
     * generated password printed once to the log — the operator never has to
     * hand-edit the database to get in.
     */

    /*
     * A worked example trip, and a read-only account to look at it with.
     *
     * **Off by default**, because this writes rows: it is for a public demo
     * instance, not for somebody's real one. When it is on, the seeder owns
     * everything it creates and touches nothing else — see {@code DemoSeeder}.
     *
     * The password is *meant* to be published; the login page prints it. That is
     * only defensible because the account is a **viewer** on the demo trip and
     * nothing else, so the worst a visitor can do is read a trip that exists to
     * be read.
     */
    public record Demo(@DefaultValue("false") boolean enabled, @DefaultValue("demo@wander.local") String email,
                       @DefaultValue("demo-traveller") String password, @DefaultValue("45") int sweepMinutes) {
    }

    public record Admin(@DefaultValue("") String email, @DefaultValue("") String password) {
    }

    /*
     * How hard a password may be guessed. See {@code LoginThrottle} for why there
     * are two limits rather than one.
     *
     * The defaults are meant to be invisible to anybody using the application and
     * ruinous to a word list: ten wrong passwords for one account in a quarter of
     * an hour is already a bad afternoon, and forty from one address is several
     * people all having one.
     */
    public record Login(@DefaultValue("10") int maxFailuresPerEmail,
            /*
             * Looser than the per-email limit on purpose: a household, an office
             * or a mobile network arrives as one address, and several people
             * signing in from it must not add up to a lockout.
             */
                        @DefaultValue("40") int maxFailuresPerAddress,
            /* How long a run of failures is remembered. Minutes, and it is not a lockout. */
                        @DefaultValue("15") int windowMinutes,
            /*
             * How many accounts one address may create inside the window.
             *
             * Counted whether the registration succeeds or not, because both
             * cost a bcrypt round — see {@code LoginThrottle}. Twenty is meant
             * to be out of the way of a household signing everybody up on the
             * same evening and firmly in the way of a script.
             *
             * **The browser suite creates every one of its accounts by
             * registering, all from one address**, so an instance you point
             * `npm run e2e` at needs this raised — the same footnote that
             * already applies to `registration-enabled`.
             */
                        @DefaultValue("20") int maxRegistrationsPerAddress,
            /* Cap on the counter map. The keys are attacker-supplied, so it is bounded. */
                        @DefaultValue("10000") int trackedKeys) {
    }

    /*
     * How much of this instance's *upstream* budget one signed-in person may
     * spend — see {@code UpstreamQuota} for why this is per user id rather than
     * per address, and why {@code RateGate} is not a substitute.
     *
     * The limits are per window and deliberately generous: they are drawn to be
     * invisible to somebody planning a trip and ruinous to a script pointed at
     * the donated services this instance depends on.
     */
    /*
     * Sending mail, which this application spent its whole life not doing.
     *
     * "Nothing here sends mail" was load-bearing for two features: invitations
     * are links because a link needs no mail, and a password reset was an
     * administrator minting one by hand. Neither is being taken away — a link is
     * still the mechanism, and the admin can still mint one. What this adds is a
     * *delivery* method for the one case where the person cannot be reached any
     * other way, because they are by definition the person who cannot sign in.
     *
     * **Off by default**, like the demo trip and for the same reason: it needs
     * an SMTP relay somebody has to sign up for, and the right default is the one
     * that works on a laptop with no outbound network. With it off, the "Forgot
     * password?" link is not offered and the endpoint is not there — the
     * administrator's minted link remains the only route, exactly as before.
     *
     * The credentials themselves are Spring's own `spring.mail.*`, not repeated
     * here: every provider speaks SMTP, so there is one shape to configure and no
     * vendor in the code. See .env.example.
     */
    public record Mail(@DefaultValue("false") boolean enabled,
            /*
             * The envelope sender. Deliverability lives or dies on this matching
             * a domain the relay is authorised for — mail claiming to be from a
             * domain nobody signed for is what spam filters are built to catch,
             * and a password reset in a spam folder is indistinguishable from one
             * that was never sent.
             */
                       @DefaultValue("") String from,
            /* The human name beside it. Blank sends the address alone. */
                       @DefaultValue("wander") String fromName,
            /*
             * Where a reset link points. Blank derives it from the request, which
             * is right behind a proxy that sets the forwarded headers — the same
             * headers `LoginThrottle` already depends on. Set it explicitly if
             * the links come out wrong: unlike a bad throttle, this one is
             * visible, because the link in the mail simply does not work.
             */
                       @DefaultValue("") String baseUrl,
            /*
             * How long a self-requested link lives. Minutes rather than the
             * admin path's days, and much shorter: an emailed link is acted on
             * immediately or not at all, and it is sitting in a mailbox that may
             * itself be the thing that was compromised.
             */
                       @DefaultValue("60") int resetExpiresMinutes,
            /*
             * How many links one address may ask for in a login window. Counted
             * as *attempts*, like registration: each one sends mail on somebody
             * else's relay and lands in somebody's inbox, so an unmetered version
             * of this endpoint is a way to use this instance to post junk at a
             * third party.
             */
                       @DefaultValue("5") int maxRequestsPerAddress) {
    }

    public record Quota(
            /* Typeahead, so the loosest: roughly one search every two seconds, sustained. */
            @DefaultValue("120") int searchesPerWindow,
            /* Opening a place, which on first sight may walk four services. */
            @DefaultValue("60") int enrichmentsPerWindow,
            /* One request covers a whole trip, so this is really per page view. */
            @DefaultValue("60") int forecastsPerWindow, @DefaultValue("5") int windowMinutes,
            @DefaultValue("10000") int trackedKeys) {
    }

    /*
     * The Content-Security-Policy header.
     *
     * Not a fixed string, because the hosts it has to allow are the operator's
     * choice: the map style, its glyphs and sprites, and the tiles all come from
     * whatever {@code map} points at, and a hardcoded policy would blank the map
     * for the first self-hoster to run their own tile server. It is assembled
     * from {@code MapTiles} at startup instead — see {@code ContentSecurityPolicy}.
     */
    public record Csp(@DefaultValue("true") boolean enabled,
            /*
             * Report rather than refuse. The escape hatch for an instance whose
             * map draws from somewhere this policy did not anticipate: turn it
             * on, load the page, read the console, and send the host that the
             * violations name. A blank map with nothing in the log is the
             * failure mode this exists to avoid — see the MapLibre worker note
             * in CLAUDE.md for how quiet that gets.
             */
                      @DefaultValue("false") boolean reportOnly,
            /*
             * Extra sources appended to `connect-src`, `img-src` and `font-src`,
             * space-separated. For an instance whose tiles, fonts or photographs
             * live somewhere this does not work out on its own.
             */
                      @DefaultValue("") String extraSources) {
    }

    /*
     * Place search. Defaults point at the public Nominatim instance, whose usage
     * policy this project honours: an identifying User-Agent, at most one
     * request a second across the whole instance, and results cached so the same
     * query is not asked twice.
     *
     * An operator with real traffic should point {@code baseUrl} at their own
     * Nominatim and raise the rate — or set {@code enabled: false}, which is the
     * right setting for an instance with no outbound network access.
     */
    public record Geocoding(@DefaultValue("true") boolean enabled,
                            @DefaultValue("https://nominatim.openstreetmap.org") String baseUrl,
            /* Sent in the User-Agent so the operator is contactable before being blocked. */
                            @DefaultValue("") String contactEmail,
            /*
             * Fallback for callers that send no Accept-Language. Not empty by
             * design: a geocoder with no language preference answers in the
             * place's own language, and this application's own UI is English.
             */
                            @DefaultValue("en") String language,
            /* Minimum gap between two outbound searches, in milliseconds. */
                            @DefaultValue("1000") long minIntervalMillis,
            /* How long a caller waits for the gate before getting a 429 instead. */
                            @DefaultValue("2000") long maxWaitMillis,
            /* How long a result stays cached. */
                            @DefaultValue("600") long cacheSeconds,
            /* How many distinct queries to remember. */
                            @DefaultValue("500") int cacheSize) {
    }

    /*
     * Descriptions, facts, hours and photos for a place, from OpenStreetMap,
     * Wikidata, Wikipedia and Wikimedia Commons.
     *
     * Off means the popup shows nothing but the place itself, which is also what
     * an instance with no outbound network gets. Everything fetched is stored, so
     * the rate here is about first sight of a place rather than steady traffic.
     */
    public record Enrichment(@DefaultValue("true") boolean enabled,
            /* Wikipedia and Commons hosts. Language is chosen per request from the caller's. */
                             @DefaultValue("https://www.wikidata.org") String wikidataUrl,
                             @DefaultValue("https://commons.wikimedia.org") String commonsUrl,
            /* How long a stored enrichment stands before it is fetched again. */
                             @DefaultValue("30") int cacheDays,
            /* How many photo candidates to offer. More is a longer strip nobody scrolls. */
                             @DefaultValue("4") int photoCount,
            /* Gap between outbound Wikimedia calls, and how long a caller waits for the gate. */
                             @DefaultValue("200") long minIntervalMillis, @DefaultValue("4000") long maxWaitMillis) {
    }

    /*
     * Reading a booking out of a confirmation file.
     *
     * On by default and useful either way, which is why this is one switch rather
     * than two. The heavy half — KItinerary, which carries 349 provider formats
     * plus schema.org markup, Apple Wallet passes and boarding-pass barcodes — is
     * a binary in the image, and an image built without it simply reports itself
     * as calendar-only: the iCalendar reader is pure Java and always there, so the
     * feature degrades rather than disappearing. `enabled: false` is for an
     * operator who would rather not have poppler parse an upload at all.
     *
     * The extractor is found by probing at startup, so `extractor-path` is only
     * for an installation the probe does not know about. Everything else here is
     * a bound on somebody else's process: how long it may run, and how much it
     * may say.
     */
    public record BookingImport(@DefaultValue("true") boolean enabled,
            /* Blank means probe: the Alpine and Debian layouts, then PATH. */
                               @DefaultValue("") String extractorPath,
            /*
             * A directory of extra extractor scripts, handed to the extractor as
             * `--additional-search-path`. The image points this at
             * /app/extractors; blank loads nothing but the 349 built in.
             *
             * It exists for fixes to upstream extractors, not for parsers of our
             * own — see extractors/README.md, which sets the bar for adding one.
             */
                               @DefaultValue("") String extractorSearchPath,
            /*
             * A tenth of a second is typical, so this is not a deadline anybody
             * meets on a bad day — it is the cap on a Qt process that has wedged
             * on a malformed PDF, which is exactly the case a subprocess boundary
             * exists to survive.
             */
                               @DefaultValue("30") int timeoutSeconds,
            /*
             * How much output is read back. The process is not ours, so a runaway
             * one must not be able to spend the heap; over the cap the answer is
             * nothing at all, because half a JSON array is a syntax error rather
             * than a shorter list of bookings.
             */
                               @DefaultValue("4194304") int maxOutputBytes) {
    }

    /*
     * The forecast on a day card.
     *
     * Open-Meteo by default, because it needs no API key: a self-hoster should
     * not have to register an account with a weather company to find out whether
     * it will rain on day three.
     *
     * **The free tier is non-commercial and CC BY 4.0.** An operator running this
     * commercially needs their own arrangement — which is why the base URL and
     * the attribution are both settings, and why the attribution travels to the
     * client with the data rather than being compiled into the Angular app.
     */
    public record Weather(@DefaultValue("true") boolean enabled,
                          @DefaultValue("https://api.open-meteo.com") String baseUrl,
            /*
             * How far ahead a forecast exists. Sixteen is Open-Meteo's maximum;
             * days beyond it get no weather at all rather than a placeholder,
             * because for a trip in nine months there is genuinely nothing to
             * show.
             */
                          @DefaultValue("16") int horizonDays,
            /*
             * How long a stored forecast stands. Minutes, not days: a forecast
             * changes through the day, which is the whole difference between this
             * cache and the enrichment one.
             */
                          @DefaultValue("180") int cacheMinutes,
            /* Shown wherever a forecast is. CC BY 4.0 requires it. */
                          @DefaultValue("Weather data by Open-Meteo.com (CC BY 4.0)") String attribution,
                          @DefaultValue("https://open-meteo.com/") String attributionUrl,
            /* Gap between outbound calls, and how long a caller waits for the gate. */
                          @DefaultValue("200") long minIntervalMillis, @DefaultValue("4000") long maxWaitMillis) {
    }

    /*
     * What the browser draws the map from. Not compiled into the client: an
     * operator running their own tiles, or one who would rather their users'
     * browsers not talk to a third party at all, changes this and every client
     * follows — which is also why the attribution travels with the URL rather
     * than being hardcoded next to the map.
     *
     * **Two kinds of source, and `styleUrl` wins when it is set.**
     *
     * A *vector* style (the default) is a MapLibre style document. It is what
     * makes labels readable on a trip abroad: raster tiles are pre-rendered
     * pictures with the local name burned into them — 東京都, never Tokyo, no
     * matter what the browser asks for — whereas vector tiles carry `name`,
     * `name:latin` and `name:xx` as data and the client picks. wander draws both,
     * so a place reads in your language *and* as it appears on the signs.
     *
     * The default is OpenFreeMap, which needs no API key, no account and states
     * no request limit, and serves the fonts and sprites as well as the tiles.
     * It is one person's project funded by donations — so if that is too thin a
     * thread for your instance, it publishes weekly planet dumps to self-host,
     * and this setting is how you point somewhere else.
     *
     * A *raster* `tileUrl` is still fully supported and is what you get by
     * setting `styleUrl` to blank: an instance with its own raster tile server
     * loses nothing by this change but the language.
     */
    public record MapTiles(@DefaultValue("true") boolean enabled,
                           @DefaultValue("https://tiles.openfreemap.org/styles/liberty") String styleUrl,
            /*
             * Used when the reader's theme is dark. A dark *style* rather than a
             * CSS filter over a light one, which is what the raster path has to
             * do — and which turns land green-on-black if you let it invert.
             */
                           @DefaultValue("https://tiles.openfreemap.org/styles/dark") String darkStyleUrl,
            /* The raster fallback, used only when styleUrl is blank. */
                           @DefaultValue("https://tile.openstreetmap.org/{z}/{x}/{y}.png") String tileUrl,
                           @DefaultValue("OpenFreeMap · OpenMapTiles · © OpenStreetMap contributors") String attribution,
                           @DefaultValue("19") int maxZoom) {
    }
}
