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
a "Source" link in the interface. wander does not ship one, because the repository
is private; if it is ever made public, that link is the piece to add.

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
  added **by email and must already have an account** — nothing here sends mail.
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
- **A password can be changed, never reset.** `POST /api/auth/password` takes the
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
  failure. There is still no reset, and there will not be one while nothing here
  sends mail.
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
  new upstream call belongs behind the same shape, not in a controller.
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
  a caller that does not choose.
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
here sends mail. A link needs no mail — the owner delivers it themselves. That
was always the way round the original blocker; delivery was never this
application's problem.

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
  by default, and nothing here sends mail — so without it, switching sign-ups off
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

## Shipping the image

CI builds **one image for linux/amd64 and linux/arm64**, because the runners are
x86 and the deployment target is an Ampere box: an amd64-only image pulls
perfectly onto arm64 and then dies with `exec format error`, on the server, at
`docker compose up`. It is nearly free here only because the build stage carries
`--platform=$BUILDPLATFORM` — the jar it produces is bytecode and static files,
identical on either architecture, so only the runtime stage is emulated. Drop
that flag and buildx runs Gradle and npm under QEMU to produce a byte-identical
artifact.

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
when there is one. Beyond the roadmap: verified nightly backups with a rehearsed
restore, and the itinerary as a printable document. See the roadmap in README.md.
Deliberately **out** of scope until asked: plugins, i18n, MCP. Keep v1 small.
