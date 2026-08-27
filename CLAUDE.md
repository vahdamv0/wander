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
- **Membership is the only grant.** No owner column on `trips` — `trip_members`
  is the single source of truth for who may see a trip.
- **Flyway owns the schema**, Hibernate runs `ddl-auto: validate`. Schema changes
  are a new `V<n>__*.sql`; never edit an applied migration.
- **Outbound HTTP goes through an interface.** `GeocoderClient` is the seam the
  tests replace (`@MockitoBean`), which is what keeps the suite off the network
  and off a free public service's rate budget. `GeocodingService` owns the
  feature toggle, the LRU cache, and the shared one-request-a-second gate — a
  new upstream call belongs behind the same shape, not in a controller.
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
- **`@for` needs a collection from the component, not an inline array literal.**
  `@for (x of [1, 2]; ...)` does not survive the block-syntax parser and fails at
  runtime with `newCollection[Symbol.iterator] is not a function`.
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

The client sends a picked suggestion's coordinates when creating a place rather
than having the server re-geocode the name: the user chose one candidate of
several, and a second search can rank a different one first.

## Browser tests

`cd web && npm run e2e` (Playwright) against a running instance — start the app
first; `WANDER_E2E_URL` overrides the default `http://localhost:8080`. These exist
because two real bugs were invisible to the Java suite: responses documented as a
wildcard media type made the generated client request Blobs instead of JSON, and
the CSRF cookie needs one GET before the first POST. Both looked perfect to curl.
The suite asserts on console errors too — a template that throws every change
detection still renders, so a status code alone proves nothing.

## Scope discipline

Milestone 0 (accounts, trips, the contract loop, one container) is done, and so
is most of "days and places": derived days, places with ordering owned by the
server (`PlaceService` renumbers a day on every move or delete, and the client
re-reads instead of patching ranks), and Nominatim search behind a proxy that
caches and rate-limits, a Leaflet map, and drag ordering. Still open in that
milestone: day notes. See the roadmap in README.md. Deliberately **out** of scope until asked:
plugins, i18n, MCP, offline. Keep v1 small.
