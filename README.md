# wander

A self-hostable, collaborative travel planner. Spring Boot 4 + Angular 22, one
container, one Postgres.

> **Status: usable.** The roadmap below is done — accounts, trips, the itinerary,
> sharing with roles and invitation links, live sync, expenses, packing, bookings,
> offline reading, and verified backups. One thing is left out on purpose rather
> than unfinished: an offline *write* queue, which would force the conflict
> resolution live sync was deliberately designed not to need.

## What works today

- Register / sign in / sign out, session-cookie auth with CSRF, sessions stored in
  Postgres so a restart does not sign everybody out
- Create, rename and reschedule trips, scoped to the people who are members of them
- A trip's days are derived from its date range, never stored — and moving the
  dates carries the itinerary with it rather than stranding it
- Places on a day: add, rename, annotate, delete, and drag into order — within a
  day or into another one, with buttons as the keyboard equivalent. Deleting asks
  first, because it takes the notes on the place with it and there is no undo
- What each day cost, on the day itself, and the weather forecast for it when one
  exists — Open-Meteo, no API key, and honestly blank beyond the forecast horizon
- A note on each day, and place search over Nominatim, proxied and cached, so a
  place keeps its coordinates
- A map beside the itinerary: a pin per located place, numbered by day, labelled
  on hover and opening the place's panel when clicked — drawn from vector tiles,
  so a place abroad is labelled in your language *and* as it appears on the signs
- Share a trip: members by email, owner / editor / viewer roles, and handing the
  trip over to somebody else
- Invitation links for people with no account here: single-use, expiring,
  revocable, and delivered by whatever you already use to talk to them
- Live sync over a WebSocket, so two people on one trip see each other's edits
  without reloading
- Expenses in one currency per trip: equal or exact splits, balances, who-owes-whom
  reduced to the fewest payments, and recording those payments
- A packing list per trip: shared items and per-person ones, ticked off live, with
  a note of who packed each shared thing
- Bookings — flights, trains, hotels, tables — stored as instants with the zone
  they were booked in, so a flight keeps London time for its departure and Tokyo
  time for its arrival, and the list is ordered by when things really happen.
  Each one can carry a phone number, shown as typed and dialable in a tap
- Place enrichment: opening a place shows what OpenStreetMap, Wikidata, Wikipedia
  and Commons know about it — a description, opening hours, a website and
  photographs you can keep — each with its source and licence shown, plus
  directions in Google Maps or OpenStreetMap
- Offline reading: open a trip once and its days, places, bookings and packing
  list stay readable with no connection, labelled with how old the copy is
- Print or save the itinerary as a PDF — a day-by-day document with bookings,
  confirmation references and phone numbers folded onto the days they happen on
- Nightly database backups that are verified before they are kept, with a restore
  procedure that has actually been run
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
./gradlew :web:apiGen                    # the same, using Gradle's pinned Node
```

## Backups, and restoring one

`docker compose up -d` starts a backup sidecar alongside the database. It writes
a compressed `pg_dump` into `./backups` once a day, keeps the newest thirty, and
**reads each dump back with `pg_restore --list` before publishing it** — a dump
nobody has ever read is a file, not a backup. It writes under a temporary name
and renames, so a copy job never picks up a half-written file, and retention only
runs after a successful dump, so a run of failures cannot rotate away the good
copies that came before.

Restoring, which is the half worth rehearsing before you need it:

```bash
# Into a scratch database first — always. Restoring over a live one is how a
# bad backup becomes a lost database.
docker compose exec db psql -U wander -d postgres -c "CREATE DATABASE restore_check OWNER wander;"
docker compose exec backup pg_restore --no-owner --dbname=restore_check /backups/wander-<stamp>.dump

# Then check it is really a wander database, not just rows in tables:
docker compose run --rm --no-deps \
  -e WANDER_DB_URL=jdbc:postgresql://db:5432/restore_check wander
```

If that starts and Flyway reports "Successfully validated N migrations", the dump
is sound: Hibernate runs `ddl-auto: validate`, so a schema that does not match the
code fails at boot rather than quietly later. To promote it, stop the app, rename
the databases, and start again.

**These dumps do not protect you from losing the machine.** They sit on the same
host as the database they came from, so anything that takes the host — a failed
disk, a provider reclaiming the instance, a compromise — takes the database and
every backup of it in one go. Thirty dumps on one box is one copy in disguise.

The other half is one line, run **from the other machine**:

```bash
rsync -az user@your-host:/path/to/wander/backups/ ~/wander-backups/
```

Two deliberate details. **No `--delete`**: that would make your copy mirror the
server, so a `backups/` emptied by a disk fault or an attacker would replicate as
an empty local directory and destroy the safeguard exactly when it was needed.
Copies accumulate here instead, and pruning is a decision you make while looking
at them. And it **pulls rather than the server pushing**: a machine that has been
taken over cannot reach into a destination it holds no credentials for, whereas a
server that pushes can be made to delete what it previously sent.

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

Components never call HTTP directly; they go through a repo. That seam is what
let offline reading land without touching a single component — `OfflineCache`
sits inside the repos, and a page only has to ask how old the copy it is showing
is. A write queue could land the same way; it deliberately has not.

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
fetch a tile per screenful and routing those through the jar would make it a tile
cache. That is the reason the map source is configurable: an operator who does
not want their users' browsers talking to a third party points it elsewhere. The
attribution travels with the URL, because the terms attach to the service, not to
the code.

**The basemap is vector, and that is what makes it readable abroad.** A raster
tile is a picture with the local name already painted into it — 東京都, never
Tokyo, whatever the browser asks for. Vector tiles carry `name`, `name:latin` and
`name:xx` as data, so the client chooses, and wander draws both lines: the
reader's language over the local name, because on a trip the useful map is the
one you can read *and* match against the sign in front of you. It also means the
map follows the theme with a dark style rather than a filter over a light one.
The default is [OpenFreeMap](https://openfreemap.org), which needs no API key and
no account; `WANDER_MAP_STYLE_URL` points somewhere else, and setting it blank
falls back to raster tiles from `WANDER_MAP_TILE_URL`, which an instance with its
own tile server loses nothing by — except the language.

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

**The one irreversible action asks first.** Deleting a place takes the notes
written on it, has no undo, and live sync puts it on everybody else's screen
within the second. It can be reached two ways — the row's `×` and the detail
panel — and both now confirm inline, naming what goes with it. The `×` in
particular is a small icon directly after "move to next day", permanently visible
on a device with no hover, which is a mis-tap away from destroying something.
It is the only confirmation in wander so far. Most of what else can be deleted
can simply be entered again; leaving a trip is the one that cannot, since getting
back in needs the owner, and it is the obvious next candidate.

**The map is a view, not a surface for content.** Everything known about a place
lives in a panel; the pin carries a label and nothing more. That is a lesson
rather than a preference — the enrichment was built into a Leaflet popup first,
and a popup computes its size and its auto-pan once, on open, so anything that
arrives afterwards does not fit. The label is a tooltip for the same family of
reason: Leaflet positions a popup to fit the *map container* and cannot see the
panel drawn over it, so a popup opened by the click that opens the panel was
drawn underneath it every time.

**Directions offer both, and choose neither.** The coordinates are OSM's and the
tiles are OSM's by default, but getting somewhere is the moment a person wants
the routing app they actually use. So the panel offers Google Maps and
OpenStreetMap as plain links out, in that order, and sends nothing to either
beyond the coordinates in the URL the user chose to open.

**Two writes are optimistic, and for the same reason.** Every other write
re-reads and lets the server's answer win. But a drag has already moved the row
under the user's finger and a tick has already moved the checkbox, so waiting for
the round trip would snap either back and then repeat it. `PlaceRepo.move`
reorders its local copy first (renumbering exactly as the server does) and
restores the previous order if it fails; `PackingRepo.setPacked` carries the
ticker's name along with the boolean, or a shared item says "packed" without
saying by whom.

**The server owns ordering.** Ranks are dense and zero-based, and any move or
delete renumbers the affected day from scratch inside one transaction. Moving a
place is one operation — "put it at rank N of day D" — which is what the up/down
buttons and a drag both send. The client re-reads after a write rather than
guessing at the new ranks.

**A backup nobody has restored is not a backup.** The sidecar reads every dump
back with `pg_restore --list` before publishing it, and the documented restore was
carried out rather than written down from memory: dumped, restored into a scratch
database, compared row-for-row by checksum against the original, and then booted —
the application started against the restored copy and Flyway validated all 14
migrations. Retention only runs after a dump succeeds, so a bad week cannot rotate
the good copies away. This is the one part of the system whose failure is
unrecoverable, and it is the one part where "it looked fine" is worth the least.

**An invitation link is a credential, and is treated like one.** Only a SHA-256
digest is stored, so the token is readable exactly once and a leaked backup is
inert; accepting takes a row lock, so a forwarded link cannot admit two people at
once; an unknown token is a 404 whatever is wrong with it, so guessing tells you
nothing. It needs no mail server, which is why it was never really blocked — the
owner sends the link themselves.

**Printing needs no PDF library.** `window.print()` is already a PDF exporter in
every browser: it honours the reader's paper size, works offline from the cache,
and needs no endpoint — the printable page is assembled from the two repos the app
already has. What it did need was a stylesheet, and the two things that would ruin
a printout (the app shell coming along, the toolbar printing itself) are invisible
on screen, so the browser test asserts them under `emulateMedia({ media: 'print' })`
rather than trusting them.

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
   live sync, and invitation links — add somebody by email, or send a link to
   somebody with no account at all, make them an editor or a viewer, hand the trip
   over, and watch each other's edits appear without reloading.
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

**GNU Affero General Public License v3.0 or later.** The full text is in
[LICENSE](LICENSE); `SPDX-License-Identifier: AGPL-3.0-or-later`.

Copyright © 2026 Vivek Madhav and wander contributors.

The AGPL was chosen over a permissive licence for the reason the AGPL exists:
wander is software people *run as a service* for other people, and under
MIT/Apache-2.0 a host could take it, improve it, and offer it back to its users
with the improvements closed. Section 13 is what closes that gap — modify wander
and run it for others over a network, and those users are entitled to the source
of what they are actually using. Self-hosting is the whole point of this project,
so the licence that protects the people doing the hosting is the right one.

It also keeps a clear line around **TREK**, the AGPL-licensed project read as a
reference for patterns while building this. No code was copied and wander is not
a port of it, but sharing its licence removes any question of the distinction
mattering.

Two consequences worth being clear about, since they are the parts people get
wrong:

- **Using wander is unrestricted.** Run it, host it for your household, plan
  trips on it. The obligations attach to *distributing* a modified version or
  *offering a modified version to others over a network* — not to use.
- **Section 13 wants a source offer in the running app.** If you modify wander
  and let other people use your instance, they must be able to get your
  Corresponding Source — in practice a "Source" link in the interface. Stock
  wander does not ship one yet, because the repository it would point at is not
  public.
