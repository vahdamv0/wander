# wander

A self-hostable, collaborative travel planner. Spring Boot 4 + Angular 22, one
container, one Postgres.

> **Status: usable.** Milestones 0 to 3 are done — accounts, trips, the
> itinerary, sharing with roles, and live sync between browsers — and milestone
> 4's money half is in. What remains before v1 is packing lists, reservations and
> offline; see the roadmap below.

## What works today

- Register / sign in / sign out, session-cookie auth with CSRF, sessions stored in
  Postgres so a restart does not sign everybody out
- Create, rename and reschedule trips, scoped to the people who are members of them
- A trip's days are derived from its date range, never stored — and moving the
  dates carries the itinerary with it rather than stranding it
- Places on a day: add, rename, annotate, delete, and drag into order — within a
  day or into another one, with buttons as the keyboard equivalent
- What each day cost, on the day itself, and the weather forecast for it when one
  exists — Open-Meteo, no API key, and honestly blank beyond the forecast horizon
- A note on each day, and place search over Nominatim, proxied and cached, so a
  place keeps its coordinates
- A Leaflet map beside the itinerary: a pin per located place, numbered by day
- Share a trip: members by email, owner / editor / viewer roles, and handing the
  trip over to somebody else
- Live sync over a WebSocket, so two people on one trip see each other's edits
  without reloading
- Expenses in one currency per trip: equal or exact splits, balances, who-owes-whom
  reduced to the fewest payments, and recording those payments
- A packing list per trip: shared items and per-person ones, ticked off live, with
  a note of who packed each shared thing
- Bookings — flights, trains, hotels, tables — stored as instants with the zone
  they were booked in, so a flight keeps London time for its departure and Tokyo
  time for its arrival, and the list is ordered by when things really happen
- Place enrichment: a searched place's pin carries what OpenStreetMap, Wikidata,
  Wikipedia and Commons know about it — a description, opening hours, a website
  and photographs you can keep — each with its source and licence shown
- Offline reading: open a trip once and its days, places, bookings and packing
  list stay readable with no connection, labelled with how old the copy is
- Light / dark / follow-the-OS theming, all driven by design tokens
- The Angular app and the API ship as a single jar

## Run it

```bash
cp .env.example .env          # then set POSTGRES_PASSWORD
docker compose up -d
docker compose logs wander    # the generated admin password is printed once
```

Open <http://localhost:8080>.

## Develop

Needs **JDK 21** and **Node ≥ 22.22.3** (Angular 22's CLI refuses older). The
Gradle build downloads its own pinned Node, so only the Angular CLI itself cares
about your local version.

```bash
# 1. Postgres on :5432 with the credentials application.yml defaults to.
#    (Not `docker compose up -d db` — that one publishes no host port on
#    purpose, so the production stack keeps its database off the network.)
docker run -d --name wander-dev-db -p 5432:5432 \
  -e POSTGRES_DB=wander -e POSTGRES_USER=wander -e POSTGRES_PASSWORD=wander \
  postgres:17-alpine

# 2. API on :8080  (Flyway migrates on boot; an admin is seeded on first run)
./gradlew :api:bootRun

# 3. Angular dev server on :4200, proxying /api to :8080
cd web && npm install && npm start
```

Stop and reset the dev database with
`docker rm -f wander-dev-db` (its data is not on a volume, so this wipes it).

Useful commands:

```bash
./gradlew build                          # everything: frontend, jar, tests
cd web && npm run e2e                    # Playwright, against a running instance
./gradlew build -Pfrontend.skip=true     # backend only, no npm work
./gradlew :api:test                      # tests + exports api/build/openapi.json
cd web && npm run api:gen                # regenerate the typed client from that spec
```

## How it fits together

```
Angular component → repo (web/src/app/repo) → generated client → /api → controller → service → JPA
                                  ▲                                        │
                                  └──── ng-openapi-gen ◄── openapi.json ◄──┘
```

**The Java code is the contract.** springdoc renders the OpenAPI document,
`OpenApiSpecExportTest` writes it to `api/build/openapi.json`, and
`ng-openapi-gen` turns it into `web/src/app/api`. No DTO is written twice — edit
the Java record and the TypeScript type follows.

The generated client **is committed**, so a fresh clone and the Docker build need
no database to produce a spec first. CI regenerates it and fails the pipeline on
any drift, so it cannot quietly diverge.

Components never call HTTP directly; they go through a repo. Today those are thin
pass-throughs, and that is the point: when offline support arrives, IndexedDB
reads and a write queue land inside the repos and no component changes.

## Decisions worth knowing

**Session cookies, not JWT.** Angular is served same-origin from the same jar, so
an httpOnly cookie cannot be read by XSS and there is no refresh-token dance.
Angular's `HttpClient` handles the `XSRF-TOKEN` cookie with no code.

**Money is integers.** Amounts are stored and sent as minor units — `1234` is
12.34 — in one currency per trip, fixed when the trip is created. No floating
point touches money in either language, an uneven split spreads its remainder to
the penny rather than losing it, and the balances arrive computed from the server
so there is only ever one implementation of the arithmetic.

**Sessions live in Postgres, not in the heap.** Spring Session JDBC, with its two
tables owned by Flyway like everything else. Because the session *is* the
credential here, an in-memory store would mean every restart signs out every
user — on a self-hosted instance that is a deploy logging out the household, and
live sync coming back from it asking people to log in again instead of
reconnecting. It also means a second instance behind a load balancer needs no
sticky sessions.

**Multi-tenant from the first migration.** `trip_members` was there before it
carried anything but an `OWNER` row. Access to a trip is decided by membership and
nothing else — there is no owner column to fall out of step with it.

**A non-member gets 404, not 403.** A 403 confirms the trip exists, which lets
anyone count trips by walking ids. A member with too weak a role does get 403 —
they already know it exists.

**One token layer, three theme states.** Every colour comes from a CSS variable
in `web/src/styles.css`; no component holds a raw colour. The theme follows the OS
by default and an explicit light/dark choice overrides it in both directions —
which is why each dark palette is declared twice, once per condition.

**Days are derived, never stored.** A stored day list is a second copy of the
date range, and moving a trip's dates would then mean keeping two things in step.
A place therefore carries a plain `day_date` rather than a foreign key, and
`GET /api/trips/{id}/itinerary` rebuilds the range on every read — empty days
included, so the client never reconstructs it.

**Place search is proxied, never called from the browser.** Nominatim's usage
policy wants one identifiable caller honouring one request a second, which is
impossible to arrange across N browsers; a shared cache only works server-side
anyway, and a self-hoster can point `WANDER_GEOCODING_URL` at their own instance
without any client learning a new address. `GeocodingService` holds the toggle,
the LRU cache, and the rate gate; `GeocoderClient` is the interface the tests
replace, so the suite never touches the network or spends a public service's
budget. Search off (`WANDER_GEOCODING_ENABLED=false`) is a supported
configuration — the itinerary still works, places are typed by hand and simply
have no coordinates.

**The client asks the instance what it may do.** `GET /api/config` carries the
tile URL, its attribution, and whether search works. None of it is compiled into
the client, because all of it is the operator's decision — an instance pointed at
its own tile server, or one with no outbound network at all, is a supported
configuration rather than a broken one.

**Tiles are the one request wander does not proxy**, since the browser has to
fetch a few hundred images and routing those through the jar would make it a
tile cache. That is the reason the tile URL is configurable: an operator who does
not want their users' browsers talking to openstreetmap.org points it elsewhere.
The attribution travels with the URL, because the terms attach to the service, not
to the code.

**Results come back in the browser's language.** The caller's `Accept-Language`
is forwarded to the geocoder and keyed into the cache with the query, because a
geocoder given no preference answers in the place's own language and one shared
cache entry would hand the first caller's language to everyone else.

**A picked location is saved, not re-derived.** The client sends the coordinates
of the candidate the user chose. Re-geocoding the name server-side would be
tidier in principle and wrong in practice: searching again can rank a different
result first, so the place would quietly move.

**Dragging did not replace the buttons.** A drag has no keyboard or
screen-reader equivalent, so the arrow controls stayed as the accessible path;
they fade in on hover or focus rather than sitting on every row, and on a device
with no hover they simply stay visible. Both paths make the same one call.

**One write is optimistic: the move.** Every other write re-reads and lets the
server's answer win, but a drag has already moved the row under the user's
finger — waiting for the round trip would snap it back and then move it again. So
`PlaceRepo.move` reorders its local copy first (renumbering exactly as the server
does), sends the call, and restores the previous order if it fails.

**The server owns ordering.** Ranks are dense and zero-based, and any move or
delete renumbers the affected day from scratch inside one transaction. Moving a
place is one operation — "put it at rank N of day D" — which is both what the
up/down buttons send today and what drag-and-drop will send later. The client
re-reads after a write rather than guessing at the new ranks.

**Gates stay on.** `EndpointAuthRatchetTest` fires an anonymous request at every
endpoint this project declares and fails if one answers; opening an endpoint
requires an explicit `@PublicEndpoint` that shows up in review. Hibernate runs
with `ddl-auto: validate`, so a drifted entity fails at boot instead of altering
tables under a running instance. Don't lower a gate to land a change.

## Stack

| | |
|---|---|
| API | Java 21, Spring Boot 4.1, Spring Security 7, JPA/Hibernate, Flyway |
| DB | Postgres 17 |
| Web | Angular 22 (standalone, signals, zoneless), Tailwind 4, TypeScript |
| Contract | springdoc-openapi → ng-openapi-gen |
| Tests | JUnit 5, Testcontainers (real Postgres, never H2), Playwright |
| Build | Gradle 9.7 (Kotlin DSL), one Docker image |

## Roadmap

1. **Milestone 0 — walking skeleton.** ✅ Accounts, trips, contract loop, one container.
2. **Days and places.** ✅ Days from the date range, places with ordering within
   and across days, place search over Nominatim, a Leaflet map, drag ordering,
   and a note on each day.
3. **Sharing.** ✅ The member list, roles beyond `OWNER`, transferring a trip,
   and live sync — add somebody by email, make them an editor or a viewer, hand
   the trip over, and watch each other's edits appear without reloading. Still to
   come: invite links for people who have no account yet.
4. **Money and stuff.** Expenses are done ✅ — one currency per trip, amounts in
   integer minor units, equal or exact splits, a "who owes whom" summary reduced
   to the fewest payments, and recording those payments so balances actually
   clear, a packing list grouped by who is bringing what, and bookings kept on a
   real clock — each time in the zone it happens in.
   The day card is finished alongside it: the day's own note, **what the day
   cost** (grouped from the expenses, payments excluded), and **the forecast**
   from Open-Meteo when there is one — no API key, CC BY 4.0, and nothing at all
   for a day past the ~16-day horizon rather than a placeholder.
5. **Offline.** Reads are done ✅ — a service worker for the app shell and
   IndexedDB inside the repos, so a trip you have opened is readable with no
   signal, honestly labelled as a saved copy. Writes are refused rather than
   queued: a replay queue forces conflict resolution that live sync deliberately
   never needed, and that stays a decision rather than a gap.

## Licence

Not chosen yet — decide before the first public push. It matters here: a
permissive licence (MIT/Apache-2.0) lets anyone fork and close it, while AGPL-3.0
requires anyone who runs a modified copy as a service to publish their changes.
