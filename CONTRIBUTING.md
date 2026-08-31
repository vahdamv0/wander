# Contributing to wander

Thanks for looking. This file is the short version of what you need to build the
thing and get a change merged. The long version — *why* the code is shaped the
way it is — lives in [CLAUDE.md](CLAUDE.md), which is worth reading before your
first change and is genuinely the fastest way to understand this codebase.

## What you need

- **JDK 21.** The Gradle toolchain pins it.
- **A running Docker daemon.** Not optional: the tests run against a real
  Postgres via Testcontainers, never H2, because the migrations use
  Postgres-specific SQL. No daemon, no test run.
- **Node** comes from Gradle. You do not need one on your PATH — `./gradlew
  build` downloads its own into `web/.gradle/nodejs/`. If you use your own, the
  Angular CLI needs ≥ 22.22.3.

## Build and test

```bash
./gradlew build                          # frontend + jar + the whole test suite
./gradlew build -Pfrontend.skip=true     # backend only, skips all npm work
./gradlew :api:test                      # Java tests; also writes api/build/openapi.json

cd web && npm start                      # Angular dev server on :4200, proxies /api
cd web && npm test                       # vitest unit tests
cd web && npm run lint
```

For the API alone: `docker compose up -d db && ./gradlew :api:bootRun`, which
serves on `:8080`.

The browser suite runs against a **running instance**, so start one first:

```bash
cd web && npm run e2e                    # Playwright; WANDER_E2E_URL overrides localhost:8080
```

Note that it creates every account by registering, from one address — so the
instance you point it at needs `WANDER_REGISTRATION_ENABLED=true` and a raised
`WANDER_REGISTRATION_MAX_PER_ADDRESS`, or the throttle will fail the run.

## The one rule that trips people up

**The API contract is generated, in one direction only:**

```
Java record → springdoc → api/build/openapi.json → ng-openapi-gen → web/src/app/api/
```

So:

- **Never hand-write a DTO twice.** Change the Java record, run `./gradlew
  :api:test`, then `./gradlew :web:apiGen`.
- **`web/src/app/api/` is generated but committed**, so a fresh clone and the
  Docker build need no database. **Never edit it by hand.** CI regenerates it and
  fails the pipeline on any drift, so a hand-edit does not survive review.
- A response field that is always present needs `@NotNull`, or springdoc leaves
  it out of `required` and the generated TypeScript field turns optional.

## House style

- **Tokens, not raw colours,** in templates — `bg-surface`, `text-muted`,
  `border-border`. That is what makes the three-state theme work everywhere at
  once. A hex literal or a `bg-white` will be sent back.
- **Components never call HTTP.** They go through a repo in
  `web/src/app/repo/`. Offline support lives inside the repos, so a component
  that bypasses them breaks it.
- **Signals, not RxJS state.** No NgRx.
- **Flyway owns the schema.** A schema change is a new `V<n>__*.sql`; never edit
  a migration that has been applied.
- Prettier and `.editorconfig` settle formatting. `npm run lint` before you push.
- Commit messages follow `feat:` / `fix:` / `chore:`.

## What CI checks

Three jobs, and **all of them are gates** — nothing here is allowed to be a
warning:

1. `test` — `./gradlew build`, the full suite against real Postgres.
2. `api-client-drift` — regenerates the typed client and fails if the committed
   one differs.
3. `container-image` — builds and pushes the multi-arch image, on `main` and on
   tags.

## Scope

v1 is deliberately small. Plugins, i18n and MCP are **out of scope until
asked** — see the roadmap in [README.md](README.md). If you are planning
something large, open an issue first so nobody writes a feature twice.

## Licence

wander is **AGPL-3.0-or-later**. Contributions are accepted under the same
licence. One practical consequence worth knowing if you are going to run your
own copy: section 13 means an instance you modify and offer to other people over
a network owes those users its source, which is what the **Source** link in the
interface is for. If you fork, point `WANDER_SOURCE_URL` at your fork.
