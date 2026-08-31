# Handoff — 30 Aug 2026

Where wander stands, and what to pick up next. This file is scratch: delete it
(or fold anything lasting into CLAUDE.md / README.md) when it stops being
useful. It is deliberately not committed.

Everything that was worth keeping from the previous version of this file has been
moved into CLAUDE.md and README.md, which are now accurate. What is left here is
only the stuff that belongs to *this machine* and *this moment*.

## State: green, and nothing half-finished

- `./gradlew build` — **174 Java tests**, real Postgres via Testcontainers.
- `cd web && npm test` — **18 vitest tests** (money parsing, zones, offline failure kinds).
- `cd web && npm run e2e` — **23 Playwright tests**, against a running instance.
- Schema is at **V14**. `wander-v1` has been fast-forwarded into **`main`**, which
  is now the whole application; both point at the same commit and both are pushed.
- **CI is green on `main`** — all three jobs, `test`, `api-client-drift` and
  `container-image`, the last two having run successfully for the first time.
  Three things had to be fixed to get there, and each is a trap worth recognising
  again: a job **named `image`** is read as the global image keyword and rejects
  the whole pipeline before any job starts; the `eclipse-temurin` image has
  **neither node nor git**, because GitLab clones with the runner's helper
  container; and the drift check needs **`--rerun`**, or Gradle calls `apiGen`
  up-to-date, skips it, and compares the committed client against itself. That
  last one is the dangerous kind — it fails open, and it is now the reason the
  check branches on `diff`'s explicit exit code rather than `if ! diff`.

## In the working tree right now: the vector basemap

Uncommitted, and finished — build, browser suite and unit tests all green, and
the rendered map checked in both themes.

The map is drawn from MapLibre vector tiles (OpenFreeMap by default) inside
Leaflet, which still owns the map, the markers, the tooltips and the framing.
The point is language: a raster tile has the local name painted into the picture,
so a Kyoto trip was labelled in Japanese whatever the browser asked; vector tiles
carry the names as data, so each label is now drawn twice — the reader's language
over the local name. Dark mode is a dark *style* rather than a filter over a
light one. A raster `tileUrl` still works and is what a blank `styleUrl` selects.

The bug that ate the session, and the reason there is now a test for it: MapLibre
loads its tile-parsing **worker by URL at runtime**, so no bundler emits it, and
the request fell through to the SPA fallback and got `index.html` back. The
worker died on its first line with *nothing* logged anywhere — no console error,
no MapLibre error event, no failed request — and the only symptom was a blank
basemap while the style, the sprites and the markers all loaded perfectly. Fixed
by copying it (and the shared chunk it imports beside itself) in `angular.json`
and pointing `setWorkerUrl` at `document.baseURI`. The browser suite could never
have caught it: it stubs the basemap with an empty style to stay off a
donation-funded tile service, and an empty style needs no worker. The new test
just asks the server for the two files and checks the content type.

Everything about it is written down in CLAUDE.md, README, `.env.example` and the
file itself, so this section can go when it is committed.

## Settled since the last handoff

- **The licence is decided and applied: AGPL-3.0-or-later.** `LICENSE` is the
  canonical FSF text, verified byte-identical to gnu.org's copy. Rationale is in
- **The map popup is gone.** A pin's label is a hover/focus tooltip. The bug it
  fixed: a click opened the detail panel *and* a popup, and Leaflet pans a popup
  to fit the map container while knowing nothing about the panel drawn over it —
  so the label was rendered underneath the panel every time. The suite had been
  green throughout, because Playwright visibility is CSS, not occlusion.
- **Directions offer Google Maps and OpenStreetMap**, in that order, as plain
  links out.
- **Removing a place asks first**, on both the row's `×` and the panel's Remove.
  It is the only confirmation in the app so far.

## Getting ready to release (30 Aug 2026)

The plan is a public instance on a **free cloud VM** — Oracle Cloud Always Free
(Ampere A1) is the shape that fits, because the deployment unit here is
`docker compose up -d` with a host directory for the dumps and a WebSocket that
has to stay open, and the free *app platforms* all break at least one of those:
they sleep, or their free Postgres pauses after a week idle, which is exactly the
usage pattern of a trip planner nobody opens between trips. Notes for the day it
is actually provisioned: pick a region near you, **upgrade the account to Pay As
You Go** (it stays free inside the Always Free shapes and takes the instance out
of the trial idle-reclamation policy), expect "out of host capacity" on A1 and
retry, build the image on the box because it is arm64, and open 80/443 in
Oracle's security list *and* in the instance's own iptables, which drops
everything but SSH regardless of what the cloud says.

Three of the four release blockers are now done — see the new "Before you put it
on the internet" section in README:

- **Sign-ups are off by default**, and a live invitation token is accepted by the
  registration endpoint as authorisation in its own right, so an invitation link
  still works end to end on a closed instance. Note the suite turns registration
  back on for the `test` profile, and **`npm run e2e` needs
  `WANDER_REGISTRATION_ENABLED=true` on whatever instance it drives**, because it
  creates its accounts by registering.
- **No password reset** is now stated plainly in README rather than left to be
  discovered. Nothing here sends mail, so the only recovery is editing
  `users.password_hash`. That is fine for a household and is the honest limit on
  how wide this instance can go.
- **`/api/auth/login` is throttled** — `LoginThrottle`, per account and per client
  address, cleared by any success, a window rather than a lockout.

**The fourth is not done, and it is the one this machine cannot do for itself:**

- **An offsite copy of the backups.** It has been optional while everything ran on
  a machine sitting in the room. On a free cloud VM it stops being optional, and
  for the exact reason the free tier is free: the instance can be reclaimed, the
  provider account can be lost, and one disk is one disk. The `rsync` is already
  written down in README, along with the two details that are the whole point of
  it — **no `--delete`** (mirroring would replicate an emptied `backups/` onto the
  last surviving copy in precisely the disaster it exists for) and it **pulls**
  rather than the server pushing (a compromised machine cannot reach a
  destination it holds no credentials for). What is left is to actually run it
  from somewhere else, on a schedule, and to restore one dump from that copy — a
  backup nobody has restored is a hypothesis.

## What is actually left

Invite links have since landed (a link needs no mail; the owner delivers it), so
one deliberate omission remains, a decision rather than a gap and written down in
README:

- **The offline write queue** — it forces the conflict resolution that live sync
  was designed never to need.

And one real item, blocked on something outside the code:

- **The AGPL §13 source link.** A modified instance served to other people owes
  those users its Corresponding Source, which in practice is a "Source" link in
  the interface. It is not built, because it would have to point at
  `gitlab.com/vm83043/wander`, which is private — a link to a repo nobody can read
  satisfies nothing. If the repo goes public, this is the piece to add, and it is
  a small footer change. Recorded in README and CLAUDE.md so it cannot be lost.

## This machine

- **The admin account on the local instance is locked out.** `.env` has blank
  `WANDER_ADMIN_EMAIL`/`WANDER_ADMIN_PASSWORD`, so first boot generated a password
  and printed it to a container replaced several times since. To get an admin
  back: put credentials in `.env`, then `docker compose down -v && docker compose
  up -d` — which wipes the dev database. Registering a normal user works fine
  meanwhile.
- **The dev database holds 913 users**, all from test runs (`e2e-*`, `probe*`,
  `drag*`, `shot-*`, `popup-*`). Harmless. `down -v` is the broom, and it is the
  same command that fixes the admin above.
- **Local Node is 22.12.0**, below Angular 22's floor of 22.22.3, so `npm start`
  and `npm run build` refuse. `./gradlew :web:ngBuild` works because Gradle
  downloads its own Node, and `npm run e2e` / `npm run api:gen` work too. For
  vitest directly:
  `PATH=web/.gradle/nodejs/node-*/bin:$PATH npx ng test --watch=false`.
  Upgrading Node locally would make the dev server usable.
- **`WANDER_GEOCODING_CONTACT` is empty, and that is correct.** Both policies were
  checked: Nominatim requires a non-stock identifying User-Agent and says nothing
  about contact details; Wikimedia asks for identification plus a contact and
  explicitly allows omitting parts that do not apply. The blank fallback already
  sends `wander/<version> (self-hosted travel planner)`, which is compliant. If it
  is ever wanted, a **URL** beats an address — but it must be publicly readable,
  so a private repo will not do. Setting it needs no rebuild.
- **Pushing needs a token, not a password.** GitLab.com rejects account passwords
  over HTTPS and no local SSH key is registered with it. Personal access token
  with `write_repository`, username `vm83043`. No credential helper is configured,
  so it is retyped every push.

## Two habits worth keeping

- **Look at the rendered page, not just the green tests.** Every defect worth
  fixing in this project has been invisible to a passing suite and obvious in a
  screenshot — the card overlap, the map framing to one pin, the empty day that
  could not be dragged into, a popup drawn underneath a panel, and now a basemap
  that drew nothing at all while every request in the network tab said 200.
- **A test that provokes a failure has to say which failure it expects.** The
  live-sync test failed about one full run in four because it cuts the network on
  purpose and then asserted on console errors — including the one its own outage
  caused. Filtered by name now, so any *other* console error still fails it.

## How to get going

```bash
docker compose up -d                     # app on :8080
./gradlew build                          # backend + frontend + all tests
cd web && npm run e2e                    # browser suite, needs the app running
```

The contract loop, unchanged: edit the Java record → `./gradlew :api:test`
(writes `api/build/openapi.json`) → `cd web && npm run api:gen`.
