# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Commands

**Build**
```
sbt compile                 # or: sbt root/compile
```

**Dev stack** (backend + Scala.js watch + Vite, three panes)
```
npm run dev                 # concurrently: backend reStart, frontend fastLinkJS, vite dev
npm run dev:tmux            # same, in a tmux window (./dev-tmux.sh)
```
Vite serves the frontend at `:5173` and proxies `/api/*` to the backend at `:8080`. Start Postgres first: `docker compose up -d postgres` (copy `.env.example` to `.env` first). A bare `docker compose up -d` starts the whole deployment stack instead — name the service.

`.env` reaches the dev backend through `reStart / envVars` in `build.sbt`, not through the shell. `sbt` cannot read the file directly, so without this wiring, `${?ENV_VAR}` overrides in `application.conf` keep their defaults under plain `reStart`. Editing `.env` needs `reload`, not just `reStart`, since it is an sbt setting. A real shell variable still wins over the file. Most overrides default to something usable in dev — an unconfigured OAuth provider is just one the sign-in page shows no button for.

**Tests**
```
sbt test
sbt backend/test
sbt "backend/testOnly gathedge.backend.service.AuthServiceSpec"
sbt sharedJVM/test
sbt frontend/test
npm --prefix web run typecheck
```
Backend/shared specs run against a fresh, migrated SQLite DB per layer (`TestDataSource.sqlite`) — no external services needed. Exception:
```
docker compose up -d postgres
RUN_POSTGRES_TESTS=1 sbt backend/test
```
`PostgresIntegrationSpec` is the only place the Postgres dialect actually runs (testcontainers). Everything else is Postgres-checked at compile time by Quill, but tested at runtime on SQLite.

**E2E** (needs the full stack running — Postgres, backend, vite)
```
npm --prefix e2e install
npm --prefix e2e test
```

**Format**
```
sbt scalafmtAll
```

**Deployment** (`Dockerfile`, `docker-compose.yml`, `docker/`)
```
cp .env.example .env         # COMPOSE_PROFILES=db in it bundles Postgres
docker compose up -d --build # http://localhost:${HTTP_PORT:-8080}
```
One multi-stage `Dockerfile` builds two images: `--target backend` (sbt-native-packager on a JRE base) and `--target web` (nginx serving `web/dist`). **nginx serves the SPA, not the backend** — the backend mounts only `/api`, and nginx proxies `/api` through too, keeping the SPA same-origin with the API. That's what the `X-Requested-With` CSRF check depends on, since there's no CORS. Only nginx's port is published; the backend container stays internal.

The frontend build stage needs both a JDK and Node: `vite build` shells out to `sbt frontend/fullLinkJSOutput`, and Tailwind scans `modules/frontend/src`. The whole repo is the build context.

The `db` compose profile decides bundled vs. external Postgres. With it (the `.env.example` default), `docker compose up` starts `postgres`; drop it and set `DB_URL` to reach a database you already run. The backend has no `depends_on: postgres` on purpose — that would break `up` when the profile is off — so `restart: unless-stopped` plus Flyway's fail-fast startup cover the boot race instead.

Containers log to stdout via `docker/logback.xml`, since the in-repo `logback.xml` writes to a relative `logs/` path. `APP_ENV` defaults to `dev`, so the stack runs over plain HTTP out of the box. `APP_ENV=production` additionally requires `SESSION_COOKIE_SECURE=true`, an `https://` `PUBLIC_BASE_URL`, and a non-default `DB_PASSWORD` — `AppConfig.productionIssues` refuses to boot otherwise. It assumes a TLS terminator sits in front of the `web` container.

## Architecture

Three-module sbt build: `modules/shared` (cross JVM/JS), `modules/backend` (ZIO HTTP), `modules/frontend` (Scala.js + Laminar + Waypoint), plus `web/` (Vite host) and `e2e/` (Playwright). Full domain writeup: `summary.md`.

### Build-wide gotchas

- **`-noindent` is set in `commonSettings`.** Significant-indentation Scala 3 syntax (`object Foo:`, colon-indent bodies, braceless `for`/`match`) does not compile. Every block needs explicit `{ }`, including `for { ... } yield`.
- **`-Werror`, not `-Xfatal-warnings`.** The latter is deprecated in this Scala version, and its own deprecation warning becomes fatal under itself — don't reintroduce it.
- `evictionErrorLevel := Level.Warn` is deliberate: `quill-jdbc-zio` pins an older `zio-json` than the rest of the build. The eviction is safe; don't remove the override without checking.

### Dual-dialect database strategy

Postgres is the only real target; SQLite exists only so tests don't need Docker. Each repository is a plain interface in `backend/db/*Repository.scala`, backed by one generic implementation (`*RepositoryLive[Dialect, Naming]`) parameterized on a `ZioJdbcContext` — query bodies are written once. Interface, implementation, and both `ZLayer`s live in one file per repository. `XRepository.live`/`.test` are thin layers supplying a Postgres or SQLite context to the same class; `.test` is named that way because only tests may wire it.

Each repository method logs one INFO line via `QuillRepository.logged` (`<table>.<method>` plus ids and a row count). **That line must never carry a password hash, session id, verification token, other opaque token, OAuth subject, or email address** — see the scaladoc on `logged`.

Trade-off: since the dialect isn't known at the `quote` call site, every query is a Quill "Dynamic Query" — rendered to SQL at runtime rather than compiled in. Functionally fine (verified against real Postgres in `PostgresIntegrationSpec`), but it gives up compile-time SQL generation/caching.

Flyway migrations are duplicated per dialect under `backend/src/main/resources/db/migration/{postgresql,sqlite}/`, kept schema-identical — all timestamps are epoch-millis `BIGINT`/`INTEGER`, not native timestamp types, to keep both dialects portable. The skeleton ships one squashed `V1__init.sql` per dialect: `users`, `sessions`, `oauth_identities`, `email_verification_tokens`, `login_attempts`, `audit_log`. Every user reference cascades on delete **except** `login_attempts.user_id` and `audit_log.actor_user_id`, which `SET NULL` — deleting an account must not erase the record of what was done to it. `audit_log.actor_email` is a denormalized snapshot for the same reason.

Before writing `V2`: SQLite can't drop a `UNIQUE` column or alter a constraint — either needs a full table rebuild (safe, since nothing enables `PRAGMA foreign_keys`). `ADD COLUMN` is one of the few `ALTER TABLE` forms SQLite does support.

**No foreign key is enforced on SQLite**, so no cascade or constraint violation is exercised outside `PostgresIntegrationSpec`. This cost real time once: `AdminService.deleteUser` returned 500 for months for users who had touched a table whose `users` reference had no `ON DELETE` action, and the SQLite suite passed the whole time. Any referential-integrity change — including a new table referencing `users` — needs its regression test under `RUN_POSTGRES_TESTS=1`; that spec's delete-user test enumerates every such table.

`Main.scala` always wires the Postgres implementations. Tests wire SQLite via `TestDataSource.sqlite`, which spins up a fresh temp-file DB and migrates it per test.

**The app owns a named Postgres schema (`db.schema`, `DB_SCHEMA`, default `gathedge`)** rather than living in `public` — that lets it share a database without colliding, and makes "drop everything it owns" one statement. Quill's `querySchema` never names it; `search_path` does the whole job.

Two readers must agree on this name: `FlywayMigrator.migrate` passes it to `.schemas(...)`, which creates the schema on first boot and keeps `flyway_schema_history` inside it. `DataSourceFactory.postgresLive` sets it on the Hikari pool (`Connection.setSchema`), which pgjdbc applies as `SET SESSION search_path`. Point the two at different schemas and the app migrates one and queries the other — a failure that only shows up at the first request. The pool is built before migration runs, and Postgres tolerates a `search_path` naming a schema that doesn't exist yet, so the ordering in `Main` is safe. `baselineOnMigrate` stays `false` for Postgres.

The schema parameter is an `Option` because SQLite has no schemas: `TestDataSource.sqlite` passes `None`. `PostgresIntegrationSpec` sets both halves, so it's the only place that can catch a mismatch between them.

### Backend request flow

**Every endpoint is described declaratively**, once, in `shared`'s `api/*Endpoints.scala` (`Auth`, `Admin`, plus one per feature), via zio-http's `Endpoint` API. Three things derive from that description, so none can drift from it:

- `backend/http/*Routes.scala` implements handlers via `implementHandler` — a plain function from input to output, with no path pattern, status, or JSON handling of its own, and no way to answer a body the description forbids.
- `backend/http/DocsRoutes.scala` generates one OpenAPI document from `<Resource>Endpoints.all`, served with Swagger UI at `/api/docs/openapi`. Every operation is marked with an `apiKey`-cookie security scheme, except those hand-listed in `DocsRoutes.publicEndpoints` — `OpenApiSpec` pins that list on both sides so it can't rot silently.
- `frontend/api/ApiClient.scala`/`AdminApiClient.scala` call the endpoints through `EndpointClient.scala`, so no page names a path or method directly.

The two OAuth routes (`/api/auth/{provider}/start` and `/callback`) are the deliberate exception, still built as `Method / path -> handler`. Both are top-level browser navigations that answer every outcome as a 302, so there's no body protocol to describe — they're absent from the OpenAPI document and exempt from the CSRF header. One pair serves every provider; an unknown or unconfigured one gets the same 404. `GET /api/auth/providers`, which lists the configured providers for the sign-in form, is an ordinary described endpoint.

Every route file exposes `Routes[R, Response]`. `Main.scala` concatenates them with `++` and wraps the result in `RouteSupport.handleFailures`, which passes failure responses through and turns defects (every `.orDie`) into a logged cause plus a generic JSON 500. It also replaces `Routes.notFound`: an unmatched request would otherwise get zio-http's bare `Response.error(NotFound, path)` — no JSON, path echoed back. **This 404 differs from `ApiFailure.NotFound`**, which means a named resource is missing; this one means the path isn't part of the API at all. It's answered in the same JSON shape as everything else, without echoing the path.

Cross-cutting checks are `HandlerAspect`s in `RouteSupport`, attached to whole `Routes` values with `@@`:

- `csrf` — requires `X-Requested-With` on methods other than GET/HEAD/OPTIONS.
- `authenticated` / `adminOnly` — resolve the session cookie once and hand the `User` to the handler via the environment.
- `requestContext` — supplies the client address (for rate limiting) and raw session cookie (for logout), neither describable as an endpoint input. It needs `AppConfig` for `app.trusted-proxy-hops`, the only thing that makes `X-Forwarded-For` trustworthy: at `0` (default), `RouteSupport.clientAddress` ignores the header entirely; otherwise it reads **right to left**, taking the entry that many hops in, since only the right-hand end is unforgeable. Getting this wrong shipped once: with `0` behind the compose stack's nginx, every request carried the proxy's address, and since `AuthService` blocks on *any* tripped rate-limit key, five failed sign-ins from anyone locked out every account. `RouteSupportSpec` pins each branch. `RateLimitKey.ip` keys IPv6 on the /64 (an address can change within one) and IPv4 exactly (aggregating would merge unrelated customers behind one NAT).

Aspects attach last-runs-first (`routes @@ authenticated @@ csrf` checks CSRF first). A route file mixing public and protected endpoints builds two `Routes` values and `++`s them. **Never attach a context-providing aspect directly to a `handler` that also takes path parameters** — it hands the handler a bare `Request` instead of the `(param, Request)` tuple it expects, which compiles and then throws `ClassCastException` at request time. Attach it to the `Routes` value instead; `RouteGuardsSpec` drives a mutating admin route with a path parameter through the real stack to catch this.

One mapping per service failure enum lives in `backend/http/ApiFailures.scala` (`auth`/`authLogin`/`verifyEmail`/`resendVerification`/`admin`), turning a `*Failure` into the right `ApiFailure` status. Route files call those instead of writing their own, so two endpoints raising the same failure can't answer differently. Each mapping returns the union of cases it can actually produce, tying it to the endpoint description.

Things to know before extending the API:

- **Each endpoint declares exactly the statuses a well-behaved caller can get, as the union of its error channel.** `.outErrors(...)` attaches a subset of `ApiEndpoint.failure`'s vocabulary — `GET /api/me` declares only 401, `POST /api/auth/login` declares six. The list is whatever `ApiFailures` produces for that handler, plus 401 wherever `authenticated`/`adminOnly` guards it (a session can expire on any open page). That 401 bypasses the endpoint's own codecs, but a generated client still has to decode it — an undeclared status fails as a defect ("Expected status code ... but found Unauthorized"), so an expired session would otherwise crash the page instead of redirecting it.
  - Three statuses are deliberately undescribed: the aspects' 403, 429, and 500. None answers a well-formed request. `EndpointClient.run` flattens each into `ApiError(0, ...)`. The exceptions are cases where the *service* raises the status: login declares 403 for `EmailNotVerified`, and signup/login/verification-resend declare 429 for `RateLimited` — both compile-forced by their `ApiFailures` mappers.
  - `outErrors[E]` only checks each codec is a subtype of `E`; a union wider than the codec list compiles and fails at encode time instead, so keep the two in step by hand. The other direction is sound — a handler failing with an undeclared case doesn't compile.
  - `OpenApiSpec` pins the full per-operation status table, plus the rule itself (403 only on login, 429 only on signup/login/verification-resend, 500 nowhere). `ApiEndpointsSpec` pins that aspect-built bodies stay `ApiFailure`-shaped on the wire, described or not. Adding an endpoint fails that spec until its row is added.
- **`codecError` is the other half of the error story.** `outErrors` describes what the handler raises; `codecError` covers what fails before the handler runs — an unparseable body, wrong `Content-Type`, a bad header/query codec. Left at the library default, this answers a private, undocumented `text/html` shape at status 400. `ApiEndpoint.withCodecError` routes it through `ApiFailure.BadRequest("Malformed request")` instead, applied to every endpoint with an input. Apply it whenever an endpoint has an input, query parameter, or header codec, and declare `failure.badRequest` even if the handler can't fail on its own — otherwise the 400 matches nothing and the client turns it into a defect. The discarded `HttpCodecError` is never logged; zio-http offers no hook there.
- **Only `ApiFailure.BadRequest` carries `fieldErrors`** — the one failure attributable to individual form inputs. A 409, for example, is `{"error":{...},"message":...}` with no `fieldErrors` key, and still decodes fine (the field defaults to empty). `error` is a `MessageRef` — the half the SPA actually renders; `message` is the English fallback for callers with no catalog (see **Internationalization**).
- **`.out[Unit](Status.NoContent)` doesn't work for a browser client.** Every 204 uses `.outCodec(HttpCodec.status(Status.NoContent))` instead — `out[Unit]` needs `Content-Length: 0` to recognize an empty body, which a 204 must never send (RFC 9110 §8.6).
- **The session `Set-Cookie` is described but optional** (`ApiEndpoint.sessionCookie`). `Set-Cookie` is a forbidden response header the browser hides from `fetch`, so a required codec would fail every successful login. The JVM test client still sees the real value.
- **Two codec stacks exist and happen to agree.** `Endpoint` codecs are zio-schema; DTOs and `dto.ErrorResponse` still use zio-json in `JsonSupport`. `ApiEndpointsSpec` pins that agreement on real bytes — it's tested, not guaranteed.

Session auth is a random opaque token in an `HttpOnly`/`SameSite=Lax` cookie, not JWT. CSRF is mitigated by requiring a custom header on mutating requests, since cross-site requests can't set one without a CORS preflight — except the OAuth callback, a top-level redirect that can't carry custom headers, protected instead by an `oauth_state` cookie/query-param match. That cookie holds `nonce|intent|locale`; the echoed `state` parameter holds only the nonce, so the sign-in-vs-link decision and the redirect locale never leave the `HttpOnly` jar.

Outbound calls go through zio-http's `Client`, not `java.net.http` — a synchronous `send` inside `attemptBlocking` is uninterruptible, and used to pin a blocking-pool thread when a provider stalled after connecting. There's deliberately no CORS middleware: the SPA is same-origin with the API, and CORS would let a cross-site page preflight past the CSRF check.

### Social sign-in and account linking

External identities live in `oauth_identities(provider, subject, user_id)`, unique on `(provider, subject)` — not a column on `users`. **`(provider, subject)` is the only thing that may decide which account a social sign-in enters.** When it misses and the email is already taken, `AuthService.loginWithOAuth` **fails** with `OAuthAccountExists` rather than logging into the existing account — a provider that lets a user assert an address they don't control would otherwise enable account takeover, and `email_verified` doesn't generalize across providers (Microsoft asserts no such claim). The recovery route is signing in normally and linking from `/settings`.

`AuthService.unlinkOAuth` refuses to remove an account's last credential (409) — without that check, a social-only account could lock itself out in one click. `SettingsPage` disables the button too, but the server check is the real one.

Providers are `OAuthClient` implementations behind `OAuthClients.forProvider`, which returns `None` for anything unconfigured. Google verifies its `id_token` via the `tokeninfo` endpoint. **Microsoft has no such endpoint** — rather than add a JWT/JWKS dependency, `MicrosoftOAuthClient` decodes the payload and validates `iss`/`aud`/`exp` as plain fields, which OIDC Core 1.0 §3.1.3.7 permits only because the token arrives over a direct back-channel TLS call. `decodeIdTokenClaims` must never be reused on a token that reached the server any other way — that needs a real signature check. `MicrosoftOAuthClientSpec` pins each claim check.

Config lives under `oauth.{google,microsoft}`; `MICROSOFT_TENANT` defaults to `common`, admitting both work/school and personal accounts.

### Email verification

`users.email_verified_at` plus single-use tokens in `email_verification_tokens`, issued by signup/resend and redeemed by `verifyEmail`. Tokens are 32 `SecureRandom` bytes, stored in plaintext like session ids; they last 24 hours, and `SessionReaper` prunes expired ones alongside sessions.

**Tokens are always issued and redeemable; `app.require-email-verification` (default `false`) only gates login.** With it on, `signup` returns no session (`SignupResponse(user, signedIn)`, since a missing `Set-Cookie` is invisible to `fetch`), and `login` fails `EmailNotVerified` **after** the password check, so the gate isn't an account-enumeration oracle. That's the API's only 403.

`POST /api/auth/verification/resend` answers 204 for an unknown address, a verified one, and a fresh send alike — page copy calling it must stay equally non-committal. It has its own `RateLimitKey.verification` namespace. OAuth accounts start verified only when the provider asserts `email_verified` (Google does, Microsoft never); admin-created and bootstrap accounts start verified.

Mail goes through `EmailSender`: `SmtpEmailSender` (Jakarta Mail) when `mail.smtp.host` is set, `LoggingEmailSender` otherwise — the same "empty string switches it off" pattern as OAuth. `AppConfig.productionIssues` refuses to boot in production with verification required and no SMTP host. Tests use `RecordingEmailSender` to read tokens, which is why e2e doesn't cover this flow.

### Administrator diagnostics, audit trail, system overview

The admin surface answers *about* accounts, never *out of* them. `AdminService.userDetail` projects away the password hash, OAuth subject, verification token, and session id before anything leaves the server — `dto.AdminSessionInfo` carries no session identifier at all, since the `sessions` primary key *is* the bearer token. That's why session revocation is per-account only. `ConfigSummary` follows the same rule: every credential-bearing setting is a boolean or absent, and `db.url` has userinfo stripped — `SystemServiceSpec` checks this against the serialized field names, not a remembered list.

Two read-only tables back it:

- **`login_attempts`** — every sign-in outcome, written at all six exits of `AuthService.login`. The write is `catchAllCause`'d: recording an attempt is observability, and a dead table must never turn a correct password into a failed sign-in.
- **`audit_log`** — every admin action, written by `AuditTrail.record`, which is also the one place emitting the `SecurityLog` line. The write is likewise swallowed on failure.

`AdminActor(userId, clientIp)` lets the audit row record where an action came from — `AdminRoutes` is the one route set carrying two context-providing aspects (`adminOnly`, `requestContext`), both on the `Routes` value, never on a handler.

**Each auth path has its own `RateLimitKey` namespace** — `email:`/`ip:` for login, `verify:` for resends, `signup:` for signup — as a correctness rule, not tidiness. Signup used to share `email:` with login; five signup attempts against a known address then locked its owner out of signing in, for free to the attacker. `AuthServiceSpec` pins both directions. `AdminService.lockoutKeysFor` deliberately excludes the signup key, since a signup budget no longer keeps anybody out.

`RateLimiter` exposes `status`/`snapshot`/`clearAll`/`pruneStale`. `AdminService.clearLockout` clears the address key, the verification key, **and** every recent `ip:` key — `login` blocks on *any* tripped dimension, so clearing one alone leaves the user locked out. `SystemService.prune` calls `pruneStale`, not `clearAll` — housekeeping must not quietly unblock an account under active brute-force.

`BackgroundJobs` is a `Ref` the two daemon loops report to each pass, so a dead `.forever` loop doesn't look identical to an idle one.

`SessionReaper.sweep` removes expired sessions, spent verification tokens, and `login_attempts` rows older than `app.login-attempt-retention-days` (30) — the only table an unauthenticated caller can grow at will, since `login` writes a row at every exit including the rate-limiter's own. `audit_log` is deliberately never swept.

`SystemService` memoizes `DbStats` for 30s, since one overview is ~15 `COUNT(*)` queries; config summary and runtime figures stay live. **`prune` clears the memo**, or the maintenance button would appear to do nothing — `SystemServiceSpec` asserts it.

**Test gotcha:** `Request.get("/path?a=b")` doesn't parse a query string — the whole string becomes the path, matching no route, answering with a 404 that looks like a broken endpoint. Use `RouteRunner.getWithQuery`.

**No bearer credential may appear in a log line, including the URL.** `RouteSupport.withRequestLogging` logs the path only, never the query string — the OAuth authorization code arrives as `?code=`. A credential in a *path segment* needs explicit scrubbing in `loggableUrl`, pinned in `RouteSupportSpec`; nginx's `access_log off` handles its own side. All opaque tokens come from `security/Tokens.scala` — **never `zio.Random`**, whose live implementation is a 48-bit LCG. The OAuth `state` nonce was once generated with `Random.nextUUID` and was predictable, on the one route that can't require the CSRF header.

Security-relevant events (failed logins, rate-limit trips, admin-route denials, admin actions) go through a dedicated `"security"` slf4j logger, emitted via `security/SecurityLog.scala` — grep `SecurityLog.` for call sites. Don't reintroduce direct `LoggerFactory.getLogger` calls. Admin actions also land in `audit_log`, always through `AuditTrail`.

Config (`AppConfig`) is HOCON with `${?ENV_VAR}` overrides, camelCase fields mapped to kebab-case keys. `.env.example` documents every override. `AppConfig.provider` is installed as the config provider in `Main.bootstrap`, and `AppConfig.live` composes it in itself so the layer stays self-sufficient in tests — without that, `ZIO.config` calls elsewhere would silently fall back to env vars and system properties.

The server is wired with `Server.customized`, combining a `Server.Config` (host/port) and a `NettyConfig` (`maxThreads`, `0` = 2× cores) — both derived from `AppConfig`, one source of truth.

### Paged, sorted, filtered listings

`GET /api/admin/users` and `GET /api/admin/audit` are paged, ordered, and narrowed **by the database**, answering `{items, total}` rather than a bare list. `total` counts what the filter matches, and the page buttons are arithmetic on it.

`dto.Paging` is the single source of policy: `firstPage` (1), `defaultPageSize` (20), `pageSizes` (20/50/100), `maxPageSize`, `pageCount`. Both ends read it, so the dropdown can't offer a size the server would clamp — **the cap is the protection**, since `pageSize` reaches `LIMIT` directly. A page number floors at 1 but isn't clamped at the top, since the server hasn't counted anything when it reads the parameter; an empty page with an honest total lets the browser self-correct.

**Pages are numbered from one, everywhere** — in the URL, the request, and the button. `?page=0` isn't an error; it's just the first page.

**The audit trail used to be cursor-paged and isn't any more.** A cursor is stabler under concurrent writes, but can't number pages, jump, or count a total — that trade-off is recorded on `AdminEndpoints.auditLog`.

Ordering is three-state per column: unsorted → ascending → descending → unsorted. The third state matters, since it's the only way back to the listing's own default order. An unrecognized `sort` value isn't an error — the repository falls through to the default, so a client can stop sending a sort without a coordinated deploy. Two columns are unsortable: the user list's sign-in badge (joined from an in-memory `Ref`, no `ORDER BY` possible) and the audit trail's target (two fields rendered as one).

The user list's search box (`q=`) is a case-insensitive substring match, case-insensitive because addresses are stored lowercased and the needle is lowercased too — no `lower()` for the dialects to disagree about. It's debounced in the browser (300ms), never throttled server-side, and never logged.

Each listing holds its whole request in one case class (`UserQuery`/`AuditQuery`) rather than separate `Var`s, since the parts aren't independent — narrowing the search invalidates the page index, for example. `reset` is the rule every writer but "turn the page" goes through.

**That case class is the argument of a route, not page-local state — the URL is where listing state lives.** `/en/admin/users?page=2&sort=email&dir=desc&q=bob` is the whole thing: bookmarkable, shareable, walkable via the back button. `AdminUsersPage`/`AdminAuditPage` take a `Signal[Query]` and `Observer[Query]`; `App` supplies both. Writes use `pushState`, so the back button steps through listing history — except a search term being typed further, which `replaceState`s (one search costs one history entry, however long the term gets), and a no-op reload, which touches no history at all.

Five things follow, each a bug if gotten wrong:

- **`App` renders both listings through `SplitRender.collectSignalPF`, not `child <-- signal.map(render)`.** With the query inside `Page`, every keystroke is a new `Page` value; the ordinary path would rebuild the element each time, losing focus and in-flight state. The signal renderer builds the element once and feeds it the query instead.
- **Each listing has two routes, query first** — a `Route.onlyQueryPF` for a non-default query, a `Route.staticPartial` for the bare path. The router uses the first that matches, in both directions, so order matters.
- **The history tag carries the query too.** Waypoint restores a page from the tag, not by re-matching the URL — a tag that dropped the query would silently lose the filter on back-button restore.
- **A hand-edited URL is bounded, not refused.** `pageSize` gets clamped with the same helpers the server uses; an unknown `sort` column is dropped.
- **The search box follows the query, never the reverse** — what reaches the query is the `onInput` *stream*, not the box's `Var`. Reading the box's `Var` back to detect a clear caused a real bug: since the page mounts inside an Airstream transaction, the box could still read `""` while the query already said `bob`, and the debounce would wrongly conclude the box had been cleared and reset the filter. A stream has no such stale value to compare. `AdminUsersPageSpec.withPageMountedInTransaction` mounts through a `child <--` switch to catch exactly this.

`Page.Admin`/`Page.AdminAudit` are case classes, so `AdminSubmenu` matches by type, not `==` — a tab shouldn't go dark just because a column got sorted. Nav links always point at `Page.Admin()`, the clean default view.

### Internationalization

English and Hungarian, across the stack. A third language needs `Locale`'s enum, a `messages.<code>.json`, and a `plural` match in `MessageCatalog` — `MessagesSpec` fails until the catalog has every key.

**The URL decides the language.** Every SPA route is served under a prefix (`/en/settings`, `/hu/settings`), which is the only thing that decides what a page renders in — Waypoint's `basePath` handles it, so `Page` itself carries no locale field. Consequences:

- The locale is known synchronously, before anything mounts, so `I18n.t` is an ordinary synchronous function — no `localeSignal`, no reactive binding for static copy.
- **Switching language is a full page navigation, not a re-render** — `LanguagePicker` is plain anchors to the other prefix. A route's `basePath` is fixed at router build time, so the document must reload; that reload also fetches the new catalog.
- `web/index.html` carries a small inline boot script that adds a prefix to a prefix-less URL before the bundle loads, and sets `<html lang>` — kept there rather than in nginx or Vite so dev and prod behave identically.

**Precedence** (the rule easy to get backwards):
```
explicit URL prefix  >  stored users.locale  >  localStorage  >  navigator.language  >  en
```
A stored preference must **never** override a prefix the visitor actually opened. The account's language only decides anything when the URL had no prefix at all. When the URL was explicit and disagrees with the stored preference, the URL wins and gets written back. `LocaleSync.decide` states this as a pure function; `LocaleSyncSpec` states it as a table.

`users.locale` exists for two things the URL can't reach: transactional email (composed server-side, no browser present) and seeding a new browser's first prefix-less visit.

**Server-side, messages are codes, not prose.** Every `ApiFailure` carries a `MessageRef` (a catalog key plus arguments) alongside an English fallback `message`; the browser resolves it at `EndpointClient.toApiError`, so pages just render `err.message` and know nothing about i18n. No signature in `ApiFailures.scala` takes a locale, and none should.

`shared/validation/Validation.scala` fails with `MessageRef` too, since it runs on both platforms — a form and its endpoint produce identical messages by construction.

**The catalogs are one JSON file per language** — `web/public/locales/messages.{en,hu}.json` — served by Vite/nginx and read by the backend off the same classpath files, no generated duplicate to go stale. The backend loads them at boot and **fails the boot if one is missing or malformed**, like a Flyway migration. The server's only use for them is the two email templates.

JSON was chosen over a typed Scala trait so translators need not touch Scala — the cost is that missing keys are runtime bugs. `MessagesSpec` buys the guarantee back: identical key sets across locales, every `MessageKeys`/`UiKeys` constant present in all of them, no orphaned `ui.` keys, matching `{0}`/`{1}` placeholders, complete `.one`/`.other` plural pairs.

Two things Hungarian breaks that English hid:

- **The `"(s)"` idiom** (`"session(s)"`) has no Hungarian equivalent — a numeral is always followed by the singular. Use `MessageCatalog.plural` with a `.one`/`.other` pair.
- **The definite article alternates `a`/`az`** by the following sound, which no placeholder can carry — phrase around it rather than interpolating a noun into a Hungarian sentence.

`RouteSupport.RequestContext` carries `locale`, read from an `X-Locale` header the client sends on every call (falling back to `Accept-Language`, then `en`). The OAuth `/start` route takes `?locale=` instead, since it carries no client headers, and tucks it into the `oauth_state` cookie to survive the round trip.

**Page copy goes through `shared/i18n/UiKeys.scala`**, the sibling of `MessageKeys` — the split is *who mints the message*: server-chosen strings are `MessageKeys`, page copy is `UiKeys`, namespaced `ui.`. It lives in `shared` so `MessagesSpec` (JVM) can walk it the same way. Four consequences:

- Field labels aren't duplicated — a form input whose label already exists as `MessageKeys` (`field.email`, etc.) renders that one.
- Only labels are translated, never stored values — a `<select>`'s `option` value stays the enum's code, never the translated text.
- `Formats.dateTime`/`.date` follow the page's language, not the browser's; the timezone still comes from the browser.
- Brand and language names stay untranslated by design (the endonym rule — "Magyar" is never "Hungarian").

Frontend specs assert on message *keys*, not copy, which is the stronger check — it proves the page routed the right message, and `MessagesSpec` separately proves the key has real copy. **e2e matches on English copy**, so changing an English string is a change to e2e's fixtures too. `e2e/tests/translation.spec.ts` is the only place a real catalog renders, catching keys nothing else can see.

### Frontend routing/auth

`AppRouter.Page` is the route ADT (locale-free). `Page.guardFor(page)` returns `RequireAuth`/`RequireAnon`/`Public`; `App.renderFor` is the single place that redirects on it — pages don't guard themselves. Admin pages additionally check `user.isAdmin` inline, falling back to `ForbiddenPage`.

Pages render through `components/AppShell`, which owns the navbar, theme control, and logout. `AppShell.render` is the authenticated shape; `.renderPublic` (sign-in/sign-up) drops session-only chrome and centers the content. Its three dropdowns (nav, account, language) share `components/Popover`.

**The nav is built twice and shown once** — a hamburger popover below `lg`, a button row at `lg` and up, picked by breakpoint utilities. They're separate elements built by separate helpers, since one Laminar element belongs to one place in the DOM; `display:none` also removes the hidden copy from the accessibility tree, keeping `getByRole` unambiguous in e2e.

**The theme control works signed out**, so `AppState` owns the theme rather than deriving it from the user. Signed in, a toggle goes to the server first and only moves locally on success; signed out, it's a local write. `Main` calls `AppState.initTheme()` before the first render to avoid a flash of the wrong theme.

`components/OAuthButtons` are plain anchors, never `ApiClient` calls — the flow is a chain of top-level redirects, so the document has to navigate.

`ApiClient`/`AdminApiClient` are generated from the endpoint descriptions, returning `EventStream[Either[ApiError, A]]` rather than throwing. `EndpointClient.run` is the seam between ZIO and Airstream: it maps declared failures to `ApiError` and flattens anything else — a defect, a dead socket, an undeclared status — into the same shape with status `0`. A failure is always a value on the success path, so loads never hang on a rejected promise.

**Laminar note**: `.split` is deprecated in favor of `.splitSeq` (takes a `StrictSignal`) — follow existing `splitSeq` usages in `pages/*.scala`, not older external docs.

### Vocabulary

The first feature: a shared dictionary of English, German, and Hungarian words, and the tags an account puts on them. `words`, `word_translations`, `tags`, `word_tags`, `word_tag_pairs` are all owned by one `WordRepository`, since they're written together and a transaction can't span repositories.

**A word belongs to nobody; a tag belongs to exactly one account, and tagging is the whole of "this is in my vocabulary."** There's no `user_words` table — `mine=true` is a join through `word_tags`. Wiktionary rows and user-typed rows share the same table and search, told apart by `source`. `POST /api/words` is *ensure and attach*, not create-or-409 — two learners adding the same word is the normal case, and the second gets the first's translations.

Three load-bearing columns:

- **`gender` is part of a word's identity** — `der See` (lake) and `die See` (sea) are two rows. It's `NOT NULL` with `''` for "not gendered", since NULL counts as distinct under a `UNIQUE` index and would let duplicates in.
- **`frequency_rank` is `NOT NULL` with a large sentinel**, not nullable, since it drives listing order and the dialects disagree on where NULLs sort.
- **Search is a prefix match on `text_norm`** (`LIKE 'hau%'`), lowercased on write so there's no `lower()` for the dialects to disagree about. A substring search would need `pg_trgm`.

**Translation edges are stored in both directions**, so every read is a simple filter with no union. `origin` distinguishes `dictionary`, `user`, and `pivot` (German–Hungarian inferred by joining two translations of the same English sense — no free source states this directly).

**Two tag controls, not one.** `WordQuery.tagId` (`?tag=`) narrows the listing. The **collect tag** — where a tick files a word — is separate page-local state in `localStorage`, deliberately not in the URL: the filter is worth bookmarking, the collect tag is working state nobody wants to send anybody. Consequences: a tick reads the collect tag, not the filter; creating a tag selects it for collecting without touching the URL; `reconcileCollectTag` re-points it if the tag list changes, since a tag deleted elsewhere would otherwise orphan every tick.

**A tick says a word is being learned; a chip says which translation is the answer.** Clicking a translation chip marks it as the practice answer for that word, inside the collect tag (`word_tag_pairs`). Consequences:

- It's a join table, since several translations can be marked for one (word, tag).
- Both directions are stored in one transaction — a chip click tags both the word it's on and the translation.
- `untagWord` deletes both-direction pairs in the repository, since a transaction can't span repositories; unmarking a chip removes only its two pair rows.
- Marks travel per row (`WordSummary.pairs`); the browser filters by collect tag, since the server is never asked "which is selected for tag X."
- The three-translation display cap is unioned with whatever the reader has already marked, so a mark never silently falls outside what's shown.

**A word can have translations in more than one language, and both add-word forms allow for that** — the listing's form has one box per other language; `WordDetailPage`'s form offers only the word's two other languages, since a word can't translate itself.

`GET /api/words` and `GET /api/words/{id}` are the only endpoints using `RouteSupport.optionalUser` instead of `authenticated` — they answer a visitor with no session at all, which is why the feature needs no sign-up.

**The dictionary is imported, not migrated** (`backend/tools/DictionaryImport`). `--seed` loads the committed sample; `--raw` streams the 2.6 GB wiktextract dump, filtered before JSON decode. It runs its own Flyway migration and is idempotent — existing keys are read back, not inserted. Deployments get a small pre-built seed (`scripts/build-dictionary-seed.sh`), never the raw dump. The data is CC BY-SA 4.0; `ui.words.attribution` is required, not decorative.

### Guest accounts

**A guest is an ordinary `users` row with `is_guest` set and no address**, holding an ordinary session — every route, aspect, repository, and foreign key treats it like any user. Upgrading it is an `UPDATE`, not a data copy. `users.email` is nullable to allow this (NULLs are distinct under its `UNIQUE` index).

Four decisions:

- **A guest is minted on the first write, never on a page view** — `POST /api/guest`, called when a visitor's first row-click needs one. A session per page view would be a row per crawler.
- **The transfer code is the account's only other credential** — 16 Crockford base32 symbols (~80 bits), stored and answered once like a session id, normalized to fold commonly misread characters. It works on several devices and is revoked once the account gets a password.
- **Each guest path has its own rate-limit namespace** (`guest:`, `claim:`), for the same reason the rest of `AuthService` does.
- **`SessionReaper` sweeps only *empty* guests** — no tags, words, or transfer code, past `app.guest-retention-days`. A guest holding anything is never swept. Guest sessions last a year, not a week.

The four guest endpoints have three failure enums and four `ApiFailures` mappings, since a shared enum would force the wrong status onto some of them.

**Two Postgres-only traps, invisible to SQLite.** Quill names a SQL alias after the quoted lambda's parameter, and `user` is a reserved word in Postgres — `users.filter(user => …)` breaks there but not on SQLite. Every quoted lambda over `users` is named `row` instead. Both the guest upgrade and the reaper's query shipped green and 500'd against real Postgres before `PostgresIntegrationSpec` caught it; any new query over `users` belongs there too.
