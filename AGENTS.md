# AGENTS.md

Full-stack Scala 3 vocabulary trainer: ZIO HTTP backend + Scala.js/Laminar SPA + Postgres, with a
cross-compiled `modules/shared`. Three-module sbt build plus `web/` (Vite) and `e2e/` (Playwright).

**Read `CLAUDE.md` before touching anything structural** — it is the authoritative writeup of the
rules (database strategy, endpoint derivation, aspects, i18n, auth, listings). `docs/ADDING-A-FEATURE.md`
is the step-by-step recipe, in write order, with a checklist. `README.md` has commands and layout.
This file only records what an agent is otherwise likely to miss or guess wrong.

## Commands

```bash
sbt compile
npm run dev            # backend :8080 + Scala.js watch + vite :5173 (needs Postgres up first)
npm run dev:tmux       # same, in tmux
docker compose up -d postgres   # name the service — bare `up` builds the whole stack

sbt test               # everything; backend/shared run on fresh migrated SQLite, no Docker
sbt backend/test
sbt "backend/testOnly gathedge.backend.service.AuthServiceSpec"   # single spec
sbt sharedJVM/test
sbt frontend/test      # Laminar, under jsdom
npm --prefix web run typecheck

RUN_POSTGRES_TESTS=1 sbt backend/test   # PostgresIntegrationSpec only (needs Postgres up)

sbt scalafmtAll

npm --prefix e2e install && npm --prefix e2e test   # needs the full stack running
```

Verify loop before finishing a change: `sbt scalafmtAll && sbt test` (plus `npm --prefix web run typecheck`).

## Build-wide gotchas (all compile-time traps)

- **`-noindent` is set.** Significant-indentation Scala 3 (`object Foo:`, braceless `for`/`match`) does
  not compile — every block needs explicit `{ }`.
- **`-Werror`, not `-Xfatal-warnings`.** The latter is deprecated here and its own deprecation warning
  becomes fatal under itself. Don't reintroduce it.
- **`.env` reaches the dev backend only through `reStart / envVars` in `build.sbt`**, not the shell.
  Editing `.env` needs `reload` (not just `reStart`). A real shell env var still wins.
- `evictionErrorLevel := Level.Warn` is deliberate (`quill-jdbc-zio` pins an older `zio-json`); don't remove.

## Database (dual-dialect) rules

- Postgres is the only real target; **SQLite exists only so `sbt test` needs no Docker.** Each repository
  is one trait + one `*RepositoryLive[Dialect, Naming]` + `live`/`test` ZLayers, all in one file.
- **Flyway migrations are duplicated** under `backend/src/main/resources/db/migration/{postgresql,sqlite}/`,
  kept schema-identical. All timestamps are epoch-millis `BIGINT`/`INTEGER`.
- **SQLite enforces no foreign keys** (`PRAGMA foreign_keys` is never enabled). Cascades and constraints
  are exercised only by `PostgresIntegrationSpec` — any referential-integrity change (incl. a new table
  referencing `users`) needs its regression test there; the whole SQLite suite passes regardless.
- **`user` is a reserved word in Postgres.** Quill names a SQL alias after the quoted lambda's parameter,
  so name every quoted lambda over `users` `row`, never `user`.
- The app owns a named schema (`db.schema`, default `gathedge`): `FlywayMigrator` and `DataSourceFactory`
  must agree on it, or the app migrates one schema and queries another (only fails at first request).

## API / routes

- **Every endpoint is declared once in `modules/shared/api/*Endpoints.scala`**; routes, OpenAPI doc, and
  the frontend client are all derived from it. Adding an endpoint fails `OpenApiSpec` until its row is
  added, and `DocsRoutes.publicEndpoints` must hand-list anything reachable without a session.
- Cross-cutting checks are `HandlerAspect`s attached to whole `Routes` values (`@@`), running
  last-attached-first. **Never attach a context-providing aspect (`authenticated`/`adminOnly`/
  `requestContext`) to a `handler` that takes path parameters** — it compiles, then throws
  `ClassCastException` at request time.
- **Declare a 401** on every aspect-guarded endpoint, and **`.withCodecError` + a declared 400** on every
  endpoint with an input/query/header codec. Do not declare 403/429/500 for the aspects.
- **204s use `.outCodec(HttpCodec.status(Status.NoContent))`, not `.out[Unit]`** — `out[Unit]` needs a
  `Content-Length: 0` a 204 must not send.
- One `*Failure -> ApiFailure` mapping per service enum lives in `backend/http/ApiFailures.scala`,
  returning the union of cases it produces.

## i18n

- Page copy is `shared/i18n/UiKeys.scala` (`ui.`-namespaced); server-minted messages are `MessageKeys`.
  Every key must exist in **both** `web/public/locales/messages.{en,hu}.json`; `MessagesSpec` enforces
  this. **Never pass a bare string to `I18n.t`.** Translate labels, never stored `<select>` values.
- Frontend specs assert on message **keys** (stronger); **e2e matches English copy** — changing an
  English string changes e2e fixtures.

## Security / logging

- No bearer credential (password hash, session id, token, OAuth subject, email) may appear in any log
  line, including URLs. `RouteSupport.withRequestLogging` logs the path only. Opaque tokens come only
  from `security/Tokens.scala`, never `zio.Random`.
- Each auth path has its own `RateLimitKey` namespace (login/verification/signup/guest/claim) — sharing
  them was a real DoS bug. `QuillRepository.logged` must likewise never log a secret (surrogate id /
  `found=` flag instead).

## Test gotchas

- `Request.get("/path?a=b")` does not parse a query string (matches no route → 404). Use
  `RouteRunner.getWithQuery`.
- Guest accounts are ordinary `users` rows (`is_guest` set, `email` NULL); upgrading is an `UPDATE`, not
  a copy.
