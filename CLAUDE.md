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
Vite serves the frontend at `:5173` and proxies `/api/*` to the backend at `:8080` (prefix kept as-is — backend routes are mounted under `/api`). Postgres must be up first: `docker compose up -d` (copy `.env.example` to `.env` first).

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

Postgres is the only real target (see `docker-compose.yml`); SQLite exists solely so tests don't need Docker. Every repository is a plain interface in `backend/db/*Repository.scala`, backed by a single generic implementation (`*RepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy]` in `backend/db/{Repositories,RepositoriesM2,RepositoriesM3}.scala`) that takes a `ZioJdbcContext[Dialect, Naming]` constructor param — the quoted-query bodies are written once. `PostgresXRepository`/`SqliteXRepository` objects are thin `ZLayer`s that just supply a `PostgresZioJdbcContext`/`SqliteZioJdbcContext` instance to the same generic class. Trade-off: because the dialect isn't statically known at the `quote` call site, every query compiles as a Quill "Dynamic Query" (rendered to SQL at runtime instead of baked in at compile time via the macro) — functionally fine (verified against real Postgres via `PostgresIntegrationSpec`) but gives up Quill's compile-time SQL generation/caching. Flyway migrations are still duplicated per dialect under `backend/src/main/resources/db/migration/{postgresql,sqlite}/`, kept schema-identical (all timestamps are epoch-millis `BIGINT`/`INTEGER`, not native timestamp types, specifically to keep the two dialects portable) — Flyway has no equivalent dialect-abstraction mechanism.

`Main.scala` always wires the Postgres implementations. Tests wire the SQLite ones via `TestDataSource.sqlite`, a `ZLayer` that spins up a temp-file SQLite DB and runs the SQLite migrations, fresh per test.

### Backend request flow

Route files (`backend/http/*Routes.scala`) follow one pattern throughout: each handler builds a `ZIO[R, Response, Response]` and each file exposes a `Routes[R, Response]` — an error channel of `Response` is what zio-http calls "handled", so failures are just error `Response`s constructed via `JsonSupport.errorResponse`, never exceptions and never merged per handler. `Main.scala` concatenates all route files with `++`, then wraps the result in `RouteSupport.handleFailures`, which passes failure `Response`s through and turns defects (every `.orDie` in the services) into a logged cause plus a generic JSON 500.

The cross-cutting checks are `HandlerAspect`s in `RouteSupport`, applied to whole `Routes` values with `@@` rather than called at the top of each handler:

- `csrf` — requires the `X-Requested-With` header, scoped with `.when` to methods outside GET/HEAD/OPTIONS.
- `authenticated` / `adminOnly` — `HandlerAspect[AuthService, User]`: they resolve the session cookie once and hand the `User` to the handler through the environment, so a protected handler reads `ZIO.service[User]` and cannot compile unless some aspect supplies it.

Aspects are attached last-runs-first (`routes @@ authenticated @@ csrf` checks CSRF before touching the session). Route files that mix public and protected endpoints (`AuthRoutes`, `InvitationRoutes`) build two `Routes` values and `++` them. **Never attach a context-providing aspect directly to a `handler` that also takes path parameters** — it hands the handler a bare `Request` where it expects the `(param, Request)` tuple, which compiles and then throws `ClassCastException` at request time. Put it on a `Routes` value.

One mapping per service failure enum lives in `backend/http/FailureResponses.scala` (`auth`/`todo`/`group`/`admin`); route files call those rather than defining their own, so the status codes can't drift between two endpoints that raise the same failure.

Session auth is a random opaque token in an `HttpOnly`/`SameSite=Lax` cookie (`SessionAuth`), not JWT. CSRF is mitigated by requiring a custom header on mutating requests (cross-site requests can't set one without a CORS preflight) rather than a token scheme — Google's OAuth callback is the one exception, since it's a top-level browser redirect that can't carry custom headers; it's protected by the `oauth_state` cookie/query-param match instead. Google sign-in additionally requires `email_verified` from the tokeninfo response before matching/creating an account by email.

Security-relevant events (failed logins, rate-limit trips, admin-route denials, admin user-management actions) go through a dedicated slf4j logger named `"security"` (see `logback.xml`), separate from general app logging. They are emitted via `security/SecurityLog.scala` (`SecurityLog.warn` / `.info`) — grep for `SecurityLog.` to find all call sites. That object logs through ZIO (so the lines carry fiber id and log annotations) and steers the SLF4J backend at the `security` logger with zio-logging's `loggerName` aspect; don't reintroduce direct `LoggerFactory.getLogger` calls.

Config (`AppConfig`) is HOCON (`application.conf`) with `${?ENV_VAR}` overrides, loaded via zio-config + zio-config-magnolia; field names are camelCase, config keys kebab-case (`.kebabCase` provider transform bridges them). `.env.example` documents every override.

### Frontend routing/auth

`AppRouter.Page` is the route ADT; `Page.guardFor(page)` returns one of `RequireAuth` / `RequireAnon` / `Public`, and `App.renderFor` in `App.scala` is the single place that redirects based on it plus the loaded session state — individual pages don't guard themselves. Admin pages additionally check `user.isAdmin` inline in `App.renderPage` (falling back to `ForbiddenPage`), since that's not expressible in the guard enum. Authenticated pages render through `components/AppShell`, which owns the navbar, theme toggle, and logout.

`ApiClient` wraps `fetch`, returning `EventStream[Either[ApiError, A]]` (per the laminar skill's explicit-flattening convention) rather than throwing — network failures are caught by `networkSafe` and turned into a `Left` too, so `onMountCallback`-driven loads never hang on a rejected promise. DTOs are the `shared` module's zio-json-derived case classes, used verbatim on both sides.

**Laminar version note**: `.split` is deprecated in the pinned Laminar version in favor of `.splitSeq` (takes a `StrictSignal` with public `.now()`/`.key` instead of `(key, initial, signal)`) — this contradicts some external Laminar docs/examples; follow the existing `splitSeq` usages in `pages/*.scala` instead.
