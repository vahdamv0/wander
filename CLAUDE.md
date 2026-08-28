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

Not a port of TREK (`/media/veracrypt1/TREK`, a TypeScript monorepo). TREK is a
reference for patterns only; do not copy its code — it is AGPL, and a file-by-file
port would make this a derivative work.

## Commands

```bash
./gradlew build                          # frontend + jar + tests
./gradlew build -Pfrontend.skip=true     # backend only, skips all npm work
./gradlew :api:test                      # tests; also writes api/build/openapi.json
./gradlew :api:bootRun                   # API on :8080 (needs `docker compose up -d db`)
cd web && npm start                      # Angular dev server on :4200, proxies /api
cd web && npm run api:gen                # regenerate the typed client from the spec
```

Tests need a Docker daemon: they run against real Postgres via Testcontainers,
never H2 — the migrations use Postgres-specific SQL.

## The contract loop

Java record → springdoc → `api/build/openapi.json` (written by
`OpenApiSpecExportTest`) → `ng-openapi-gen` → `web/src/app/api`.

- **Never hand-write a DTO twice.** Change the Java record, rerun
  `:api:test`, then `npm run api:gen`.
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
- **A day is addressed by its date.** Days are derived from the trip's range and
  have no rows, so anything hung off one carries a plain `day_date` — `places`
  does, and so does `day_notes`, whose real key is the unique `(trip_id,
  day_date)`. `Trip.requireCovers` is the one range check both services call.
  A day note is an upsert (`PUT .../days/{date}/note`) and a **blank note
  deletes the row**: "no note" is the absence of a row, so there is one
  representation of empty and no second endpoint to clear one. Notes ride along
  on `TripItinerary`, so the page is still one request.
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

`GET /api/config` (authenticated) carries the tile URL, its attribution, and
whether place search is enabled. Nothing operator-configurable should be
compiled into the Angular app: self-hosting means the tile server and the
geocoder are somebody else's decision, and "search off, map off" is a supported
configuration. `InstanceConfigStore` loads it once when the signed-in shell
mounts, and features that depend on it treat "not answered yet" as available so
nothing flickers into existence.

## Talking to Nominatim

Place search is a proxy (`/api/geo/search`), authenticated like everything else —
an open one would hand this instance's rate budget to anyone. Three rules the
usage policy imposes and this code keeps: an identifying `User-Agent` (a default
Java one gets 403'd), at most one outbound request a second **across the
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

## Live sync

Two people on one trip see each other's changes without reloading.
`/api/ws/trips/{tripId}` is a plain WebSocket — no STOMP, no SockJS: the client
sends nothing but keepalive, and the payload is one small event.

- **Invalidation, not state.** A frame says *what* changed (`ITINERARY`,
  `MEMBERS`, `TRIP_DELETED`), never what it changed to, and the client answers by
  re-reading. The server owns place ranks and renumbers a whole day on every
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

## Browser tests

`cd web && npm run e2e` (Playwright) against a running instance — start the app
first; `WANDER_E2E_URL` overrides the default `http://localhost:8080`. These exist
because two real bugs were invisible to the Java suite: responses documented as a
wildcard media type made the generated client request Blobs instead of JSON, and
the CSRF cookie needs one GET before the first POST. Both looked perfect to curl.
The suite asserts on console errors too — a template that throws every change
detection still renders, so a status code alone proves nothing.

Sharing and live sync are tested with **two browser contexts**, not two pages: a
session is a cookie, so one context would simply log the first account out. The
reconnection test drops the socket with Playwright's `routeWebSocket` and refuses
the first few retries, which makes the outage a few seconds wide instead of a
race. Note that Chromium's offline emulation leaves an already-open WebSocket
alone and only breaks HTTP — useful for testing that a failed re-read retries,
useless for testing reconnection.

## Scope discipline

Milestone 0 (accounts, trips, the contract loop, one container) is done, and so
is "days and places": derived days, places with ordering owned by the
server (`PlaceService` renumbers a day on every move or delete, and the client
re-reads instead of patching ranks), Nominatim search behind a proxy that
caches and rate-limits, a Leaflet map, drag ordering, and a note per day.
Milestone 3 is done bar one piece: members, roles, ownership transfer and live
WebSocket sync are in; what remains of sharing is invite links for people who
have no account yet. See the roadmap in README.md. Deliberately **out** of scope until asked:
plugins, i18n, MCP, offline. Keep v1 small.
