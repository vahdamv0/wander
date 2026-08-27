# wander

A self-hostable, collaborative travel planner. Spring Boot 4 + Angular 22, one
container, one Postgres.

> **Status: milestone 0.** The walking skeleton is complete and verified —
> accounts, sessions, trips, and the full contract loop from Java records to a
> typed Angular client. The planner itself (days, places, maps) is next.

## What works today

- Register / sign in / sign out, session-cookie auth with CSRF
- Create and list trips, scoped to the people who are members of them
- A trip's days are derived from its date range, never stored
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

**Multi-tenant from the first migration.** `trip_members` exists even though only
its `OWNER` row is written today. Access to a trip is decided by membership and
nothing else — there is no owner column to fall out of step with it.

**A non-member gets 404, not 403.** A 403 confirms the trip exists, which lets
anyone count trips by walking ids. A member with too weak a role does get 403 —
they already know it exists.

**Days are derived, never stored.** A stored day list is a second copy of the
date range, and moving a trip's dates would then mean keeping two things in step.

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
| Web | Angular 22 (standalone, signals, zoneless), TypeScript |
| Contract | springdoc-openapi → ng-openapi-gen |
| Tests | JUnit 5, Testcontainers (real Postgres, never H2) |
| Build | Gradle 9.7 (Kotlin DSL), one Docker image |

## Roadmap

1. **Milestone 0 — walking skeleton.** ✅ Accounts, trips, contract loop, one container.
2. **Days and places.** Days from the date range, place search over Nominatim, a
   Leaflet map, drag ordering within and across days, day notes.
3. **Sharing.** Invites, the member list, roles beyond `OWNER`, and WebSocket
   sync so two people editing one day do not clobber each other.
4. **Money and stuff.** Expenses with splits, packing lists, reservations.
5. **Offline.** IndexedDB reads and a replaying write queue, inside the repos.

## Licence

Not chosen yet — decide before the first public push. It matters here: a
permissive licence (MIT/Apache-2.0) lets anyone fork and close it, while AGPL-3.0
requires anyone who runs a modified copy as a service to publish their changes.
