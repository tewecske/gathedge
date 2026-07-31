# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

**Build / compile**
```
sbt compile                 # or: sbt root/compile
```

**Dev stack** (backend + Scala.js watch + Vite, three panes)
```
npm run dev                 # concurrently: backend reStart, frontend fastLinkJS, vite dev
npm run dev:tmux            # same, in a tmux window (./dev-tmux.sh)
```
Vite serves the frontend at `:5173` and proxies `/api/*` to the backend at `:8080` (stripping the `/api` prefix). Postgres must be up first: `docker compose up -d` (copy `.env.example` to `.env` first).

**Tests**
```
sbt test                                                        # everything
sbt backend/test                                                 # backend only
sbt "backend/testOnly webapp1.backend.service.AuthServiceSpec"   # single spec
sbt sharedJVM/test                                                # shared validation logic
sbt frontend/test                                                 # Laminar components, jsdom
npm --prefix web run typecheck                                    # frontend TS config only (no app TS)
```
Backend/shared specs run against a fresh, migrated SQLite DB per layer instantiation (see `TestDataSource.sqlite`) — no external services needed. The one exception:
```
docker compose up -d
RUN_POSTGRES_TESTS=1 sbt backend/test    # PostgresIntegrationSpec, gated behind this env var
```
`PostgresIntegrationSpec` is the only place the Postgres dialect actually executes (testcontainers); everything else is Postgres-dialect-checked at compile time by Quill but runtime-tested on SQLite.

**E2E** (needs the full stack running — Postgres, backend, vite)
```
npm --prefix e2e install
npm --prefix e2e test
```

**Format**
```
sbt scalafmtAll
```

## Architecture

Three-module sbt build: `modules/shared` (cross JVM/JS), `modules/backend` (ZIO HTTP), `modules/frontend` (Scala.js + Laminar + Waypoint), plus `web/` (Vite host for the frontend) and `e2e/` (Playwright). Full domain writeup is `summary.md`.

### Build-wide gotchas

- **`-noindent` is set in `commonSettings`** — significant-indentation Scala 3 syntax (`object Foo:`, colon+indent bodies, braceless `for`/`match`) does not compile. Every block needs explicit `{ }`, including `for { ... } yield`. This is easy to get wrong if writing "modern" Scala 3 style from habit.
- **`-Werror`, not `-Xfatal-warnings`** — the latter is deprecated in this Scala version and its own deprecation warning becomes fatal under itself, breaking every compile. Don't reintroduce it.
- `evictionErrorLevel := Level.Warn` is set deliberately: `quill-jdbc-zio` pins an older `zio-json` than the rest of the build uses directly; the eviction is safe, just don't remove the override without checking.

### Dual-dialect database strategy

Postgres is the only real target (see `docker-compose.yml`); SQLite exists solely so tests don't need Docker. Every repository is a plain interface in `backend/db/*Repository.scala` with two Quill implementations — `Postgres*Repository` and `Sqlite*Repository` — swapped in purely via `ZLayer`. Quill's `quote`/`run` macros bind to a concrete context type, so query code is duplicated between the two implementations rather than shared; keep both in sync when changing a query. Flyway migrations are duplicated per dialect under `backend/src/main/resources/db/migration/{postgresql,sqlite}/`, kept schema-identical (all timestamps are epoch-millis `BIGINT`/`INTEGER`, not native timestamp types, specifically to keep the two dialects portable).

`Main.scala` always wires the Postgres implementations. Tests wire the SQLite ones via `TestDataSource.sqlite`, a `ZLayer` that spins up a temp-file SQLite DB and runs the SQLite migrations, fresh per test.

### Backend request flow

Route files (`backend/http/*Routes.scala`) follow one pattern throughout: each handler builds a `ZIO[R, Response, Response]` (both channels are `Response`) and ends with `.merge` to collapse it to `URIO[R, Response]` — failures are just error `Response`s constructed via `JsonSupport.errorResponse`, not exceptions. `RouteSupport` holds the three cross-cutting checks every route composes from: `csrfCheck` (state-changing requests require the `X-Requested-With` header — see below), `authenticatedUser`, and `requireAdmin`. `Main.scala` concatenates all route files with `++` into one `Routes` value.

Session auth is a random opaque token in an `HttpOnly`/`SameSite=Lax` cookie (`SessionAuth`), not JWT. CSRF is mitigated by requiring a custom header on mutating requests (cross-site requests can't set one without a CORS preflight) rather than a token scheme — Google's OAuth callback is the one exception, since it's a top-level browser redirect that can't carry custom headers; it's protected by the `oauth_state` cookie/query-param match instead. Google sign-in additionally requires `email_verified` from the tokeninfo response before matching/creating an account by email.

Security-relevant events (failed logins, rate-limit trips, admin-route denials, admin user-management actions) go through a dedicated slf4j logger named `"security"` (see `logback.xml`), separate from general app logging — grep for `LoggerFactory.getLogger("security")` to find all call sites.

Config (`AppConfig`) is HOCON (`application.conf`) with `${?ENV_VAR}` overrides, loaded via zio-config + zio-config-magnolia; field names are camelCase, config keys kebab-case (`.kebabCase` provider transform bridges them). `.env.example` documents every override.

### Frontend routing/auth

`AppRouter.Page` is the route ADT; `Page.guardFor(page)` returns one of `RequireAuth` / `RequireAnon` / `Public`, and `App.renderFor` in `App.scala` is the single place that redirects based on it plus the loaded session state — individual pages don't guard themselves. Admin pages additionally check `user.isAdmin` inline in `App.renderPage` (falling back to `ForbiddenPage`), since that's not expressible in the guard enum. Authenticated pages render through `components/AppShell`, which owns the navbar, theme toggle, and logout.

`ApiClient` wraps `fetch`, returning `EventStream[Either[ApiError, A]]` (per the laminar skill's explicit-flattening convention) rather than throwing — network failures are caught by `networkSafe` and turned into a `Left` too, so `onMountCallback`-driven loads never hang on a rejected promise. DTOs are the `shared` module's zio-json-derived case classes, used verbatim on both sides.

**Laminar version note**: `.split` is deprecated in the pinned Laminar version in favor of `.splitSeq` (takes a `StrictSignal` with public `.now()`/`.key` instead of `(key, initial, signal)`) — this contradicts some external Laminar docs/examples; follow the existing `splitSeq` usages in `pages/*.scala` instead.
