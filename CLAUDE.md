# CLAUDE.md

Guidance for Claude Code working in this repository.

## What wander is

A self-hostable, multi-user collaborative travel planner. Gradle multi-project
build, two modules:

- **`api/`** — Java 21, Spring Boot 4.1, Spring Security 7, JPA/Hibernate,
  Flyway, Postgres. Owns the API contract: springdoc renders OpenAPI from the
  Java types.
- **`web/`** — Angular 22 (standalone components, signals, zoneless),
  TypeScript. Built by the Gradle node plugin and packaged **inside** the boot
  jar as `META-INF/resources`, so a deployment is one container.

**The licence is AGPL-3.0-or-later** (`LICENSE`, and see README's Licence
section). One thing follows for the code: section 13 means a modified instance
offered to others over a network owes those users its source, which in practice is
a "Source" link in the interface. wander ships one — `wander.source-url`, drawn by
`user-menu.ts` beside the build chip **and** on the sign-in page, because most
people interacting with a public instance never get past that page. It is a
setting rather than a constant so a fork can point it at itself: a link aimed at
upstream names code the instance is not running, which looks like compliance and
is not. Blank hides it, which is right for a private box and wrong for a public
one.

## Commands

```bash
./gradlew build                          # frontend + jar + tests
./gradlew build -Pfrontend.skip=true     # backend only, skips all npm work
./gradlew :api:test                      # tests; also writes api/build/openapi.json
./gradlew :api:bootRun                   # API on :8080 (needs `docker compose up -d db`)
cd web && npm start                      # Angular dev server on :4200, proxies /api
cd web && npm run api:gen                # regenerate the typed client from the spec
./gradlew :web:apiGen                    # the same, on Gradle's pinned Node
cd web && npm test                       # vitest unit tests (money formatting)
cd web && npm run icons                  # redraw the app icons from public/icon.svg
```

Tests need a Docker daemon: they run against real Postgres via Testcontainers,
never H2 — the migrations use Postgres-specific SQL.

## The contract loop

Java record → springdoc → `api/build/openapi.json` (written by
`OpenApiSpecExportTest`) → `ng-openapi-gen` → `web/src/app/api`.

- **Never hand-write a DTO twice.** Change the Java record, rerun
  `:api:test`, then `npm run api:gen` — or `./gradlew :web:apiGen`, which runs the
  generator on the plugin's downloaded Node and is what CI uses, because the CI
  image is a JDK with no `node` or `npx` on PATH at all.
- **`web/src/app/api/` is generated but committed** so the Docker build and a
  fresh clone need no database. CI regenerates it and fails on drift. Never edit
  it by hand.
- A response field that is always present needs `@NotNull`, or springdoc omits
  it from `required` and the generated TypeScript field becomes optional.
- `springdoc.default-produces-media-type` must stay `application/json`. Without
  it every response is documented as a wildcard type and the generated client
  requests a Blob — the API still returns correct JSON, so only the browser
  breaks.
- The OpenAPI `servers` entry is pinned to `/` in `OpenApiConfig`; otherwise the
  spec records whatever random port the export test ran on.

## Server conventions

- **Default deny.** `SecurityConfig` authenticates everything except an explicit
  allow-list plus non-API GETs (the SPA shell and its client-side routes).
  A public endpoint must also carry `@PublicEndpoint`, which is what
  `EndpointAuthRatchetTest` checks: it fires an anonymous request at every
  handler under `com.wander` and fails if one answers. Do not weaken it.
- **Operation ids are global on the client.** ng-openapi-gen exports every
  operation unqualified into one barrel file, so a second controller with a
  `create` method collides with `TripController`'s. Name them for their
  resource — `createPlace`, `getItinerary`.
- **Trip access goes through `TripAccessService`.** `requireMember` for read,
  `requireRole` for write. A non-member gets **404**, not 403; a member with too
  weak a role gets 403. New trip-scoped endpoints must go through it.
- **A place holds several notes, written whole.** `place_note` rows, ordered, sent
  as a list on create and update — there is deliberately no endpoint for one note,
  so nothing has to reconcile a half-applied change and "delete the third" is
  expressed by sending the list without it. Blank bodies are dropped rather than
  stored, so "no notes" is the absence of rows, as with `day_notes`.
  `Place.replaceNotes` reuses rows by position rather than clearing and re-adding,
  which is the lesson `Expense.replaceShares` had to learn.
- **`places.starts_at` is a plain `TIME`, and not a sort key.** The day comes from
  `day_date` and the zone from wherever the place is, so neither needs storing —
  unlike `reservations`, whose instant-plus-zone exists because a flight lands
  somewhere else. A day keeps the manual order `PlaceService` renumbers on every
  drag: sorting by time would fight that and would strand every untimed place.
- **A place from search keeps the geocoder's reference.** `places.osm_ref` holds
  `node/240109189`, sent by the client from the suggestion it picked, for the same
  reason the coordinates are: the user chose one candidate of several and a later
  re-search can rank a different one first. It is the only thing that can identify
  a place upstream afterwards, so a place saved without it can never be asked
  about — which is why it is stored before anything reads it. Null forever for a
  place typed by hand, and `PlaceView.enrichable` is how the client asks. A kept
  photo lives beside it, and `places.category` is the geocoder's own word for what
  the place is — under the same deadline and never inferred from the name, because
  a category shown as fact should have come from something that knows.
  A kept photo is drawn from `photoThumbUrl` everywhere — the row, the
  detail panel and the printed page — because `photoUrl` is the *original* on
  Commons, which is routinely several megabytes for a picture shown 160px high.
  The original stays one click away under the credit.
  `Place.setPhoto` takes its author and licence or
  refuses: a Commons image is licensed *per image*, so a URL without its credit is
  a picture this project has no right to draw.
- **A day is addressed by its date.** Days are derived from the trip's range and
  have no rows, so anything hung off one carries a plain `day_date` — `places`
  does, and so does `day_notes`, whose real key is the unique `(trip_id,
  day_date)`. `Trip.requireCovers` is the one range check both services call.
  A day note is an upsert (`PUT .../days/{date}/note`) and a **blank note
  deletes the row**: "no note" is the absence of a row, so there is one
  representation of empty and no second endpoint to clear one. Notes ride along
  on `TripItinerary`, so the page is still one request.
- **A trip can be rewritten, but its dates are the hard part.** `PUT /api/trips/{id}`
  is **owner only**, like deleting one. Days are derived, so places and day notes
  carry a plain date and nothing in the database stops one pointing at a day the
  trip no longer has. Two rules cover it: a move of the **same length** shifts
  every place and note by the same offset (the "our flights changed" edit, which
  must not lose anything), and any other change that would leave content outside
  the new range is **refused with 409** — hiding those rows or deleting them are
  both worse than being told to move them. The refusal **names them** ("2 places
  (Fushimi Inari Shrine on 2026-08-28, …)"), first three then a count: a message
  that only counts them says there is a problem without saying where to look, and
  a real user ended up querying the database to find out. A length change *and* a
  move is genuinely ambiguous — "two days added at the front" wants the plan to
  keep its dates, "moved a month later and made longer" wants it to come along —
  so the server refuses rather than guessing and the client offers
  `shiftItinerary: true` as a retry. A shift that would still strand something is
  refused too, and rolls back. Shifting the
  notes deletes and reinserts them, because `uq_day_notes_trip_day` is not
  deferrable and an in-place shift collides mid-statement; `places` has no such
  constraint, which `V2` says it left out for exactly this reason. A trip's
  **currency moves only while it has no expenses** — after that the stored amounts
  mean something in it.
- **The itinerary carries one number from the money feature: what each day cost.**
  Grouped in the database by `ExpenseRepository.sumPerDay`, payments excluded for
  the same reason the trip total excludes them, and a day with nothing spent comes
  back **null rather than zero** — a card printing "0.00" on every untouched day
  would be a claim nobody entered. It rides along on `TripItinerary` like the day
  notes do, because the page is one request; the number is a link to the expenses
  page, not a second place to edit money.
- **Membership is the only grant.** No owner column on `trips` — `trip_members`
  is the single source of truth for who may see a trip. It needed no migration to
  become a real feature: the table has carried `role` and `UNIQUE (trip_id,
  user_id)` since `V1`.
- **A trip has exactly one OWNER, and the only way it moves is a transfer.**
  `TripMemberService` never counts owners, because it does not have to: `addMember`
  refuses to create a second one, `removeMember` refuses to delete the only one,
  and setting a member's role to `OWNER` demotes the caller to `EDITOR` in the same
  transaction. There is therefore no "last owner" check to forget. Members are
  added **by email and must already have an account** — the only thing this
  application mails is a reset link, and only where an operator configured a relay.
  Only the owner manages members; any member may remove *themselves*, which is how
  you leave a trip. Authorisation is checked **before** the target is looked up, so
  a member who may not remove anyone cannot use the 404 to learn who is on the
  trip.
- **Self-signup is off by default, and guessing a password is throttled.** Two
  separate answers to "this instance is on the internet now". `registration-enabled`
  defaults to **false** because the default belongs to the deployment that is
  exposed, not to the laptop — and it would be useless if it sealed the instance,
  so `UserAccountService.register` takes an optional invite token and a *live* one
  authorises the account on its own (see Invitation links). The suite creates its
  accounts by registering, so `api/src/test/resources/application-test.yml` turns
  the switch back on for the `test` profile; the two closed-instance tests override
  that with `@TestPropertySource`, which also gives each its own context.
  `LoginThrottle` counts failed sign-ins per email **and** per client address —
  the second is what catches a spray across many accounts, and it is the looser
  limit because an office is one address. A success clears both, the gate closes
  *before* the password is verified (spending a bcrypt round per guess is the
  denial of service), and it is a window rather than a lockout: there is no
  password reset on this instance, so a lockout would need the operator to undo
  it. Its integration test gets its own context on purpose — one bean, one map,
  and every test in the suite arrives from 127.0.0.1, so exhausting a counter in
  the shared context would take everybody else's sign-in down with it.
- **The per-address limits are only as honest as the proxy.** `LoginThrottle`
  counts by client address as well as by email, and that counter is worth
  nothing unless `X-Forwarded-For` is *overwritten* by the proxy — Caddy's
  default is to append the real client to whatever arrived, and
  `server.forward-headers-strategy: framework` reads the **first** entry, so an
  appending proxy lets a caller name their own address. The Caddyfile's
  `header_up X-Forwarded-For {remote_host}` is the whole fix and it is
  load-bearing; binding 8080 to loopback is a different protection for a
  different attack (going *around* the proxy, not through it). Nothing fails
  visibly when this is wrong: the limit still exists, still answers 429, and
  still never fires for the caller it was meant for. `UpstreamQuota` sidesteps
  the question entirely by keying on the user id, which is the better key
  wherever the endpoint is authenticated.
- **Registering is counted too, and counts attempts rather than failures.**
  `POST /api/auth/register` is anonymous, spends a bcrypt round on every call and
  leaves a row behind when it works, so an unmetered one is both a way to burn
  the box's CPU and a way to fill the user table. It shares `LoginThrottle`
  rather than getting a counter of its own — same argument, and two windows would
  be two places to get the arithmetic wrong — but keys only the address (the
  email belongs to an account that does not exist yet) and clears on nothing but
  the window passing. **The browser suite creates every account by registering,
  from one address**, so `application-test.yml` raises the limit and
  `npm run e2e` needs `WANDER_REGISTRATION_MAX_PER_ADDRESS` raised on whatever
  instance it drives.
- **A quota per person on the endpoints that spend somebody else's budget.**
  `UpstreamQuota` meters place search, enrichment and the forecast per user id.
  `RateGate` is not this and does not help: it spaces what wander sends *out*, by
  parking the request thread, so without an inbound limit one heavy caller
  queues everybody else's searches behind their own and the 429s land on people
  who did nothing. Cached reads count, because a quota that only counted misses
  is one anybody can sit just underneath. This is the piece that makes opening
  self-signup survivable — `.env.example` names the donated capacity as the
  reason it is off, and authentication alone stops nothing once anybody can get
  an account.
- **The password policy is a list, not a rule about capitals.** `@Size(min = 10)`
  was the whole policy, and `password12` is ten characters. `@GuessablePassword`
  adds a bundled blocklist plus the patterns too numerous to list (one character
  held down, a straight run of the keyboard or the digits, in either direction).
  Composition rules were rejected deliberately: they produce `Password1!`, which
  is on every list there is, while making the password harder to remember. No
  breach-list API call either — it would put an outbound dependency on the one
  page that must work with no outbound network. It applies to **both** doors;
  a policy at registration but not at change-password is just the door people
  use to get a weak one. The suite's own `correct-horse-battery` has to keep
  passing it, which is what `GuessablePasswordValidatorTest` pins.
- **The CSP is assembled from the map settings, never hardcoded.**
  `ContentSecurityPolicy` reads the style, dark-style and tile URLs out of
  `MapTiles` and puts their origins in `connect-src`, `img-src` and `font-src`,
  because those hosts are the operator's choice and a fixed policy would blank
  the map for the first self-hoster who runs their own tiles. `style-src` needs
  `'unsafe-inline'` and always will — Leaflet and MapLibre position DOM they
  built themselves by writing style attributes, which no nonce can reach — but
  `script-src` gets no such exemption, and that is the half that stops an
  injection. The tile URL is parsed with a regex rather than as a URI because
  `{z}/{x}/{y}` is not legal in one. **A cross-origin host the page only ever
  names in an `<img>` still needs to be in `connect-src`**, which is why Commons
  is in both: the service worker stands in front of every request and re-issues
  it with `fetch()`, and a fetch is governed by `connect-src` whatever element
  started it. Getting this wrong is invisible to whoever has the picture cached
  — the person who chose a photo could see it and nobody else could, and ngsw
  turned the refusal into a synthetic 504 that looks like an upstream outage.
  **Commons is two hosts, not one**: the original file is on
  `upload.wikimedia.org`, and `imageinfo`'s `thumburl` now comes back on
  `thumb.wikimedia.org`. Both are in the policy, because the thumbnail is what
  a place row and the printout draw — with only the first, a photo kept before
  Wikimedia's change renders and one kept after it is a broken image, while the
  detail panel goes on working.
- **A validation failure's message is in `fields`, not in `message`.** The
  envelope's own `message` is the generic "Validation failed", so `messageOf`
  prefers the first field message — otherwise somebody refused for a weak
  password is told only that something was wrong.
- **A password is changed by its owner and reset only by an administrator.**
  `POST /api/auth/password` takes the
  current password as well as the new one, and that is the whole point: a session
  cookie somebody else has got hold of must not be enough to take the account for
  good. It goes through the *same* `LoginThrottle` as signing in — verifying a
  password is verifying a password, and a second door with no counter on it is
  the one an attacker with a stolen session walks through. A wrong current
  password is **400, not 401**: the client reads a 401 as "your session is gone"
  and would sign somebody out over a typo, clearing the offline cache with it.
  Reusing the current password is refused too, because answering "done" to it
  would leave the user believing something changed. On success every *other*
  session for that account is deleted (`FindByIndexNameSessionRepository`,
  indexed by principal name, so a lookup rather than a scan) while the caller's
  own survives — somebody changing a password they think leaked expects exactly
  that, and signing them out of the page they are looking at would read as
  failure. The recovery for a forgotten password is an administrator
  minting a link, and — on an instance that has been given an SMTP relay — the
  person asking for one themselves. See "Administering accounts".
- **Renaming yourself has to rewrite the session, not just the row.** The
  principal is serialised into the session at sign-in and read back on every
  request, so `PUT /api/auth/profile` rebuilds the `SecurityContext` with a fresh
  `WanderUser` and saves it back — exactly what `authenticate` does after a login.
  Update the row alone and `/me` keeps answering with the old name: the change
  appears to work and then undoes itself on the next page load, with nothing
  failing. `UpdateProfileIntegrationTest` asserts on `/me`, not on the response
  body, for that reason. **Only the display name is editable**: the email address
  is the account's handle — members are added by address and invitations are
  accepted against one — so changing it is a rename with consequences elsewhere
  rather than an edit to a label.
- **Flyway owns the schema**, Hibernate runs `ddl-auto: validate`. Schema changes
  are a new `V<n>__*.sql`; never edit an applied migration. That includes tables
  a library would happily create for itself: `V5` carries Spring Session's own
  `schema-postgresql.sql` verbatim (so an upgrade can be diffed against the jar)
  and `spring.session.jdbc.initialize-schema` is `never`.
- **Sessions live in Postgres** (Spring Session JDBC), because the session is the
  credential and an in-memory store signs everybody out on every restart. Two
  things follow. The cookie is **`SESSION`**, not `JSESSIONID` — nothing hands out
  a container session any more. And **anything reachable from the security context
  must be `Serializable`**: `WanderUser` is, and if it stops being, the first
  request after signing in fails at runtime with nothing at compile time to warn
  you. `SessionPersistenceIntegrationTest` starts a second instance on the same
  database and presents the first one's cookie to it, which is the only honest way
  to test "survives a restart".
- **Outbound HTTP goes through an interface.** `GeocoderClient` is the seam the
  tests replace (`@MockitoBean`), which is what keeps the suite off the network
  and off a free public service's rate budget. `GeocodingService` owns the
  feature toggle, the LRU cache, and the shared one-request-a-second gate — a
  new upstream call belongs behind the same shape, not in a controller. There are
  five of these now: `GeocoderClient`, `EnrichmentClient`, `WeatherClient`,
  `MailClient` and `FxRateClient`.
- **A second application context in a test needs command-line arguments**, not
  `SpringApplicationBuilder.properties()`: the latter lands in Spring's *default*
  property source, which `application.yml` then overrides, so the new instance
  quietly goes looking for a database on localhost. `.run("--spring.datasource.url=...")`
  wins instead.
- **Boot 4 notes** (these differ from every Boot 3 tutorial): `TestRestTemplate`
  is gone — use `RestClient`; Jackson 3 lives under `tools.jackson`; each
  integration ships as its own module, so `flyway-core` alone gives you no
  autoconfiguration (`spring-boot-flyway` does); Testcontainers 2.x artifacts are
  `testcontainers-postgresql`, not `postgresql`; there is no `RestClient.Builder`
  bean unless `spring-boot-restclient` is on the classpath, so an outbound client
  calls `RestClient.builder()` itself.

## Client conventions

- **`inlineCritical` is off in `angular.json`, and the CSP is why.** Angular's
  critical-CSS inlining rewrites the stylesheet link to
  `media="print" onload="this.media='all'"` — an **inline event handler**, which
  `script-src 'self'` refuses and which no hash or nonce can rescue (hashes do
  not apply to event handlers without `'unsafe-hashes'`, and adding that to get
  a rendering optimisation would be trading the directive for the thing it is
  for). Turning it off costs a first-paint optimisation on a bundle served from
  the same container. The symptom if it comes back is instructive: the app looks
  and behaves perfectly, and every one of the 24 browser tests that asserts on
  console errors fails at the last line with a CSP violation.
- **Styling goes through tokens, never raw colours.** `bg-surface`, `text-muted`,
  `border-border` — not `bg-white` or a hex literal. That is what makes the
  three-state theme (system / light / dark, in `web/src/styles.css`) work
  everywhere at once. Repeated shapes live as `.card` / `.field` / `.btn-*` in
  the `@layer components` block; Tailwind 4's `@apply` cannot reference another
  custom class, so variants list the base selector rather than composing it.
- **`.label` is a grid**, so a bare text node and a sibling `<span>` inside one
  become separate rows: `Destination <span>(optional)</span>` puts "(optional)" on
  a line of its own. Wrap both in one span.
- **A bare `grid` sizes its track to max-content.** Inside a constrained column
  (the trip page's day list, beside the map) that lets one long address stretch
  the cards past their column and under the map. Use `grid-cols-1` — which is
  `minmax(0, 1fr)` — for any single-column grid whose content can be wide, and
  `min-w-0` on a grid item that must be allowed to shrink.
- **Tailwind utilities beat `@layer components`, whatever the specificity.** A
  component class cannot override a utility the template also sets — a
  `border-color` in the components layer loses to `border-border` on the element.
  Reach for a property nothing else sets (an `outline`), or declare the rule
  outside any layer, as the dark-mode tile filter does.
- **Drag-and-drop (Angular CDK) rules.** Every day renders its `cdkDropList`
  even when empty, or an empty day cannot be dragged into — the first thing
  anyone tries. Dragging is handle-only, so a touch drag on a row still scrolls
  the page. `cdkDragLockAxis="y"`, because days are stacked and a full-width
  preview chasing the pointer sideways trails out over the map. The arrow
  buttons remain the keyboard path: a drag has no keyboard equivalent, and both
  paths call the same `PlaceRepo.move`.
- **`PlaceRepo.move` is the one optimistic write.** It reorders the local copy
  before calling the server (renumbering the same way the server does) and puts
  the old order back on failure. The others re-read and let the server win; a
  drag cannot, because the row has already moved under the user's finger.
- **Leaflet is imported in exactly one file.** `pages/trip/trip-map.ts` owns the
  map: it creates it in `afterNextRender`, reacts to signals by issuing
  imperative calls, and removes it in `onDestroy` (Leaflet holds
  document-level listeners, so a map that is not removed leaks across
  navigation). Leaflet cannot tell a programmatic move from a gesture — `setView`
  raises `zoomstart` exactly as a pinch does — so auto-framing is guarded by a
  flag around our own moves; without it the first frame silently disables
  itself. Map furniture is styled from the same tokens as everything else, in
  `@layer components`, because Leaflet builds those nodes outside any template.
- **The basemap is vector, drawn by MapLibre inside Leaflet.** Leaflet still owns
  the map, the markers, the tooltips and the framing; only the tile layer is
  different, and `@maplibre/maplibre-gl-leaflet` makes it an ordinary Leaflet
  layer so nothing after that line changed. The reason is language: a raster tile
  is a picture with the local name painted into it — 東京都 whatever the browser
  asks for — while vector tiles carry `name`, `name:latin` and `name:xx` as data.
  `applyLabelLanguage` rewrites `text-field` to draw both, the reader's language
  over the local name, and only on layers whose label already mentions a name
  (a house-number layer's `text-field` is `{housenumber}`, and rewriting that
  blanks every number at street zoom). It hangs off `style.load`, not
  `styledata`, which our own `setLayoutProperty` calls would re-enter. A raster
  `tileUrl` is still supported and is what a blank `styleUrl` selects.
- **MapLibre's tile worker is copied by `angular.json`, and that is not
  optional.** It is loaded by URL at runtime, so no bundler sees it as an import
  and none emit it — and the shared chunk it imports beside itself has to travel
  with it. Get it wrong and the request falls through to the SPA fallback, which
  answers `index.html`: the worker dies on its first line with **nothing** logged
  — no console error, no MapLibre `error` event, no failed request — and the only
  symptom is a basemap that never draws while the style document, the sprites and
  the markers all load perfectly. `setWorkerUrl` resolves it against
  `document.baseURI` rather than trusting the library's default, which is derived
  from the chunk's own `import.meta.url`. The browser suite stubs the basemap
  with an *empty but valid* style so a run stays off a donation-funded tile
  service — which means it asks for no vector tiles and needs no worker, so it
  could never catch this. The test that does asks the server for the two files
  and checks the content type.
- **Dark is a different style, not a filter**, which is why `ThemeService` grew
  `isDark`: CSS has `prefers-color-scheme`, but a style URL is chosen in
  TypeScript, and asking the DOM for `[data-theme]` answers nothing for the
  common case of following the OS.
- **WebSocket is imported in exactly one file**, for the same reason as Leaflet:
  `core/trip-sync.ts` owns the socket, the backoff, the keepalive and the
  release on destroy. It *reports* and decides nothing — the trip page chooses
  what to re-read, because it is what knows which repos are on screen. Its
  `TripChange` interface is **the one hand-written copy of a server payload in
  this project**: the OpenAPI document describes HTTP operations, so a frame
  cannot appear in it and there is nothing to generate. That is affordable only
  because the payload is invalidation-only; anything richer belongs on the REST
  side where the contract loop owns it.
- **A pushed re-read retries itself.** Nobody is watching it: if the single
  request a live event triggers is dropped, the page sits there quietly wrong and
  the next event may be hours away. `TripPage.refreshFromServer` retries with
  backoff and is single-flight, so several events arriving together cost one
  re-read. A *reconnection* re-reads unconditionally — events during the gap were
  never delivered, and there is no replay.
- **A space between an element and an `@if` block needs `&ngsp;`.** Angular
  drops a whitespace-only text node there, so `</span> @if (…) { <span>· …` runs
  the two together — "September 14· ¥9,400" on the printed day heading. Nothing
  fails; the separator simply loses its space.
- **`@for` needs a collection from the component, not an inline array literal.**
  `@for (x of [1, 2]; ...)` does not survive the block-syntax parser and fails at
  runtime with `newCollection[Symbol.iterator] is not a function`.
- **Leaving a trip is the one write that must not re-read.** `MemberRepo.leave`
  exists next to `remove` for exactly that: they are the same endpoint, but the
  trip is a 404 for you the moment your own membership goes, so reloading the
  list afterwards would turn a success into an error on screen. A transfer is the
  mirror image — it changes the *caller's* role, so the trip page re-reads the
  itinerary on `rolesChanged` or keeps offering edit controls it no longer has.
- **The login page decides whether to offer a sign-up, and an invitation
  overrides the instance.** `/api/config/sign-in` says whether this instance
  accepts them, and "not answered yet" means no — that part is unchanged. But a
  visitor sent here by `authGuard` from `/invite/<token>` is offered the form
  regardless, with a line saying why, and the token travels with the
  registration: it is the only thing authorising the account. The token comes out
  of `returnUrl` and nowhere else, matched against that one route rather than
  anything shaped like it, and it is not validated in the browser — a spent link
  gets the server's answer, which is better than a page with no way forward.
- **Components never call HTTP.** They go through a repo in `web/src/app/repo/`,
  which wraps the generated client. Offline support will land inside the repos;
  a component that bypasses them blocks that.
- **Signals, not RxJS state.** Stores expose readonly signals
  (`SessionStore`, `TripRepo`). No NgRx.
- Auth is a session cookie; `withXsrfConfiguration` handles CSRF. Keep the API
  root URL empty (same-origin) — Angular only attaches the XSRF header to
  relative URLs.
- CSRF applies to login and register too. A client must load something first to
  get the `XSRF-TOKEN` cookie; an anonymous request that fails the CSRF check
  comes back **401**, not 403, because Spring treats access-denied-while-anonymous
  as "authenticate first".

## Instance configuration reaches the client

`GET /api/config` (authenticated) carries the map source — a vector `styleUrl`
and its dark counterpart, with a raster `tileUrl` behind them — its attribution,
and whether place search is enabled. Both kinds are sent every time rather than
one being resolved server-side: which applies depends on the reader's theme,
which is a browser preference the server has no business knowing.

Nothing operator-configurable should be compiled into the Angular app:
self-hosting means the basemap and the geocoder are somebody else's decision, and
"search off, map off" is a supported configuration. `InstanceConfigStore` loads it once when the signed-in shell
mounts, and features that depend on it treat "not answered yet" as available so
nothing flickers into existence.

`GET /api/config/sign-in` is the one **public** endpoint here, carrying
`registrationEnabled` alone. The login page runs before anybody has a session, so
it cannot read the authenticated config — which is why it used to offer "Create
one" on an instance with sign-ups switched off. It leaks nothing: an anonymous
caller learns the same thing by posting to `/api/auth/register` and reading the
403. This is also the one place that inverts the rule above and treats "not
answered yet" as *un*available — a "Create one" link that vanishes as somebody
reaches for it is worse than one that appears a moment late, and not offering
registration is the entire point. Keep the rest of `/api/config` authenticated:
one anonymous endpoint for one boolean, not the operator's configuration.

It is no longer the *only* public endpoint — `/api/auth/reset/{token}` is the
second, and the reasoning for why that one had to be is in "Administering
accounts" below. Two is still the whole anonymous surface, and both are listed
in `SecurityConfig` beside the login and register pair.

## Place enrichment

Descriptions, facts, hours and photo candidates for a place, from OpenStreetMap,
Wikidata, Wikipedia and Commons.

- **One seam, not four.** `EnrichmentClient` is a single interface and
  `WikiEnrichmentClient` walks the whole chain — OSM tags, then Wikidata, then
  Wikipedia, then Commons — because each step only happens if the one before found
  something, and most places stop at the first for want of a `wikidata` tag. It is
  what the tests replace, exactly as `GeocoderClient` is.
- **`RateGate` is a bean, shared by search and enrichment.** Nominatim's limit is
  one request a second *per client* and it is the instance that gets blocked, so a
  second gate would quietly double the rate. `UpstreamGatesTest` asserts both
  callers hold the same object, because nothing else would notice.
- **The cache is a table, keyed by `osm_ref` and shared by every trip and user.**
  Two people with Fushimi Inari on their itineraries mean one row: it describes the
  shrine, not anybody's Tuesday. Note this makes enrichment rows outlive a *test*
  as well, so tests bring their own reference rather than assuming a clean slate.
  "We looked and found nothing" is stored too, or every popup on a bus stop re-asks
  four services.
- **Hours are OpenStreetMap's raw string, never parsed.** `Mo-Su 06:00-18:00` with
  the date it was fetched is something a person judges; an app-computed "Open now"
  is a claim this data cannot support, and people plan around hours. This was the
  one part of the feature I argued against; showing the provenance is what makes it
  defensible.
- **A photo without its author and licence is not stored, offered or displayed.**
  A Commons image is CC BY, CC BY-SA or public domain *per image*, so the terms for
  one say nothing about the next. `PlaceFacts.PhotoCandidate.isUsable` drops
  unattributable candidates during parsing, the photo columns are written five at a
  time by `Place.setPhoto`, and `setPlacePhoto` stores **the credit the server
  fetched** rather than the one the request supplied — a request is a choice among
  what was offered, not a source of truth. It also refuses a URL that was never
  offered, which is what stops the endpoint becoming a way to hotlink anything.
- Nothing here throws upward. An enrichment is a nicety: a Wikipedia outage costs
  a description, not a 502 on somebody's itinerary.
- **The enrichment lives in `pages/trip/place-detail.ts`, a panel — not the map
  popup.** It was a popup for about an hour, which took four attempts to make fit
  and needed an Angular component rendered into Leaflet's DOM, a `ResizeObserver`
  to notice it had grown, and a hand-written pan because Leaflet's own runs before
  the content exists. All of that is deleted; the popup is a label again. The
  lesson worth keeping: **Leaflet computes a popup's size and pan once, on open**,
  so a popup is the wrong home for anything that arrives afterwards.
- **The pin label is a tooltip, and there is no popup at all.** The click that
  opens the panel used to open a popup too, and the popup lost: the panel is a
  drawer over the right of the viewport, Leaflet auto-pans a popup to fit the
  **map container** and knows nothing about what covers it, and on this layout the
  panel covers the map outright — there was nowhere to pan to, so the label was
  drawn underneath it every single time. `bindTooltip` needs no pan, opens on
  hover *and* focus, and the panel's header already carries the same two lines, so
  a tap that gets no hover still has its answer. The general form is worth more
  than the fix: **an overlay outside the map is invisible to Leaflet's geometry**,
  so anything Leaflet positions for itself has to stay clear of one. Note also
  that this was green the whole time — Playwright visibility is CSS, not
  occlusion, and `smoke.spec.ts` happily asserted on text no one could read. It
  now asserts `.leaflet-popup` has **count 0** while the panel is open.
- **The panel is the only editor for a place.** The row used to carry an inline
  form too; two editors for one thing is two places to keep in step and two
  answers to "where do I change the name".
- **Removing a place asks first, on both paths.** It is the only irreversible
  thing in the itinerary — it takes the notes written on the place with it, there
  is no undo, and live sync puts it on everybody else's screen within the second.
  Two paths remain deliberately (the row's `×` and the panel's Remove) because
  they answer different situations — a pin click opens the panel with no row in
  view — but the row's `×` is the sixth small icon in a strip, immediately after
  "move to next day", and `.row-actions` stay on permanently where there is no
  hover, so on a phone it is a live target beside an arrow. The confirmation is
  **inline where the button was**, not a dialog: there is no backdrop to dismiss
  by accident, and it names what goes ("Its note goes with it"), because the notes
  are the part nobody expects to lose. This is the project's first confirmation of
  anything; leaving a trip and deleting an expense still do not ask.
- **Name a row control with `aria-label`, not an `sr-only` span.** Both give the
  same accessible name, but an `sr-only` span puts the subject's name into the
  row's *text* a second time, and every loose `getByText('Park Guell')` in the
  browser suite then matches two elements. Adding one to the remove button broke
  six tests at once; `aria-label` broke none. The drag handle already did it this
  way.
- **The place row's name is a handle the browser tests identify a place by**, and
  it has broken them twice — once when a category chip went inside it, once when it
  became a button to open the panel. Changing it is fine; changing it without
  updating `smoke.spec.ts` is not.

## Talking to Nominatim

Place search is a proxy (`/api/geo/search`), authenticated like everything else —
an open one would hand this instance's rate budget to anyone. Three rules the
usage policy imposes and this code keeps: an identifying `User-Agent` — which is
what the policy actually requires, "identifying the application (stock User-Agents
as set by http libraries will not do)"; `wander.geocoding.contact-email` sharpens
it into a contact but is **not** required by either Nominatim or Wikimedia, and the
blank fallback `wander/<version> (self-hosted travel planner)` is already
compliant, at most one outbound request a second **across the
instance** — it is the instance that gets blocked, not a user, so `RateGate` is
shared rather than per session — and results cached, which is what makes a
typeahead affordable at that rate. Debouncing sits in the component, next to the
keystrokes it throttles.

The caller's `Accept-Language` is forwarded to the geocoder and is **part of the
cache key**. Without a language Nominatim answers in the place's own language — a
search for Kyoto returns 京都 — and sharing one cache entry across languages would
hand the first caller's language to everyone after them. The header is hidden from
the OpenAPI document (`@Parameter(hidden = true)`): browsers set it themselves and
JavaScript may not touch it, so a generated client parameter could only be wrong.
`wander.geocoding.language` is the fallback for callers that send none.

The client sends a picked suggestion's coordinates when creating a place rather
than having the server re-geocode the name: the user chose one candidate of
several, and a second search can rank a different one first.

## Money

Expenses are the one feature where a rounding bug is silent and permanent, so the
rules are narrow on purpose.

- **Integer minor units, everywhere.** `1234` is 12.34. No `BigDecimal`, no
  `double`, no decimal strings on the wire. `amount_minor` is `BIGINT` because a
  JPY trip has no minor unit at all.
- **One currency per trip**, on `trips.currency`, chosen at creation and **not
  editable** — the amounts stored against it mean something, so changing it would
  be a re-denomination rather than a relabel. `wander.currency` is the default for
  a caller that does not choose. An *expense* may be in any currency; see below.
- **An expense carries its own currency, converted once on the way in.**
  `expenses.amount_minor` did not change meaning — it is still the trip's
  currency and still what every aggregate sums, which is why `sumPerDay` and the
  balance summary needed no edit and `V18` needed no backfill. Beside it sits the
  receipt: `source_amount_minor`, `source_currency`, `fx_rate` and `fx_quoted_on`,
  all null for the ordinary case, and `ck_expenses_fx` refuses any half of that.
  `V6` said balances that mix currencies "stop being arithmetic" and that is still
  the rule — the conversion happens at the boundary so nothing downstream of
  `ExpenseService` knows a second currency exists.
- **The rate is frozen at entry and never looked up again.** The money left the
  account at the rate of the day. An edit re-runs the arithmetic (the amount may
  have changed) but reuses the stored rate; only a change of *currency* or of
  *date* refetches, because those make the row a different claim. Without that,
  fixing a typo in a description silently moves everybody's balance —
  `theRateIsFrozenAndAnEditDoesNotLookItUpAgain` is what holds it.
- **The split is made of the converted total, never of converted shares.**
  Convert each share on its own and they round independently: ¥5,000 and ¥3,000
  of a ¥8,000 bill come to €27.92 and €16.75, which is a cent short of the €44.67
  the expense is worth. So EXACT shares are typed and validated in the currency on
  the bill, then `ExpenseSplitter.proportionalShares` divides the converted total
  in those proportions — largest remainder, ties to the lowest user id, the same
  rule `equalShares` uses. For an expense in the trip's own currency it is the
  identity, which is what lets one code path serve both.
- **Rates are asked for against the euro and divided here.** Never as a pair.
  The upstream publishes about five significant figures whichever direction it is
  asked in, so the small side of a pair arrives pre-rounded: the dong against the
  euro is `3.3e-05` — two figures, over a percent of error — where the euro
  against the dong is `30127`. The date is always explicit for the same class of
  reason: asked for "latest", each currency answers with its own most recent
  publication, so two legs of one cross rate can be stamped with different days.
- **A missing rate is an error, and this is the one upstream where that is
  true.** Enrichment swallows an outage because a description is a nicety; the
  forecast answers 200 with nothing because weather is decoration. Neither
  argument survives money: an expense stored without a rate either drops out of
  the totals or counts its yen as euros, and nothing later rechecks it. So the
  write is refused — and the message names the way out, because there is one.
- **A rate can be typed, and that is a first-class path rather than a fallback.**
  It replaces the lookup rather than overriding its answer, so it is the only
  thing that works on an instance with no outbound network — and it is frequently
  the *better* number, since a card statement knows what was really charged and a
  market reference does not. `fx_manual` is a column because the two are different
  claims and the interface says which.
- **The client computes no part of the conversion, including the preview.**
  `GET /api/fx/rate` takes an optional `amountMinor` and answers with
  `convertedMinor`, so the figure the form shows comes from the same code path
  that will do the real conversion on save. A preview multiplied in TypeScript
  would be a second implementation, and the way it would announce itself is by
  disagreeing with the saved figure by a cent.
- **`fx_rates` is the strongest cache in the project and has no TTL.** A rate
  published for a past day is *final*, not merely fresh — unlike an enrichment,
  which goes stale, or a forecast, which improves. Keyed against the euro rather
  than as a pair, so a trip's second foreign currency is half a lookup. Global,
  like `place_enrichment`, which means **rows outlive a test**: a test that counts
  calls to `FxRateClient` has to bring its own date or it is really testing which
  test ran first.
- **`CurrencyConversion` is the one place a decimal type is allowed**, and the
  exception is narrow: a rate is not a quantity of money and has no minor unit,
  so the multiplication has to happen somewhere. It happens there, in
  `BigDecimal`, rounded HALF_UP exactly once, and the result leaves as a `long`.
  Exponents come from `java.util.Currency`, for the reason `money.ts` takes them
  from `Intl` — a table maintained here is a table that is wrong about the yen.
  Rates cross the wire as **strings**, never JSON numbers, for the same reason
  amounts are integers.
- **A split always sums to its amount.** `ExpenseSplitter.equalShares` spreads the
  remainder one minor unit at a time to the lowest user ids — €10 over three is
  334/333/333, deterministic so a test can assert on it. An `EXACT` split is the
  client's numbers and is **refused with 400** unless they add up; adjusting it
  silently would corrupt every balance on the trip with no error anywhere.
- **All the arithmetic is server-side**, in `ExpenseSplitter` and
  `ExpenseService.summarise`, and shipped in the response. The client formats and
  parses money (`web/src/app/core/money.ts`) and computes none of it — a second
  implementation in TypeScript is a second chance to round a cent differently.
  `Intl.NumberFormat` is where the per-currency exponent comes from, so there is
  no table of exponents to be wrong about; `parseMoney` **rejects** more decimal
  places than the currency has rather than rounding them away.
- **`split_mode` is stored** so an edit reopens in the mode it was saved in.
  Without it, fixing a typo in the description of an equal split converts it to an
  exact one.
- **An expense's date is not range-checked** against the trip, unlike a place's
  day: flights and deposits are paid months earlier.
- **Replacing a split updates the rows it keeps.** `clear()` and re-add makes
  Hibernate order inserts before orphan deletes in one flush, and
  `uq_expense_shares` then rejects the write for anybody who was in both splits —
  which is almost everybody, almost every time. See `Expense.replaceShares`.
- **A payment is an expense, not a table of its own.** `expenses.kind` is
  `EXPENSE` or `PAYMENT`; a payment's payer is whoever handed money over and its
  single share belongs to whoever received it, so `net = paid - owed` clears the
  balance with no special case. `kind` exists for one reason: a trip's *total* is
  what it cost, and money moving between its members is not a cost. A payment has
  no update — `PUT` on one is a 409 — because rewriting it through an expense body
  could put its share on the wrong person and silently reverse a balance; fixing
  one is removing it and recording it again.
- **`PersonBalance` keeps four figures apart**: `paidMinor` and `shareMinor` are
  *expenses only*, with `paymentsMadeMinor` and `paymentsReceivedMinor` beside
  them. Folding payments into the first two balances correctly and reads as a lie
  — somebody who fronted an €84.51 dinner and was handed €42.26 back would see
  "paid €84.51 · share €84.51" when their share was €42.25. The share is the
  number people check against their own memory, so it stays its own figure.
- **Somebody who leaves a trip keeps their shares**, and `PersonBalance.stillAMember`
  is false for them. Money is history; membership is present tense. Dropping the
  rows would silently forgive a debt, and refusing the removal would make it
  impossible to leave a trip you had spent money on.

## Invitation links

The last piece of sharing, and the way somebody with **no account** joins a trip.
`TripMemberService.add` cannot reach them: it works by email address and nothing
here mails an invitation. A link needs no mail — the owner delivers it
themselves. That was always the way round the original blocker; delivery was
never this application's problem, and it is still not: the SMTP relay a password
reset can use is deliberately not wired to this, because an owner who has a
stranger's address already has a way to send them something.

- **The token is never stored.** `trip_invites.token_hash` is a SHA-256 digest and
  the token exists in exactly one response, once — `CreatedInviteView`, which the
  client shows immediately and says is not shown again. The nightly dumps leave
  the machine, so a token at rest would turn a mislaid backup into working keys to
  other people's trips. Same bargain as the admin password printed once to the log.
- **SHA-256, not bcrypt, and it is the opposite reasoning to `users.password_hash`.**
  Bcrypt is slow because a password is short and guessable. This token is 256 bits
  from a CSPRNG: there is nothing to slow down, and a per-row salt would make the
  lookup a scan of every invitation on the instance instead of one indexed read.
- **Accepting takes a row lock** (`findByTokenHashForUpdate`). Without it two
  people opening the same forwarded link both read an unused invitation, both join,
  and both mark it used — a single-use link admitting two strangers, with every
  individual request looking perfectly correct.
  `twoPeopleRacingForOneLinkGetOneMembership` is what holds this.
- **A bad token is always 404**, whatever is wrong with it, so a guesser never
  learns which attempt found something real. A token that *exists* but is spent,
  revoked or expired answers 200 with `joinable: false` and the server's own
  reason — the holder is not an attacker and "ask for another" is actionable.
- **A live token is also a permit to sign up.** `TripInviteService.admits` is
  asked by registration, and it is the piece that keeps a closed instance from
  being a sealed one: accepting an invitation needs an account, self-signup is off
  by default, and nothing here mails an invitation — so without it, switching
  sign-ups off
  would silently mean nobody but the first-boot admin could ever join. The token
  is **checked, not spent**: `accept` still locks the row and re-checks
  everything, so a token that dies between the two steps costs an account rather
  than a membership. A wrong token, a spent one and no token at all are the same
  403, so the register endpoint cannot be walked to find out which invitations
  exist.
- **No new public endpoint.** The preview is authenticated like everything else,
  so the journey is link → register → back to the link, carried by `returnUrl` on
  `authGuard`. An anonymous preview would have been the *second* exception to
  default-deny, and `/api/config/sign-in` is documented as the only one.
  `returnUrl` is sanitised to a same-origin path in `LoginPage`: a login page that
  redirects anywhere is a phishing primitive.
- **`InviteRepo` is the one repo with no offline cache**, deliberately. A minted
  token would be written to IndexedDB — the one place this application keeps trip
  data — and a cached list would show a revoked link as still outstanding, which is
  backwards for a control whose purpose is taking access away. It also clears
  itself when the signed-in user changes, or a link minted before signing out is
  still in memory for the next person at that browser.
- Status is **derived from timestamps**, never stored: nothing in this application
  runs on a clock to write "expired" at the right moment.

## Administering accounts

`GlobalRole.ADMIN` was written by `FirstBootAdmin` and read by **nothing** for
most of this project's life: the first-boot account was labelled an administrator
and had precisely the authority of everybody else. This is what the label means.

- **Three powers, and the narrowness is the design.** List the accounts, take one
  out of service, and make somebody else an administrator. Not their trips — `TripAccessService` answers an admin a
  404 on a trip they are not a member of, exactly as it does anybody, and
  `anAdministratorIsNotAMemberOfEverybodysTrips` pins that. Administering the
  instance is authority over accounts, not a way into other people's holidays.
- **Disabled, never deleted.** `users.disabled_at` — a timestamp, because "when
  did this happen" is the first question about an account that was shut off.
  There is no delete and there should not be: expenses and packing items
  reference their user, and a departed member's shares are history the trip still
  needs (`PersonBalance.stillAMember`), so removing the row would silently
  forgive a debt on somebody else's trip.
- **Disabling has to do three things or it does none.** Mark the row, which stops
  the next sign-in (`WanderUserDetailsService` throws `DisabledException`); delete
  every session, because the session *is* the credential and a live one would sail
  past that check for as long as it lasted; and publish `AccountDisabled`, which
  `TripSyncBroadcaster` turns into hanging up the sockets — membership is resolved
  once at the handshake, so a socket left open keeps being told about other
  people's edits. Only the first of the three is visible in the database, which is
  why the test asserts on all three.
- **The disabled check is thrown from the details service, not modelled on
  `WanderUser`.** Adding a component to that record changes its serialized shape,
  and it is Java-serialized into Postgres by Spring Session — so every session
  written by the previous version would fail to deserialize on the first request
  after an upgrade. Signing the whole instance out to ship a feature about
  accounts is a poor trade.
- **An admin may not disable themselves**, and may disable another admin. Not
  paternalism: the caller would lose their own session mid-request, and on a
  one-admin instance — every instance, by default — nobody would be left to undo
  it. The recovery would be psql, which is the situation this whole feature exists
  to end. Disabling *another* admin leaves somebody holding the keys, so it is
  allowed.
- **Administrators are a set, not a role one person holds.** `PUT
  .../accounts/{id}/role` promotes and demotes, and without it `ADMIN` could only
  ever belong to the account first boot created — every rule written about
  *other* administrators described a state nothing could reach. Two things make
  it work. **Both directions end that account's sessions**, because the principal
  is serialised into the session and read back on every request: somebody demoted
  would otherwise keep `ROLE_ADMIN` until their session expired, authority
  removed in the database and still held in fact. And **the last administrator
  who can sign in cannot be demoted** — this *is* the "last owner" check
  `TripMemberService` is pleased not to need, and the difference is that a trip's
  ownership *moves* in one transaction while an instance's administrators are a
  set with no equivalent atomic move. Disabled admins do not count towards it: an
  account that cannot sign in cannot administer anything. Stepping down is
  otherwise allowed, including on your own account, or an instance could never
  change hands. `setDisabled` deliberately has **no** such check, and that is not
  an omission — the caller is an enabled admin who cannot be the target, so one
  always survives; the note in the code says so, because the absence of a guard
  is the kind of thing somebody later adds "just in case".
- **The count is pinned by a unit test, not an integration one.**
  `AdminServiceTest` mocks the repository, because the count is instance-wide and
  the integration suite shares one database that every other test adds
  administrators to — over HTTP the "last" administrator can only be arranged by
  accident.
- **`@PreAuthorize` on the controller class, not a matcher in `SecurityConfig`.**
  Both work; this one travels with the code. `EndpointAuthRatchetTest` cannot
  catch a missing rule here, because it only asks whether an *anonymous* caller
  gets in — an admin endpoint left open to every signed-in account passes it
  perfectly. Annotating the type means a new method is guarded by existing.

### The reset link

The recovery for a forgotten password, and originally the same trick as an
invitation: **a link needs no mail**, and the administrator already has a way to
talk to the person. That is still the default and still the whole mechanism —
"Asking for one yourself" below adds a *delivery* method, not a second kind of
link. Everything about `trip_invites` applies — token never stored, SHA-256 and
not bcrypt (256 CSPRNG bits have nothing to slow down, and a per-row salt would
make the lookup a scan), single use, expiring, revoked rather than deleted,
status derived from timestamps, and a row lock on redeem so two requests cannot
both set a password. `SecureToken` is now the one copy of the mint-and-digest, so
the two features cannot drift apart — and drift here fails silently, producing
tokens that hash to nothing.

Where it deliberately differs:

- **Redeeming is anonymous, and it is the second public endpoint** after
  `/api/config/sign-in`. The invitation preview is authenticated on the argument
  that its holder can register first; that argument does not survive here,
  because everybody who needs a reset is somebody who *cannot sign in*. An
  authenticated reset is a door that opens only for people who do not need it.
  What makes it affordable is that the token is the whole credential and a good
  one, so an anonymous caller gets nothing they did not already hold.
- **A missing token is 404, a real-but-unusable one answers with a reason.** Same
  rule as an invitation, and the reason matters more: "ask for another" is
  actionable, and a 404 on a revoked link reads as a mistyped URL.
- **Redeeming ends every session for the account, with no exception** — unlike
  changing your own password, which keeps the session doing the changing.
  Somebody using a reset link is often recovering an account they believe
  somebody else has open.
- **It does not sign the redeemer in.** Typing the new password at the login form
  is what proves it took, and issuing a session to whoever holds the link is a
  strictly larger thing than letting them set a password.
- **A disabled account can neither be minted for nor reset.** A link that cannot
  work is a worse answer than saying so at the point of minting, and a live link
  must not be a way back into an account somebody deliberately shut off.
- **The password policy applies here too**, which is the third door onto the same
  account — `@GuessablePassword`, same as registration and change-password.
- Throttled per address in its own `LoginThrottle` namespace: the redeem call
  spends a bcrypt round for an anonymous caller, which is the argument that
  already covers `/api/auth/register`.

`AdminRepo` is the **second repo with no offline cache**, after `InviteRepo` and
for the same reasons sharpened — a minted token would be written to IndexedDB,
and a cached list would show a disabled account as active and a revoked link as
outstanding, which is backwards for controls whose purpose is taking access away.
`/reset/:token` and `/forgot` are the two routes outside both the shell and
`authGuard` — the halves of one journey, and both addressed to somebody with no
session; `adminGuard` is a courtesy on `/admin`, since the server refuses
regardless.

### Asking for one yourself

The same link, delivered by the machine instead of by the administrator, for the
instance where recovery cannot go through a person — which is any instance
strangers can sign up to. `wander.mail.enabled` is **off by default**, like the
demo trip and for the same reason: it needs a relay somebody has to sign up for,
and the default has to work on a laptop with no outbound network. Off, the
endpoint 404s, `/api/config/sign-in` says so, the login page draws no "Forgot
password?" link, and the administrator's minted link is the only route — exactly
as before.

- **`MailClient` is a seam, like every other outbound call.** `GeocoderClient`,
  `EnrichmentClient`, `WeatherClient`, and now this: one interface, replaced with
  `@MockitoBean`, which is what keeps the suite off the network and out of
  somebody's inbox. `SmtpMailClient` is the only implementation and **there is no
  provider in it** — Brevo, Gmail, Resend and a Postfix on the next rack all
  speak SMTP, so the configuration is Spring's own `spring.mail.*` and changing
  provider is four lines in `.env`. Same argument as the tiles and the geocoder
  being settings: which service an instance leans on is the operator's decision.
- **The bean exists whether or not mail is on**, which is why `JavaMailSender`
  arrives as an `ObjectProvider`: with `spring.mail.host` unset there is no
  sender bean, and injecting it directly would fail the context on every instance
  that is not sending mail — which is most of them. One always-present bean also
  means the tests have exactly one thing to replace.
- **Nothing here throws upward**, the enrichment rule again. A relay having a bad
  afternoon costs a message, not a 500 on a page whose job is to say "check your
  mail" — and the caller must not be told the difference anyway, which is the
  next bullet.
- **Every outcome is 204.** Address found, address unknown, account disabled,
  relay refused it: the response is identical, because any difference between
  them answers "does this person have an account here", and on a trip planner
  that is most of what an attacker wanted to know. The page says "if that address
  has an account, a link is on its way" and means it literally — it has not been
  told. The honest limit is written down in `PasswordResetService.requestReset`:
  a missing account skips a mint and an SMTP round trip, so the two are
  distinguishable by clock. Closing that would mean mailing nobody or sleeping a
  random interval, and both are worse than the leak. This stops casual
  enumeration, not a patient adversary with a stopwatch.
- **`/api/auth/reset/request` is the third public endpoint**, after
  `/api/config/sign-in` and redeem, and it needs no new argument — everybody who
  needs it is by definition somebody who cannot sign in, which is what already
  bought redeem its exemption.
- **The tightest limit in `LoginThrottle`, and the only one metering an endpoint
  that makes something leave the building.** Each call sends a message on a relay
  somebody signed up for, to a mailbox belonging to a real person, so an
  unmetered version is not just a way to spend this box's CPU — it is a way to
  use this instance to post junk at a third party and get its sending domain
  listed for it. Counted as *attempts* like registration, cleared by nothing but
  the window, and keyed by **address alone**: the email would be the better key
  and cannot be used, because keying on it would make the counter a record of
  which addresses have been asked about. Its own `q:` namespace, separate from
  redeem's `p:`, so asking too often does not also block a colleague at the same
  office address finishing theirs.
- **Minutes, not days.** `wander.mail.reset-expires-minutes` defaults to 60
  against the admin path's days: an emailed link is acted on immediately or not
  at all, and it is sitting in a mailbox that may itself be the thing that was
  compromised.
- **`createdBy` is the account itself.** Literally true — they asked for it — and
  it is what lets the admin's list show who requested a link without a nullable
  column or a migration.
- **`selfServiceEnabled` is asked of the mail client, not of the property.**
  "Switched on" is not "able to send": a blank from-address or an unconfigured
  relay would offer a flow that cannot finish, and the one person who clicks that
  link is already locked out.
- **`passwordResetEnabled` follows `registrationEnabled`'s rule, not the rest of
  the config's**: "not answered yet" reads as *un*available, or the link flickers
  into existence as somebody reaches for it. `ForgotPage` treats a failed config
  read the same way, and says the administrator can still mint one.
- The message is **plain text**, three sentences and one URL. There is no
  template engine in this project and this is not the feature that should
  introduce one; an HTML version would be a second copy of the same words to keep
  in step, and a short plain message from a new sending domain is also the shape
  least likely to be filed as junk — which for this feature is the difference
  between working and not. The log records the **subject only**, never the
  recipient or the body: a reset link in a log file is the thing the whole
  never-store-the-token design exists to avoid writing down.
- **The link's base URL is derived from the request unless configured.** That is
  correct only because `server.forward-headers-strategy: framework` reads the
  proxy's `X-Forwarded-*` — the same headers `LoginThrottle` leans on. The
  failure modes differ usefully, though: a wrong throttle is silent, while a
  wrong base URL produces a link that visibly does not work. Hence deriving it is
  an acceptable default and `wander.mail.base-url` exists for when it is not.

## The demo trip

A worked example trip and a read-only account to look at it with, for a public
instance. `wander.demo.enabled` is **off by default** because this writes rows:
it belongs on a demo box, not on somebody's real one.

- **The visitor is a VIEWER, and that is the whole design.** The content belongs
  to two accounts nobody can sign in as — their passwords are random and never
  printed — and the published account is added to the trip as a viewer. So
  read-only is `TripAccessService` answering 403 exactly as it would anybody
  else, not a client that hides its buttons, and the READ ONLY badge becomes part
  of the demonstration rather than an apology. `DemoSeedIntegrationTest` pins it,
  because nothing else would notice the seeder handing out `EDITOR` one day: every
  write would simply start succeeding.
- **The password is published on purpose.** `/api/config/sign-in` carries
  `demoEmail` and `demoPassword`, and the login page prints them. A credential on
  a public endpoint reads like a mistake, so the reasoning has to be explicit:
  what makes it safe is the *role*, not secrecy, and there is nothing to keep
  back about an account whose only purpose is to read one seeded trip. Both
  fields are empty unless the demo is on, so an ordinary instance publishes
  nothing.
- **It re-seeds on every boot, scoped to the demo owner's own trips.** Dates are
  the reason: a forecast exists for about sixteen days, so a trip with fixed
  dates would quietly lose the best half of its day cards and then become a trip
  in the past — the exact rot the weather feature's "absence is designed for"
  rule makes invisible. Restarting the container is therefore also how a demo
  instance is tidied up after visitors. Scoping the delete to
  `findAllForUser(owner)` is what makes switching the flag on by mistake cost
  nothing.
- **The accounts are made once and never rewritten**, unlike the trip. Otherwise
  the published password would change under whoever was reading the login page.
- **The published account may not leave the trip.** Leaving is the one write a
  VIEWER is entitled to — any member may remove *themselves*, which is how you
  leave a trip — and on a shared login it is an accident rather than a decision:
  one visitor clicking it takes the demo trip away from every visitor after
  them, and the only recovery is the re-seed on boot. `TripMemberService.remove`
  refuses a self-removal by the published account with **409**, and it is
  refused *there* rather than only in the client because a password printed on
  the sign-in page is exactly the situation where a hidden button is not a rule.
  An owner removing them is still allowed, which is how somebody who added the
  demo account to a real trip gets it back off. `DemoAccount` answers "is this
  the published visitor" from `wander.demo.email` — the same thing that makes
  the account special everywhere else — and `SessionUser.demoAccount` carries it
  to the client, which is what lets the trip page stop drawing the button. Note
  that flag is derived per request and deliberately **not** on `WanderUser`:
  that record is Java-serialised into the session table, so a new component
  would fail every session written by the previous version.
- **`DemoContent` is a table of constants, not something fetched at boot.**
  Seeding has to work with no outbound network, must not spend Nominatim's and
  Commons' donated capacity every time a container restarts, and must produce the
  same trip every time so the screenshots in README stay true. The `osm_ref`s are
  real, so enrichment works on a demo place exactly as on a searched one — and
  the photo credits are the ones Commons actually returned, because `setPhoto`
  refuses a URL without its author and licence and an invented author would be a
  licence breach dressed up as sample data.
- **It writes through the entities, not the services.** A service call would
  publish `TripChanges` events to sockets that cannot exist yet, and would check
  permissions on behalf of a caller that is not a request. `ExpenseSplitter` is
  still used for the equal splits, so the remainder lands where it really lands
  rather than where a seeder guessed.
- The content is chosen to have the *shapes* worth showing: an exact split that
  is not half and half, an equal split of an **odd** amount (¥3,121 for the Nara
  trains, so the spare minor unit visibly lands on the lowest user id — every
  other amount here halves cleanly, and without one of these the rule that a
  split always sums to its total cannot be seen), a payment (so a balance is
  partly settled), an expense **paid in another currency** (the flights, bought
  from home months before anybody was near a yen), a shared packing pile beside
  assigned items, days with no places at all, and a flight whose arrival zone
  differs from its departure.
- **The seeded conversion is marked as a rate somebody entered, and that is
  argued rather than convenient.** A looked-up rate belongs to a published day,
  and this trip is re-dated on every boot — so stamping a market quote onto
  whatever date the seeder produced would be the `places.category` mistake in
  another costume, a number presented as fact that came from something that does
  not know. It also keeps the seeder true to its own rule of needing no outbound
  network. `DemoContent` therefore declares the amount in **pounds** with the
  rate beside it and the seeder converts through `CurrencyConversion`, so the
  yen figure is never written down and cannot drift from the rate that produced
  it. `theDemoShowsAnExpensePaidInAnotherCurrency` pins that, for the reason the
  VIEWER role is pinned: a conversion that stopped happening would leave a ledger
  that still adds up and still renders, quietly counting pounds as yen on the one
  instance strangers are looking at.
- **Trips the shared account makes for itself are swept on a schedule**, and
  that is a second job rather than part of the re-seed because the re-seed
  cannot do it: its delete is scoped to the demo *content* owner, which is what
  makes switching the flag on by mistake cost nothing, and a trip the published
  account owns is outside that scope. So it survives a restart, a rebuild and a
  redeploy alike — the graffiti is rows, and only deleting rows removes it.
  Without the sweep, whatever the last visitor typed is on the next visitor's
  trip list, because they are the same account.
  `DemoSweeper` deletes what that account **owns** — never what it can *see*,
  which would take the seeded trip it is a viewer on and would look like it was
  working — announces each deletion through `TripChanges` so somebody reading a
  swept trip is navigated away rather than left on a page that now 404s, and
  runs once at startup as well, a scheduled task's first pass being immediate.
  `theSweepRemovesWhatAVisitorMadeAndLeavesTheSeededTrip` pins both halves.
- **The visitor is told, on the page where they would create one.** A sweep is
  the only place in wander where somebody's work disappears without them asking,
  and the project's rule about stale data — show it, but label it — is the same
  promise read the other way round, so the trips page carries a notice for that
  one account: everyone shares it, anything you make is visible to them, and it
  is deleted every N minutes. `SessionUser.demoAccount` says who, and
  `InstanceConfig.demoSweepMinutes` says how often — a *setting*, so it travels
  from the server rather than being a constant in the client, because a page
  promising 45 minutes on an instance configured for 10 would be believed. Zero
  means no sweep and no sentence, which is also what "not answered yet" looks
  like, so there is no state in which the page invents a schedule.
- **It is the project's first thing that runs on a clock**, and the exception is
  argued rather than assumed: everywhere else state is derived from timestamps
  precisely so nothing has to fire at the right moment (an invitation is never
  written "expired"). A junk trip cannot be derived away — the trip list is the
  ordinary one every account uses. The alternative was a cron restarting the
  container to make the seeder run again, which drops every socket, hands every
  reader an outage and re-dates the trip under them, all to achieve one delete.
  `DemoSweeper` is declared by `DemoScheduling`, conditional on the demo flag, so
  an ordinary instance has neither the bean nor a scheduler thread.
- What it does **not** solve: the demo account can still create trips of its own
  — the sweep clears them within the window rather than preventing them, which
  is deliberate, since "can I actually make a trip" is most of what a visitor
  came to find out — and it shares one `UpstreamQuota` budget with everybody
  using it. The quota is the protection.

## Packing lists

The first thing in this project that belongs to a *person* on a trip rather than
to the trip.

- **One list, with an optional assignee.** `packing_items.assignee_user_id` is
  nullable and that null is meaningful — it is the shared pile, not a missing
  value. A list per member plus a shared one would have been two concepts, and
  reassigning would be a delete and a create instead of a field change.
- **No `sort_order`.** A packing list is grouped, not ranked, so creation order is
  enough and none of `PlaceService`'s renumbering is needed. Adding drag ordering
  later is a column, not an unpicking.
- **Ticking has its own endpoint** (`PUT .../packed`), because it is by far the
  commonest action and sending the whole item to flip a boolean lets a tick
  silently undo a rename that arrived in between.
- **The server groups**, unlike money where the point was that the client
  calculates nothing. Here it is because the server knows the member list: a
  member who has added nothing still needs a section, since an absent one reads as
  missing data. Somebody who leaves keeps their items, flagged `stillAMember`.
- **`PackingRepo.setPacked` is the second optimistic write**, after
  `PlaceRepo.move`, and for the same reason — the checkbox has already moved under
  the user's finger. It carries the ticker's *name* along with the boolean, or the
  row says "packed" without saying by whom until something forces a re-read, which
  on a shared item is the useful half. Unlike `move` it does not re-read: the
  server's answer to a tick is exactly what was sent.

## Reservations and the clock

The first thing in the project with a *time* rather than a date, and the only one
that crosses a timezone.

- **Both halves are stored**: `starts_at TIMESTAMPTZ` — the instant — and
  `start_zone`, the IANA id it was booked in. Postgres keeps a `TIMESTAMPTZ` as
  UTC and throws the original zone away, so without the second column "09:15"
  could never be shown again. A flight has two zones, because it lands somewhere
  else; `end_zone` defaults to the start's, which is right for everything that
  does not fly.
- **The instant is what everything sorts by.** 23:00 in Tokyo really does come
  before 08:00 in London the next morning, and only the instants say so. Sorting
  on local times would put them the wrong way round.
- **The client sends wall-clock plus zone; the server makes the instant.** Java
  carries the full tz database and `atZone` has documented answers for the two
  awkward hours a year (a nonexistent time shifts forward, an ambiguous one takes
  the earlier offset) — both better than refusing a booking somebody holds. The
  browser only goes the other way, instant → zone, which is what `Intl` is good
  at; that lives in `core/zones.ts` and nowhere else.
- **The response carries the local wall time too**, redundantly, so an edit form
  puts back exactly what was typed instead of reconstructing it from an instant
  and a zone.
- **An unknown zone is a 400.** It is the one field a client can get wrong in a
  way that makes the whole record undisplayable, so it is checked against the tz
  database rather than trusted.
- **A zone is only shown when it is not the reader's own**, or every row of a
  domestic trip carries noise. The exception is a flight's arrival, which is
  labelled whenever it differs from the departure.
- **A booking carries a phone number, and every kind does.** Place enrichment
  already surfaces one from OpenStreetMap, but that only knows what the map knows
  — a chain hotel's node often has none, and when it does it is the switchboard
  rather than the direct line on the booking email. It is *not* gated on `HOTEL`,
  and certainly not on `places.category`: a restaurant holding a table, a car hire
  desk and a ferry terminal all want a number, and branching display on the
  geocoder's own word for a place would promote a decorative label into a
  load-bearing one. Stored as free text and **never parsed or reformatted** —
  "+81 3-4333-1234 (front desk)" is a realistic and useful thing to write down,
  and a validator here would refuse numbers that work. Only the `tel:` href is
  reduced (`telHref`): everything from the first bracket is dropped and what is
  left keeps its digits and a leading plus, with **nothing added** — a bare local
  number stays local, because guessing a country code is how you ring a stranger
  at 1am. It prints, which is half the point: on paper there is nothing to tap.

## Booking import

Reading a booking out of a confirmation: an email, a PDF, an HTML page, a
calendar attachment or an Apple Wallet pass.

- **It writes nothing, and that is the whole shape of the feature.** `POST
  .../reservations/import` answers with *drafts*, the client prefills the booking
  form the reservations page already has, and the user saves through the ordinary
  `createReservation`. So there is one write path, one set of validation rules, no
  `TripChanges` event, and nothing to roll back — and a misread file costs a
  correction on screen rather than a wrong row that live sync puts on everybody
  else's screen within the second, with no undo.
  `importingWritesNothing` is the load-bearing test in the suite: everything else
  here could work perfectly and the feature would still be wrong if a parse
  reached the database.
- **The parsing is borrowed, not written.** `BookingExtractor` has one
  implementation, and it execs KDE's `kitinerary-extractor` — 349 provider
  extractors (airlines, railways, hotel chains, and the white-label platforms
  small hotels actually run on: Amadeus, Availpro, Caesar Data, Direct-Book),
  schema.org JSON-LD **and** microdata, `.pkpass`, IATA boarding-pass barcodes,
  phone numbers through libphonenumber. A hand-written parser competing with any
  of that would be worse in every way, and per-vendor parsers of our own would be
  a treadmill that fails *silently* when a template changes.
- **One exception, and it is a patch rather than a parser.** `extractors/` holds
  extractor scripts of our own, handed to the engine as
  `--additional-search-path` (`wander.booking-import.extractor-search-path`,
  which the image points at `/app/extractors`). The bar is in
  `extractors/README.md` and it is narrow: **upstream must already have an
  extractor for that vendor and it must nearly work.** `ana-fullyear.js` is
  KItinerary's own `ana.js` with a four-digit year, because an ANA SKY WEB India
  e-ticket prints `31OCT2026` where the upstream regex wants `31OCT26` — so no
  leg matches and the import answers "nothing recognised". Writing the *first*
  parser for a vendor is still the treadmill; correcting a year format is not,
  and each file here is a patch waiting for upstream to take it.
  **The year is matched as four digits only, never two-or-four**, which is what
  keeps the patch and the built-in extractor disjoint: both are loaded and both
  are offered every matching document, so a script that could match a
  Japan-issued ticket too would import every leg of it twice. That, and the
  manifest pairing, is what `ExtractorManifestTest` pins — every way of
  mis-wiring an extractor is silent, because a script that fails to load looks
  exactly like a document nothing recognised.
- **`poppler-data` is a hard dependency of reading a PDF, not a nicety.**
  Without those CMap tables poppler cannot decode the text of a document in a
  CJK character collection, and KItinerary hands every matching script an
  **empty** `pdf.pages[n].text`. Nothing errors: each script returns nothing and
  the import says "nothing recognised", which is indistinguishable from a
  template nobody has written an extractor for — and it sent this project
  looking for a bad regex first. A Japanese airline's e-ticket is exactly such a
  document. 13MB unpacked of the image's 737, 4MB of the 291 a pull fetches.
- **It resolves the timezone itself**, from its own airport and address
  databases — `HND` becomes `Asia/Tokyo`, with a flight's two zones separately.
  That is why nothing in this project ships a table of IATA codes: one was
  designed and then deleted when the engine turned out to answer the question
  already. The `QDateTime` wrapper carrying `timezone` beside `@value` is
  precisely the wall-clock-plus-zone pair `reservations` stores, which is what
  made borrowing the engine worth its weight.
- **A subprocess, and that is a feature.** It is C++ and Qt behind poppler and
  ZXing parsing a file a stranger mailed somebody, so a malformed PDF that
  segfaults or wedges the parser kills a child on a timeout instead of taking the
  instance down. Nothing is linked, nothing is generated at build time, and
  Gradle knows nothing about any of it — the relationship `backup/backup.sh` has
  with `pg_dump`. The binary is resolved to an **absolute path once at startup**
  and probed with `--version`; re-resolving through `PATH` per call would mean
  anyone able to write to a directory on it gets their binary run as the app user.
- **The upload is never stored.** It is written into `/dev/shm` at 0600 for the
  tenth of a second the extractor needs and deleted in a `finally` — a
  confirmation holds a booking reference and sometimes a passport number, and the
  nightly dumps leave the machine, which is the same bargain
  `trip_invites.token_hash` makes. Nothing about the file, its name or its
  contents is logged, for the same reason.
- **`IcsBookingReader` exists because of one measured gap, not on principle.**
  KItinerary returns **nothing at all** for a generic `VEVENT` — verified
  standalone *and* attached to a multipart email, with a context date supplied
  and `--no-validation`. That matters because a calendar attachment is what a
  hotel, restaurant or tour operator with no vendor extractor sends, and that
  long tail is exactly the self-hoster's small local hotel. It is pure and static
  rather than a second implementation of the seam: there is nothing external to
  stand in for.
- **Reaching into the MIME tree is what makes that reader reachable.** Nobody
  has a bare `.ics`; they have the email it was attached to. `jakarta.mail` is
  already on the classpath from the mail starter, and a MIME parser is a MIME
  parser.
- **A date-only `DTEND` is exclusive.** RFC 5545 writes a stay from the 15th to
  the 18th as `DTEND;VALUE=DATE:20261019`. Read it literally and every imported
  hotel stay checks out a day late — invisible, because the number on the screen
  is the number in the file. `treatsAnAllDayEndAsExclusive` is what holds it.
- **Date and time are separate fields on a draft, and neither is required.**
  That is why `ReservationDraft` is not shaped like `ReservationRequest`: a
  `LocalDateTime` cannot say "the 15th, time unknown", which is what an all-day
  entry and a ticket with no time both give you. Forcing midnight in would invent
  a departure hour. They also line up with the page's existing draft signals, so
  prefilling is assignment rather than parsing.
- **Nothing is guessed from prose.** A calendar draft's kind is always `OTHER`
  even when the summary says "Hotel" — inferring one is the `places.category`
  mistake, a decorative label promoted to load-bearing. No confirmation code is
  fished out of a description either; the description travels verbatim into the
  notes, so the code is in front of the user either way, and guessing which token
  is the reference is how the field gets confidently filled with a flight number.
  An unrecognised `TZID` leaves the zone null rather than substituting one.
- **There is no PDF text-salvage layer, and that is a decision.** PDFBox plus
  token regexes, to produce a title and a pile of notes in the case where 349
  vendor scripts found nothing, is the worst ratio of code to value in the
  feature — and its only possible output is a low-confidence draft. An empty
  answer names the readers that looked instead.
- **Two booleans on `/api/config`, not one.** `bookingImportEnabled` is the
  switch; `bookingDocumentImport` says whether documents can be read or only
  calendars, because an image built without the extractor still imports `.ics`
  and reporting a PDF there as "nothing recognised" would send somebody hunting a
  fault in a file that is fine. `bookingImportEnabled` **inverts the rest of
  `/api/config`'s rule** and reads "not answered yet" as *un*available, like
  `registrationEnabled`: an Import button that appears and then errors is worse
  than one that appears a beat late.
- **Not metered.** `UpstreamQuota` is for endpoints that spend somebody else's
  donated capacity, and this one calls nothing outward — a local process, ~170ms,
  on a trip the caller can already edit. The limit that matters is
  `spring.servlet.multipart.max-file-size`, plus the extractor's own timeout and
  output cap. An unrecognised file is a **200 with an empty list**, the enrichment
  rule again: a confirmation this instance cannot parse is a nicety not
  delivered, not an error.
- The image cost is real and measured: **282MB to 737MB unpacked, 134MB to
  291MB compressed** — +157MB to pull, which is the half an operator waits for.
  ~90MB peak resident for ~170ms per import. All amd64. Re-measure the same way
  or the numbers drift apart: compressed is the layer sizes in the manifest,
  unpacked is the sum of the layer diffs, and `docker image ls` is neither — it
  reports the unpacked snapshot and answered 1.03GB to the same question. See
  the Dockerfile comment for the
  two things not to do to it — Mesa cannot be deleted (Qt6Gui hard-links libEGL,
  which pulls libgallium at process start), and the package is in Alpine
  `community` rather than `main` while the base tag floats.

On the client:

- **The prefill fills the same signals the form already had**, so there is one
  editor and one save path. Import is a prefill, not a second way to create a
  booking — the same argument as the place panel being the only editor for a
  place.
- **`ReservationRepo.importFile` is not a write**, so it skips `write`, raises
  its own `importing` signal rather than `saving`, and does not re-read the list
  afterwards. It has **no `OfflineCache`**: parsing happens on the server, and a
  cached result would be last week's file.
- **It must be handed a `File`, never a `Blob`.** The generated client puts it
  through `FormData.set`, which only carries a filename for a `File` — a plain
  `Blob` arrives named "blob", and the server picks its reader from the
  extension, so every upload would be refused. Nothing fails visibly at compile
  time; the parameter's type is `Blob`.
- **One draft goes straight into the form; several are listed.** Both stay on
  offer until each is saved, and `cancel` deliberately does *not* clear them —
  abandoning one leg of a return trip must not throw the other away. A draft is
  retired by a successful save, not by being picked.
- **The zone picker has to accept a zone the browser does not list.** A document
  that gave an offset without naming a zone comes back as `+09:00`, which is not
  in `Intl.supportedValuesOf`, so `zones` is a computed that unions the imported
  ones in. Without it the select would show something else while the model held
  the offset — the quiet way to move a booking nine hours.
- **`prefillNote` says what the file did not.** A zone defaulted to the reader's
  own looks exactly like one the document named, and that is the gap nobody would
  notice. It names a missing start time *and* a missing end time separately,
  because an all-day calendar entry has a date at each end and a clock at
  neither, and being told only about the start leaves the reader hunting for why
  the form will not submit.
- `bookingImportEnabled` defaults to **false** in `InstanceConfigStore`, which
  inverts that store's usual optimism, for the reason given above.

## Routes

Sorting a day by how long it takes to get between its stops, over OSRM, with
three profiles — and the finished order handed to Google Maps.

- **Off by default, and this is the one upstream where that is not caution.**
  Nominatim, Open-Meteo, Frankfurter and Wikimedia all run a public service
  anybody may politely use; routing has none — the OSRM project's demo server
  is a demo, not capacity to build on. What a self-hoster does have is that
  OSRM is easy to run over a Geofabrik extract, so `wander.routing.base-url`
  defaults to `localhost:5000` and waits.
- **`/table`, never `/route`.** The optimiser needs the cost between every pair
  of stops, which is one call for a whole day instead of n². The shape of the
  path between two of them is what the person's phone is for.
- **Preview writes nothing, and `previewWritesNothing` is the load-bearing
  test** — booking import's rule again. `POST …/route/preview` calls upstream
  and answers with a proposal; `POST …/days/{date}/order` is a pure write that
  takes place ids and renumbers, with no upstream call in it. So applying is
  not a second chance to spend the routing budget, one thing on the server
  decides what order a day is in whether the order came from a drag or an
  optimiser, and a misread route costs a Discard rather than a rearranged day
  appearing on everybody else's screen a second later with no undo.
- **Locked is a position, not a preference.** `places.locked` pins a stop to its
  index and the optimiser permutes the rest into what is left; a 2-opt reversal
  is skipped outright if the stretch it would reverse contains a pinned slot,
  which is the whole mechanism and means there is no second code path for
  locked stops to disagree with. It is enforced *again* in `reorderDay`, because
  a client that ignored the flag would otherwise make the lock a suggestion.
  Dragging a locked place is still allowed: a lock constrains the optimiser, not
  the person.
- **A place with no coordinates is locked implicitly.** It was never sent
  upstream, so moving it would be rearranging somebody's plan around a guess —
  and dropping it to the end would be worse, since it is usually the
  note-to-self ("pick up tickets") that was typed rather than searched. The
  proposal says which stops did not move and why, because being told "these have
  no location" is the difference between a disappointing answer and a mysterious
  one.
- **`places.category` stays decorative, so there is no hotel anchor.** Inferring
  an anchor from the geocoder's own word would promote a label into something
  load-bearing and would be null for every place typed by hand. Locking the
  hotel is the same thing said explicitly.
- **No cache, and that is worth saying out loud** because every other upstream
  here has one. The key would be an ordered set of coordinates that changes the
  moment anything on the day moves, so the hit rate is nil outside a double
  click — which `RateGate` already covers, with `UpstreamQuota.route` behind it.
- **Off is a 503, not a quiet "no change".** The forecast swallows an outage
  because weather is decoration; a sort that answers "already the best order"
  when it never asked anything is a claim about somebody's day. The client is
  told through `routingEnabled`, which follows `bookingImportEnabled`'s rule of
  reading "not answered yet" as *un*available.
- **A null cell in the matrix is refused, never read as zero.** OSRM answers
  `null` for a stop it cannot snap to a road, and zero would make that stop the
  nearest thing to everywhere — the optimiser would route through it first and
  the day would come back confidently wrong. Coordinates go into the URL
  **longitude first**, which is the opposite of everywhere else in this project
  and fails by routing into the sea rather than by erroring.
- **The improvement loop scores a candidate by walking the whole route**, not by
  the usual two-edge delta. Driving is asymmetric — one-way systems — and the
  shortcut assumes reversing a stretch costs the same both ways.
- **The Google Maps link is built in the client**, from the day's coordinates,
  like a single place's Directions menu: wander proxies nothing to Google. The
  `dir/?api=1` form carries an origin, a destination and **nine** waypoints, so
  a longer day links the first eleven stops and the title says so rather than
  letting Google drop the afternoon silently.
- The profile is **one choice for the page**, not one per day: it is a fact
  about how the trip is being got around, and setting it on each of eight days
  is eight chances to leave one wrong. `bicycling` is Google's word for cycling.
- Locking lives in the **place panel**, and the row gets an indicator only. The
  row's `.row-actions` strip is already six icons that stay on permanently where
  there is no hover, and an `sr-only` name in a row once broke six browser tests
  at once.

## The forecast on a day card

A fifth upstream, behind the same shape as the others: `WeatherClient` is the seam
the tests replace, `openMeteoGate` is its own `RateGate`, and
`wander.weather.enabled` turns it off for an instance with no outbound network.

**Open-Meteo, because it needs no API key** — a self-hoster should not have to
register an account with a weather company. Verified from its terms: free tier is
"less than 10'000 API calls per day, 5'000 per hour and 600 per minute",
**non-commercial**, and the data is **CC BY 4.0, attribution required**. So the
credit travels to the client *with the data*, exactly as the tile attribution
travels with the tile URL, and an operator pointing this at something else changes
one setting and the credit follows.

- **A forecast has a horizon (~16 days), and that is designed for rather than
  hidden.** A day outside it produces no row, no view and **no request**. A trip
  next July shows nothing at all until July, and the day card renders nothing — not
  a dash, not a placeholder. `WeatherIntegrationTest` asserts the *absence*, which
  is the half that would rot silently.
- **A day's location is its first located place**, and a day with nothing on it
  borrows from the **nearest planned day**, preferring the one before. Not the
  average of the trip's points: on a Tokyo-then-Kyoto trip that average is a
  mountain range neither is near, and it costs a third outbound call to ask about.
- **One call per location, not per day.** A response covers the whole horizon, so a
  fortnight in one city costs one call. This is the difference between fitting in
  that daily budget and not.
- **A stored row remembers the point it was fetched for**, so moving a day's first
  place refetches it. Age alone would keep showing Kyoto's weather for a day now
  spent in Kanazawa, for the whole TTL. The TTL is *minutes* (180), not days: a
  forecast changes through the day, unlike an enrichment.
- **An outage here is not an error.** The service logs and returns what it has,
  possibly nothing, and the endpoint answers 200. That is the opposite of the
  geocoder, where silently returning no results would read as "no such place" —
  weather is decoration on a page whose job is the itinerary.
- **The response is columnar**: parallel arrays under `daily`, assembled by index.
  A short array is the horizon, not an error, and a hole in one column must not
  become a zero on somebody's card. Both are in `OpenMeteoWeatherClientTest`.
- **`WeatherCodes` maps WMO codes to words on the server**; the client picks an
  icon from a six-way band of the same code. The words are content and want one
  copy; the glyph is presentation.
- **The forecast is the one read not cached offline.** `WeatherRepo` skips
  `OfflineCache` deliberately — a prediction with a shelf life of hours, shown with
  no way to say how old it is, is worse than an empty space. No connection, no
  weather, itinerary unaffected.
- `InstanceConfig.weatherEnabled` exists so the client does not make a request it
  already knows the answer to. The trip page asks when a computed
  `weatherAnchors` string changes — each day's first location, rounded — so
  renaming a place or reordering two restaurants does not refetch.

## Live sync

Two people on one trip see each other's changes without reloading.
`/api/ws/trips/{tripId}` is a plain WebSocket — no STOMP, no SockJS: the client
sends nothing but keepalive, and the payload is one small event.

- **Invalidation, not state.** A frame says *what* changed (`ITINERARY`,
  `MEMBERS`, `EXPENSES`, `PACKING`, `RESERVATIONS`, `TRIP_DELETED`), never what it changed to, and the client
  answers by re-reading. A new kind has to be added to the union **and** to
  `parse()` in `core/trip-sync.ts`, which drops anything it does not recognise —
  forgetting the second half looks exactly like a broken socket. The server owns place ranks and renumbers a whole day on every
  move, so sending state would be a second serialisation path that can disagree
  with `GET /itinerary` — and `PlaceRepo` already re-reads after its own writes.
- **Broadcast after commit.** Services call `TripChanges`, which publishes a
  Spring application event; `TripSyncBroadcaster` listens at
  `AFTER_COMMIT`. A plain `@EventListener` would announce writes that then rolled
  back, and no HTTP test would notice. `TripChangeCommitTest` is what holds this.
- **The handshake is the only access check, and `EndpointAuthRatchetTest` cannot
  see it.** That test walks `RequestMappingHandlerMapping`, and a handler
  registered through `WebSocketConfigurer` is not a `@RequestMapping`. So
  `TripSyncHandshake` authenticates *and* calls `requireMember` itself, and
  `TripSyncIntegrationTest` is the ratchet for this one endpoint. Do not remove
  those two tests.
- **Losing access closes the socket.** Membership is resolved once, at the
  handshake, which is only safe because `TripChange.revokedUserId` makes the
  handler hang up on somebody who was removed — and a deleted trip closes every
  socket on it. A socket that outlived its membership would keep being told about
  a trip its owner may no longer read.
- **Sessions are wrapped in `ConcurrentWebSocketSessionDecorator`.** A
  `WebSocketSession` is not safe for concurrent sends, and broadcasts arrive on
  whichever thread committed — two people saving at once is the normal case.
- The dev server needs `"ws": true` on `/api` in `web/proxy.conf.json`, or the
  handshake 404s under `npm start` and works only in the packaged jar.

## The printed itinerary

`/trips/:id/print` — the one page in this project designed for paper.

- **No PDF library.** `window.print()` is already a PDF exporter in every browser,
  it honours the reader's paper size, it works offline from the cache, and it adds
  no endpoint and no dependency. A server-side renderer would have meant bundled
  fonts and a second layout engine to disagree with the one that drew everything
  else.
- **Its own route, not print styles on the trip page.** That page carries a map, a
  People panel and a day's worth of controls, so printing it would be a long list
  of things to hide — and it would still be missing the bookings, which live on
  another page and belong on the same sheet.
- **It opens with a cover, and the cover *is* the header.** Name, destination,
  the date range, how many days, who is coming and what it has cost — then
  `break-after: page`, which is the whole difference between a cover and a
  banner. Growing the existing header into it rather than adding a second block
  is deliberate twice over: a title printed twice is a title printed twice, and
  a second `<h1>` carrying the trip's name would make "the heading called Tokyo"
  ambiguous to the browser suite. The rule sits on the wrapper, not on the `h1`,
  which the stylesheet already forbids breaking after.
- **The total on the cover is the server's, never a sum of the day figures.**
  They legitimately differ: an expense's date is not range-checked against the
  trip, so the flight bought in March is in `ExpenseSummary.totalMinor` and on no
  printed day. Summing `spentMinor` here would be wrong *and* would be the client
  computing money, which the ledger's rules forbid outright. It is null rather
  than zero when nothing has been spent, like `spentMinor` itself — a cover
  printing "Total spent 0.00" states something nobody entered. The cover is what
  took the page from two reads to four (members and the ledger join the
  itinerary and the bookings); all four are read-through cached, so it still
  assembles from the device with no signal.
- **Two toggles, both remembered, and photos default to off.** `One page per day`
  adds `one-day-per-page` to the document, which is a `break-before: page` on
  every `.print-day` — adjacent forced breaks collapse, so the cover's own break
  leaves no blank sheet before day one. `Print photos` is off by default because
  paper is white and ink is expensive, the same argument the stylesheet's forced
  white background makes. Both live in localStorage under `wander.print.*`, the
  shape `ThemeService` uses: a print choice you must make again on every reprint
  gets made wrong once and then printed thirty times. Whether a break actually
  landed is the print engine's business and no browser will say, so the browser
  test asserts on the class and stops there.
- **A printed photo carries its credit under it, not in a tooltip.** There is
  nothing to hover on paper, and a Commons image is licensed per image.
  `photoThumbUrl` is what prints — 640px from Commons, and never null when a
  photo exists — sized in *millimetres* in the print stylesheet, this being the
  one place in the application whose output is measured in paper. It stays an
  `<img>` rather than a background, so a reader with background graphics
  switched off still gets the picture.
- **Bookings are folded into the day they happen on**, unlike on screen where they
  are their own list. On paper you follow an itinerary rather than maintain it.
  A booking outside the trip's range — the flight out the night before — gets its
  own "Around the trip" heading rather than being dropped, which is what grouping
  alone would do.
- **Every time carries its zone**, which is the *opposite* of the screen rule
  (`zoneLabel` shows one only when it differs from the reader's). A printout is
  read somewhere other than where it was made, so the zone it was printed in tells
  the reader nothing, and an unlabelled 09:15 is a bad thing to hand somebody
  heading for an airport.
- **`isoDayInZone`, never `dayInZone`, for keying.** `dayInZone` formats for a
  human ("12 Jul 2027"); using it as a key silently matches no day at all, so every
  booking vanishes into "Around the trip" with nothing logged and no request
  failing. That is exactly what the first version did, and it is why `zones.spec.ts`
  exists.
- **Screen furniture carries `print-hide`** — the shell header, the offline banner,
  the page's own toolbar — rather than being matched by position, because the
  header sits two elements deep in `app-shell` and a descendant chain would break
  the first time somebody wrapped it. The whole print block is scoped to
  `body:has(.print-document)`, so a stray Ctrl-P anywhere else is untouched.
- `.print-block` on a day, a booking and a place sets `break-inside: avoid`, so
  they are not split across a page boundary when they would fit whole on the next.
- The footer says when it was printed. On paper that matters in a way it does not
  on screen: the document stops being true the moment somebody edits the trip, and
  the date is the only clue its reader has.
- Note the print test uses `page.emulateMedia({ media: 'print' })`. The two things
  that would ruin a printout — the shell coming along, the toolbar printing itself
  — are invisible on screen by definition, so a test that only checked content
  would pass on a page that prints a navigation bar across every copy.

## Unit tests in the client

`cd web && npm run test` (vitest, via `@angular/build:unit-test`). There are
two specs — `core/money.spec.ts` and `core/zones.spec.ts` — and that is the shape
to keep: the client is tested through the browser suite, except where a pure
function deserves better than that. Both qualify for the same reason. Money
parsing does because "12.345" quietly becoming 12.34 is invisible from the
outside; `isoDayInZone` does because a wrong answer throws nothing, logs nothing
and fails no request — a booking keyed by it simply never appears under any day.

The Angular CLI needs Node ≥ 22.22.3 and the machine's Node may be older; Gradle
downloads its own at `web/.gradle/nodejs/`, so
`PATH=web/.gradle/nodejs/node-*/bin:$PATH npx ng test --watch=false` works when
`npm test` refuses.

## Offline reads

With no signal the app shows what it last saw. **Reads only** — writes are refused
rather than queued, because a replay queue forces the conflict resolution that
live sync deliberately never needed, and its plausible answers overwrite somebody
else's work.

- **A service worker for the shell, nothing else.** `ngsw-config.json` has asset
  groups and **no `dataGroups`**: without a worker the browser cannot fetch
  `index.html` at all and there is no app to show a cache to, but API caching
  belongs in the repos where a page can say how old a copy is.
- **`OfflineCache.readThrough` is the whole mechanism.** Fetch, cache, return; on
  a *network* failure serve the last copy with the time it was saved. A 401, 404
  or 500 always rethrows — a deleted trip must not resurrect from a cache.
- **An unreachable server is status 0 *or* 504.** Once the worker controls the
  page it synthesises a `504 Gateway Timeout` for anything it cannot fetch rather
  than letting the request reject, so with the worker installed — the exact state
  offline depends on — an outage does not look like a network error. Getting this
  wrong signs people out on a train, and it did: see `isNetworkError`.
- **Only a 401 clears the cache.** Any other failure leaves it alone, because the
  answer is unknown and unknown is not grounds to delete somebody's trips.
- **`SessionStore.restore` is the hinge.** It runs in an app initializer and
  `authGuard` gates every route on it, so an unreachable server must fall back to
  the cached identity. Treat it as "signed out" and the app bounces to a login
  form it cannot submit, with a full cache behind it.
- **Cache entries are keyed by user id and the store is cleared on sign-out.**
  Both, not either: this is the only place wander keeps trip data on a device, and
  without both the next person at that browser can read the last one's itineraries
  and booking references.
- Every page says "Saved copy · 2 hours ago" when it is showing one, and the shell
  says so once when offline. Showing stale data without saying so is the thing
  this project has refused everywhere else.

## Installing it

The other half of the service worker. Offline reading gave wander a worker and
a cache; a manifest is what lets a browser turn that into an application with a
name, an icon and a window — and `ContentSecurityPolicy` has carried
`manifest-src 'self'` since it was written, waiting for the file.

- **`start_url` is `/`, not `/trips`.** The guard already answers "signed in or
  not" for every other way into the app, and a second answer to "where does
  this start" is a second thing to keep in step.
- **`.webmanifest` is in neither Spring's `mime.types` nor Boot's table**, so
  without `SpaFallbackConfig`'s `MimeMappings` customiser the manifest is served
  with no usable content type. Browsers parse one regardless, which is exactly
  why this is easy to get wrong and impossible to notice.
- **Three icon shapes from one drawing.** `public/icon.svg` is favicon.svg's
  geometry with the colours *fixed* — a tab icon may follow the reader's chrome,
  an app icon may not, because it is drawn on a launcher this application knows
  nothing about. `scripts/render-icons.mjs` composites the PNGs: full-size for
  `any`, shrunk to the centre 80% on a full-bleed tile for `maskable` (Android
  crops to whatever shape the launcher likes and guarantees only that circle),
  and an opaque 180px `apple-touch-icon.png`, iOS reading no manifest icon and
  compositing transparency onto black.
- **The renderer is Playwright's Chromium, and its output is committed.** No
  `sharp` or `resvg`: a native module per platform to draw four pictures that
  change never, when the suite already carries the engine that will draw the
  icon in the browser. `npm run icons` is run when the mark changes; the Docker
  build and a fresh clone must not need a browser download. Unlike
  `web/src/app/api/`, CI does **not** check it for drift — the input is a
  drawing, not a contract that moves with every DTO.
- **Installing is what made an update prompt necessary.** ngsw serves a new
  build on the *next load*, which is fine for a tab and wrong for a standalone
  window: no reload button, and people leave one open for the length of a
  holiday. `core/app-update.ts` watches `SwUpdate.versionUpdates` and the shell
  offers a reload beside the offline notice. It **asks rather than reloading** —
  a reload throws away whatever is in a form, and ignoring it costs nothing.
  It also polls (six hours, plus `visibilitychange`), because ngsw checks on
  load and never again and the whole problem is a window that does not load.
- **The manifest test parses it rather than checking the status.** Same silent
  failure as the map worker: a missing file under the SPA fallback comes back
  200 with `index.html` in it, and now that the extension is mapped it would
  carry `application/manifest+json` too.

## Browser tests

`cd web && npm run e2e` (Playwright) against a running instance — start the app
first; `WANDER_E2E_URL` overrides the default `http://localhost:8080`. These exist
because two real bugs were invisible to the Java suite: responses documented as a
wildcard media type made the generated client request Blobs instead of JSON, and
the CSRF cookie needs one GET before the first POST. Both looked perfect to curl.
The suite asserts on console errors too — a template that throws every change
detection still renders, so a status code alone proves nothing.

**Service workers are blocked for the suite** (`playwright.config.ts`), and the
offline test opts back in for its own context. With a worker controlling the page,
`page.route` no longer intercepts what passes through it — which silently broke
the two geocoder-stubbing tests the moment offline support landed.

**Rendered text is not template text.** `innerText` returns what CSS produced, so
a chip with `uppercase` reads "SAVED COPY" and a case-sensitive check for "Saved
copy" fails against an element that is right there.

**Match item text with `{ exact: true }`.** Rows carry their subject's name in the
screen-reader labels of their controls ("Rename Tent", "Remove Tent", "Who is
bringing Tent"), so a loose `getByText('Tent')` finds four elements and fails on
strict mode. For the same reason, scope a section with
`filter({ has: page.getByRole('heading', …) })` rather than `hasText`: an assignee
select puts the word "Everyone" inside every section on the page.

Sharing and live sync are tested with **two browser contexts**, not two pages: a
session is a cookie, so one context would simply log the first account out. The
reconnection test drops the socket with Playwright's `routeWebSocket` and refuses
the first few retries, which makes the outage a few seconds wide instead of a
race. Note that Chromium's offline emulation leaves an already-open WebSocket
alone and only breaks HTTP — useful for testing that a failed re-read retries,
useless for testing reconnection.

## Backups

A `postgres:17-alpine` sidecar (`backup/backup.sh`), started by `compose.yaml`
alongside everything else.

- **Same image as the database, on purpose.** `pg_dump` refuses to dump a server
  newer than itself, so a sidecar pinned to another version is a backup that stops
  working the day Postgres is upgraded.
- **Every dump is read back before it is published.** `pg_restore --list` walks the
  archive's table of contents, so a truncated file is caught the day it happens
  rather than the day it is needed. It is written under `.partial-*` and renamed,
  because a rename is atomic and a copy job must never pick up a half-written file.
- **Retention runs only after a success**, so a run of failures cannot rotate away
  the last good copies — the failure mode where backups quietly become nothing.
- **Thirty dumps, not seven.** The number is a *detection window*, not an appetite
  for history: every dump older than a mistake is a faithful copy of the mistake,
  so retention decides how long something can go unnoticed and still be
  recoverable. A week fits software somebody opens daily; wander gets used hard
  for a fortnight and then not at all until the next trip, which is exactly when a
  quietly damaged trip would go unseen. At a few hundred KB a dump, a month costs
  ~10MB, so the usual reason to keep it short does not apply.
- **The directory is a host path, not a named volume.** `docker compose down -v`
  removes named volumes, and this is the one thing that most needs to survive
  somebody typing that. It is in `.gitignore`: a dump is the whole database,
  password hashes and booking references included.
- **The restore is documented in README and has been run**, not written from
  memory: restored into a scratch database, compared against the original by
  `md5(string_agg(...))` over whole rows, and then booted — the app started
  against it and Flyway validated all 14 migrations. `ddl-auto: validate` is what
  makes that last step meaningful, so "the app starts" really does mean "the
  schema is intact".
- **Backups on the same host do not survive losing the host**, and that is stated
  in README and `.env.example` rather than left implied. The dumps are independent
  of the *database* — a bad migration leaves them intact — but not of the
  *machine*: one disk, one provider account. The offsite copy is a plain `rsync`
  documented in README, with two details that are the whole point of it. It has
  **no `--delete`**, because mirroring would replicate an emptied `backups/`
  directory onto the last surviving copy in exactly the disaster it exists for;
  and it **pulls** rather than having the server push, because a compromised
  machine cannot reach a destination it holds no credentials for.

## Third-party notices

`THIRD-PARTY.txt` in the image, in **three parts**, generated in two places
because they answer to two different things.

- **An image is a binary distribution**, so MIT, BSD, ISC and Apache-2.0 all
  require the copyright notice to accompany it. That is the argument
  `api/build.gradle.kts` already made about `BOOT-INF/lib`, and it does not stop
  at the jar.
- **Parts 1 and 2 are Gradle's** — the browser bundle and the server's jars,
  from `runtimeClasspath` and the npm tree, because the question is what gets
  distributed and a test-only dependency ships nothing.
- **Part 3 is `deploy/os-notices.sh`, run in the runtime stage**, and it cannot
  move into Gradle: the package set belongs to the image rather than the source
  tree, and it differs per architecture — 233 packages on amd64, two fewer on
  arm64 when that was last measured, which the per-arch runtime stage gets
  right for free.
- **It reads apk's installed database**, so it cannot drift from what is
  genuinely in the image the way a hand-kept list would. Records are emitted as
  one tab-separated line and sorted *then* formatted: sorting the formatted
  blocks sorts their lines independently and pairs every package with somebody
  else's licence — a file that looks right and is wrong throughout.
- **It names licences and does not reproduce their texts**, because Alpine ships
  no per-package copyright files to copy (unlike Debian's
  `/usr/share/doc/*/copyright`). What it gives instead is the SPDX id apk
  records plus the upstream URL, and for the GPL, LGPL and MPL packages the
  offer of source: Alpine's aports, since these are Alpine's own unmodified
  binaries.
- **`extractors/` is LGPL-2.0-or-later and stays that way.** The scripts there
  are derived from KDE's own, so each keeps its upstream copyright line beside
  ours. Nothing changes about the argument below: they are not linked into
  anything either — they are interpreted by the subprocess, which is a further
  arm's length rather than a shorter one.
- **None of it reaches wander's own licence.** They are separate programs
  sharing a filesystem, not code linked in — the application runs
  `kitinerary-extractor` as a subprocess and reads its output — so an image is
  an aggregate and a GPL-2.0-only utility sits beside an AGPL-3.0 application
  without either licence touching the other. The subprocess boundary was chosen
  for crash isolation; that it also keeps the licensing arm's length is a
  second reason not to link anything.
- The section was **never empty**: the base image has always carried a GPL-3
  `coreutils` and `gnupg`. Booking import took it from 73 packages to 233, which
  is what prompted writing it down rather than what created the obligation.

## Shipping the image

CI builds **one image for linux/amd64 and linux/arm64**, because the runners are
x86 and the deployment target is an Ampere box: an amd64-only image pulls
perfectly onto arm64 and then dies with `exec format error`, on the server, at
`docker compose up`. It is nearly free here only because the build stage carries
`--platform=$BUILDPLATFORM` — the jar it produces is bytecode and static files,
identical on either architecture, so only the runtime stage is emulated. Drop
that flag and buildx runs Gradle and npm under QEMU to produce a byte-identical
artifact.

**A version means a release, and nothing else.** `wander.version` is
`${WANDER_VERSION:dev}`, and the only thing that sets it is CI building a **tag**
— so an image built from an ordinary commit on `main` honestly calls itself
`dev` rather than claiming a number somebody edited into a yaml file once. Beside
it, `wander.build-ref` is the commit, baked in as a build argument on every
build; that is the half that moves, and the only thing that identifies an
instance updated by pulling `latest`. Both reach the client on `/api/config` and
are drawn as one chip in the account menu, because "which build are you on" is
the first question anybody asks and a self-hoster otherwise cannot answer it
without SSH. The version argument is passed *only* when a tag exists: Spring
reads a set-but-empty environment variable as a value, not as an absent one, so
passing it empty would produce a blank version rather than `dev`.

**A null entry in `compose.yaml`'s `environment:` block unsets what the image
set.** The pass-through style there — `WANDER_SOURCE_URL:` with no value — is
written up as "the variable reaches the container *absent* rather than empty, so
the application's own default applies". True, and it has a second half worth
knowing: absent means absent, so if the *Dockerfile* set that variable, listing
it here removes it. `WANDER_IMPORT_EXTRACTOR_SEARCH_PATH` is set by the image to
`/app/extractors`, and passing it through unloaded the extractor fixes with
nothing failing and no log line. It is deliberately not in that block, and the
comment beside it says why. `env` inside the container is the only thing that
shows this; Compose's own `config` output prints the same `null` either way.

**The image carries its own deployment bundle**: `docker run --rm <image> bundle
| tar x` writes out `compose.yaml`, the `Caddyfile`, `backup/backup.sh`,
`.env.example`, `DEPLOY.md` and `update.sh`. They are `COPY`d from the repository
at build time, so the compose file a server runs is the one committed beside the
image it runs. The alternative — a deployment repository holding its own copy —
drifts the first time somebody edits one and not the other, and drift here starts
a stack that is subtly wrong rather than failing. `deploy/entrypoint.sh` is the
whole mechanism: `bundle` writes a tar, anything else is the application. The CI
job extracts the bundle from the pushed image and checks the files are there, so
a broken `bundle` is a red pipeline rather than something found on a machine with
no source to fall back on.

## Scope discipline

Milestone 0 (accounts, trips, the contract loop, one container) is done, and so
is "days and places": derived days, places with ordering owned by the
server (`PlaceService` renumbers a day on every move or delete, and the client
re-reads instead of patching ranks), Nominatim search behind a proxy that
caches and rate-limits, a Leaflet map, drag ordering, and a note per day.
Milestone 3 is done: members, roles, ownership transfer, live WebSocket sync,
and invitation links for people who have no account yet. Milestone 4 is done: expenses with splits, balances and
settling up, packing lists, and reservations. Milestone 5 is half done — offline
*reads* are in; the write queue is deliberately not, and a decision rather than an
omission. The day card is finished: a note, what the day cost, and the forecast
when there is one. Milestone 6 is done: sorting a day by route over OSRM,
with locked stops and three profiles, and the finished order opening in Google
Maps — off by default, because there is no public routing service to lean on.
Beyond the roadmap: verified nightly backups with a rehearsed
restore, the itinerary as a printable document, and the admin surface — which
finally gives `GlobalRole.ADMIN` something to grant and removes the "no password
reset" limit README had stated since the beginning. See the roadmap in README.md.
Deliberately **out** of scope until asked: plugins, i18n, MCP. Keep v1 small.
