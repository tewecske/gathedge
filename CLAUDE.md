# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Writing style

Write in ASD-STE100 Simplified Technical English and follow Zinsser's four principles: **simplicity,
brevity, clarity, humanity**. Short sentences. One idea per sentence. Active voice. No needless words.

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
Vite serves the frontend at `:5173` and proxies `/api/*` to the backend at `:8080`. Start Postgres first: `docker compose up -d postgres` (copy `.env.example` to `.env` first). Name the service; bare `up` starts the whole stack.

`.env` reaches the dev backend through `reStart / envVars` in `build.sbt`, not the shell. Editing `.env` needs `reload`, not just `reStart`. A real shell variable still wins.

**Parallel worktree** (a second branch with its own dev stack)
```
scripts/new-worktree.sh <branch>    # -> ../wt-<n>-<branch>
scripts/rm-worktree.sh <n>          # remove it + drop its schema
```
`<n>` is the lowest free slot. It offsets the dev ports (`SERVER_PORT=8080+n*10`, `VITE_PORT=5173+n*10`, bumped past anything listening) and names a `gathedge_wt<n>` schema cloned from `gathedge` (data included) in the same Postgres. The new `.env` is git-ignored and per-worktree. Needs the `postgres` compose service up, or `psql`/`pg_dump` on the PATH.

**Tests**
```
sbt test
sbt backend/test
sbt "backend/testOnly gathedge.backend.service.AuthServiceSpec"
sbt sharedJVM/test
sbt frontend/test
npm --prefix web run typecheck
```
Backend/shared specs run against a fresh, migrated SQLite DB per layer (`TestDataSource.sqlite`). Exception:
```
docker compose up -d postgres
RUN_POSTGRES_TESTS=1 sbt backend/test
```
`PostgresIntegrationSpec` is the only place the Postgres dialect runs (testcontainers).

**E2E** (needs the full stack running)
```
npm --prefix e2e install
npm --prefix e2e test
```

**Format**
```
sbt scalafmtAll
```

**Security scanning** (CVE / known vulnerabilities)
```
./scripts/cve-scan.sh            # npm lockfiles + Dockerfile + staged backend JARs
./scripts/cve-scan.sh --images   # also scan the container images (slow)
```
One engine, Trivy, no API key. `trivy.yaml` holds the shared policy (HIGH/CRITICAL, fixed
only); `.trivyignore` records accepted findings. The same scan runs in
`.github/workflows/security.yml` on every push and PR, plus a weekly image scan; results
go to the repo's **Security** tab. `.github/dependabot.yml` adds security-update PRs for
npm, Docker, and Actions. Scala deps get alerts through the `sbt-dependency-submission`
job (Dependabot has no sbt ecosystem). Keep the Trivy action version and any tool pins in
step across the workflow and the script.

**Deployment**
```
cp .env.example .env         # COMPOSE_PROFILES=db in it bundles Postgres
docker compose up -d --build # http://localhost:${HTTP_PORT:-8080}
```
One multi-stage `Dockerfile` builds two images: `--target backend` and `--target web` (nginx serving `web/dist`). nginx serves the SPA and proxies `/api` through; only nginx's port is published.

The frontend build stage needs a JDK and Node. The `db` compose profile decides bundled vs. external Postgres. The backend has no `depends_on: postgres`; `restart: unless-stopped` plus Flyway's fail-fast startup cover the boot race.

Containers log to stdout via `docker/logback.xml`. `APP_ENV` defaults to `dev`. `APP_ENV=production` requires `SESSION_COOKIE_SECURE=true`, an `https://` `PUBLIC_BASE_URL`, and a non-default `DB_PASSWORD` — `AppConfig.productionIssues` refuses to boot otherwise.

## Architecture

Three-module sbt build: `modules/shared` (cross JVM/JS), `modules/backend` (ZIO HTTP), `modules/frontend` (Scala.js + Laminar + Waypoint), plus `web/` (Vite host) and `e2e/` (Playwright). Full domain writeup: `summary.md`.

### Build-wide gotchas

- **`-noindent` is set in `commonSettings`.** Significant-indentation Scala 3 (`object Foo:`, braceless `for`/`match`) does not compile. Every block needs explicit `{ }`.
- **`-Werror`, not `-Xfatal-warnings`.** The latter is deprecated; don't reintroduce it.
- `evictionErrorLevel := Level.Warn` is deliberate; don't remove without checking.

### Dual-dialect database strategy

Postgres is the only real target; SQLite exists only so tests need no Docker. Each repository is one trait + one `*RepositoryLive[Dialect, Naming]` + `live`/`test` ZLayers, in one file. `.test` is named that because only tests may wire it.

Each repository method logs one INFO line via `QuillRepository.logged`. That line must never carry a password hash, session id, token, OAuth subject, or email.

Every query is a Quill Dynamic Query (rendered to SQL at runtime), since the dialect isn't known at the `quote` call site.

Flyway migrations are duplicated under `backend/src/main/resources/db/migration/{postgresql,sqlite}/`, kept schema-identical. Timestamps are epoch-millis `BIGINT`/`INTEGER`.

Before writing `V2`: SQLite can't drop a `UNIQUE` column or alter a constraint; `ADD COLUMN` is supported.

**No foreign key is enforced on SQLite.** Cascades and constraints are exercised only by `PostgresIntegrationSpec`. Any referential-integrity change (including a new table referencing `users`) needs its regression test under `RUN_POSTGRES_TESTS=1`.

`Main.scala` wires the Postgres implementations. Tests wire SQLite via `TestDataSource.sqlite`.

**The app owns a named Postgres schema (`db.schema`, `DB_SCHEMA`, default `gathedge`).** `FlywayMigrator.migrate` and `DataSourceFactory.postgresLive` must agree on it. The schema parameter is an `Option` because SQLite has no schemas.

### Backend request flow

**Every endpoint is described once** in `shared`'s `api/*Endpoints.scala`, via zio-http's `Endpoint` API. Three things derive from it:

- `backend/http/*Routes.scala` implements handlers via `implementHandler`.
- `backend/http/DocsRoutes.scala` generates the OpenAPI document, served with Swagger UI at `/api/docs/openapi`. Public endpoints are hand-listed in `DocsRoutes.publicEndpoints`.
- `frontend/api/ApiClient.scala`/`AdminApiClient.scala` call the endpoints through `EndpointClient.scala`.

The two OAuth routes (`/api/auth/{provider}/start` and `/callback`) are plain `Method / path -> handler`, built as top-level 302 navigations. They're absent from the OpenAPI document and exempt from the CSRF header. `GET /api/auth/providers` is an ordinary described endpoint.

Every route file exposes `Routes[R, Response]`. `Main.scala` concatenates them with `++` and wraps them in `RouteSupport.handleFailures` (failures pass through; defects become a generic JSON 500; not-found gets a JSON body without the path echoed).

Cross-cutting checks are `HandlerAspect`s in `RouteSupport`, attached to whole `Routes` values with `@@`:

- `csrf` — requires `X-Requested-With` on non-GET/HEAD/OPTIONS.
- `authenticated` / `adminOnly` — resolve the session cookie and hand the `User` to the handler.
- `requestContext` — supplies client address (for rate limiting) and raw session cookie. `app.trusted-proxy-hops` decides whether `X-Forwarded-For` is trusted (default `0`: ignored). `RateLimitKey.ip` keys IPv6 on the /64, IPv4 exactly.

Aspects attach last-runs-first. **Never attach a context-providing aspect to a `handler` that takes path parameters** — it compiles, then throws `ClassCastException` at request time. Attach to the `Routes` value instead.

One mapping per service failure enum lives in `backend/http/ApiFailures.scala`, returning the union of cases it produces.

Things to know before extending the API:

- **Each endpoint declares exactly the statuses a caller can get, as the union of its error channel.** `.outErrors(...)`; add 401 wherever `authenticated`/`adminOnly` guards it.
  - Three statuses are deliberately undescribed: the aspects' 403, 429, 500. `EndpointClient.run` flattens each into `ApiError(0, ...)`. Exceptions: login declares 403 for `EmailNotVerified`; signup/login/verification-resend declare 429 for `RateLimited`.
  - Keep `.outErrors` and the codec list in step by hand.
  - `OpenApiSpec` pins the per-operation status table. `ApiEndpointsSpec` pins that aspect-built bodies stay `ApiFailure`-shaped.
- **`codecError` covers what fails before the handler runs.** Apply `ApiEndpoint.withCodecError` to every endpoint with an input/query/header codec, and declare `failure.badRequest`.
- **Only `ApiFailure.BadRequest` carries `fieldErrors`.** `error` is a `MessageRef`; `message` is the English fallback.
- **204s use `.outCodec(HttpCodec.status(Status.NoContent))`, not `.out[Unit]`.**
- **The session `Set-Cookie` is described but optional** (`ApiEndpoint.sessionCookie`).
- **Two codec stacks exist and agree**: `Endpoint` codecs (zio-schema) and DTOs (zio-json). `ApiEndpointsSpec` pins the agreement.

Session auth is a random opaque token in an `HttpOnly`/`SameSite=Lax` cookie, not JWT. CSRF: a custom header on mutating requests; the OAuth callback uses an `oauth_state` cookie/query-param match instead.

Outbound calls go through zio-http's `Client`. There's no CORS middleware.

### Social sign-in and account linking

External identities live in `oauth_identities(provider, subject, user_id)`, unique on `(provider, subject)`. **`(provider, subject)` is the only thing that may decide which account a social sign-in enters.** On a miss with the email taken, `AuthService.loginWithOAuth` fails with `OAuthAccountExists`.

`AuthService.unlinkOAuth` refuses to remove an account's last credential (409).

Providers are `OAuthClient` implementations behind `OAuthClients.forProvider` (returns `None` for anything unconfigured). Google verifies its `id_token` via the `tokeninfo` endpoint. `MicrosoftOAuthClient` decodes the payload and validates `iss`/`aud`/`exp` as plain fields (permitted only over a direct back-channel TLS call). `decodeIdTokenClaims` must never be reused on a token that reached the server any other way.

Config lives under `oauth.{google,microsoft}`; `MICROSOFT_TENANT` defaults to `common`.

### Email verification

`users.email_verified_at` plus single-use tokens in `email_verification_tokens`. Tokens are 32 `SecureRandom` bytes, plaintext, 24-hour expiry; `SessionReaper` prunes expired ones.

**Tokens are always issued and redeemable; `app.require-email-verification` (default `false`) only gates login.** With it on, `signup` returns no session, and `login` fails `EmailNotVerified` **after** the password check.

`POST /api/auth/verification/resend` answers 204 for an unknown, verified, or fresh address alike. It has its own `RateLimitKey.verification` namespace. OAuth accounts start verified only when the provider asserts `email_verified` (Google does, Microsoft never); admin-created and bootstrap accounts start verified.

Mail goes through `EmailSender`: `SmtpEmailSender` when `mail.smtp.host` is set, `LoggingEmailSender` otherwise. `AppConfig.productionIssues` refuses to boot in production with verification required and no SMTP host. Tests use `RecordingEmailSender`.

### Administrator diagnostics, audit trail, system overview

`AdminService.userDetail` projects away the password hash, OAuth subject, verification token, and session id. `dto.AdminSessionInfo` carries no session identifier. Session revocation is per-account only. `ConfigSummary` follows the same rule (credentials are booleans or absent; `db.url` has userinfo stripped).

Two read-only tables:

- **`login_attempts`** — every sign-in outcome, written at all six exits of `AuthService.login`, `catchAllCause`'d.
- **`audit_log`** — every admin action, written by `AuditTrail.record`, also the one place emitting the `SecurityLog` line. Also swallowed on failure.

`AdminActor(userId, clientIp)` records where an action came from. `AdminRoutes` carries two context aspects (`adminOnly`, `requestContext`), both on the `Routes` value.

**Each auth path has its own `RateLimitKey` namespace** (`email:`/`ip:` for login, `verify:` for resends, `signup:` for signup). `AdminService.lockoutKeysFor` excludes the signup key.

`RateLimiter` exposes `status`/`snapshot`/`clearAll`/`pruneStale`. `AdminService.clearLockout` clears the address, verification, **and** every recent `ip:` key. `SystemService.prune` calls `pruneStale`.

`BackgroundJobs` is a `Ref` the daemon loops report to.

`SessionReaper.sweep` removes expired sessions, spent verification tokens, and `login_attempts` rows older than `app.login-attempt-retention-days` (30). `audit_log` is never swept.

`SystemService` memoizes `DbStats` for 30s. **`prune` clears the memo.**

**Test gotcha:** `Request.get("/path?a=b")` doesn't parse a query string. Use `RouteRunner.getWithQuery`.

**No bearer credential may appear in a log line, including the URL.** `RouteSupport.withRequestLogging` logs the path only. Opaque tokens come only from `security/Tokens.scala` — never `zio.Random`. Security-relevant events go through the `"security"` slf4j logger via `security/SecurityLog.scala`. Don't reintroduce direct `LoggerFactory.getLogger`.

Config (`AppConfig`) is HOCON with `${?ENV_VAR}` overrides. `.env.example` documents every override. `AppConfig.provider` is installed in `Main.bootstrap`, and `AppConfig.live` composes it.

The server is wired with `Server.customized` (a `Server.Config` and a `NettyConfig`), both derived from `AppConfig`.

### Observability

OpenTelemetry, exported as OTLP. Two halves:

- **The HTTP server span** is ours: `RouteSupport.serverSpan`, a `HandlerAspect` built like `requestLogging`, attached last in `Main.allRoutes` so it is the parent of the log line, the usage row, the handler, and the SQL spans below. `telemetry.Telemetry` is the only wiring (`OpenTelemetry.global` + `contextJVM` + `tracing`).
- **Per-SQL-statement spans, plus JVM and HikariCP-pool metrics**, come from the OpenTelemetry Java agent. No app code, no Quill change. The agent is inert unless `OTEL_JAVAAGENT_ENABLED=true`. Two ways it reaches the JVM: the `Dockerfile` `ADD`s it into the backend image (`docker compose`), and `build.sbt` resolves it into the `OtelAgent` ivy config and puts `-javaagent:` on `reStart / javaOptions` when the env toggle is set (`~backend/reStart`, `npm run dev`). One version, kept in step across both.

`contextJVM` (a `ThreadLocal` context store, not a `FiberRef`) is deliberate: it is what lets an agent-made JDBC span nest under our span. The agent then propagates that context onto the blocking pool where Quill runs. Without the agent (`sbt test`, or dev with the toggle off) the tracer is a no-op, so nothing extra is needed or resolved.

**A span name or attribute is as readable as a log line.** `spanName` is the method plus the `{id}`-collapsed route and never the query string — the same rule `loggableUrl`/`normalizeRoute` follow for the OAuth `?code=`. The agent's SQL sanitiser (on by default) reduces `db.statement` literals to `?`; bind values are never captured. OTLP goes to a collector on the compose network; nothing new is published.

Local drill-down: `docker compose -f docker-compose.yml -f docker-compose.observability.yml up` (Jaeger, traces only) for the all-in-Docker stack, or `docker compose -f docker-compose.observability.yml up -d jaeger` alongside `npm run dev`. `docker-compose.observability.yml` publishes Jaeger's OTLP ports (4317/4318) so a host-run backend reaches them at the agent's own default endpoint. Percentiles/time-series and production: a separately installed SigNoz (or any OTLP backend) via `OTEL_EXPORTER_OTLP_ENDPOINT`, with `OTEL_METRICS_EXPORTER=otlp` (Jaeger rejects metrics, so the default is `none`).

### Paged, sorted, filtered listings

`GET /api/admin/users` and `GET /api/admin/audit` are paged, ordered, and narrowed **by the database**, answering `{items, total}`.

`dto.Paging` is the single source of policy (`firstPage`, `defaultPageSize`, `pageSizes`, `maxPageSize`, `pageCount`). **Pages are numbered from one, everywhere.** `?page=0` is the first page.

Ordering is three-state per column: unsorted → ascending → descending → unsorted. An unrecognized `sort` value falls through to the default. Two columns are unsortable: the user list's sign-in badge and the audit trail's target.

The user list's search box (`q=`) is a case-insensitive substring match, debounced in the browser (300ms), never logged.

Each listing holds its whole request in one case class (`UserQuery`/`AuditQuery`). `reset` is the rule every writer but "turn the page" goes through.

**The query case class is a route argument, not page-local state — the URL is where listing state lives.** `AdminUsersPage`/`AdminAuditPage` take a `Signal[Query]` and `Observer[Query]`; `App` supplies both. Writes use `pushState` (except a search term being typed, which `replaceState`s).

Five things:

- **`App` renders both listings through `SplitRender.collectSignalPF`, not `child <-- signal.map(render)`.**
- **Each listing has two routes, query first** (`Route.onlyQueryPF` then `Route.staticPartial`).
- **The history tag carries the query too.**
- **A hand-edited URL is bounded, not refused** (`pageSize` clamped; unknown `sort` dropped).
- **The search box follows the query, never the reverse.**

`Page.Admin`/`Page.AdminAudit` are case classes; `AdminSubmenu` matches by type. Nav links always point at `Page.Admin()`.

### Internationalization

English and Hungarian. A third language needs `Locale`'s enum, a `messages.<code>.json`, and a `plural` match in `MessageCatalog`.

**The URL decides the language** (every SPA route is under `/en/` or `/hu/`; Waypoint's `basePath` handles it):

- `I18n.t` is synchronous.
- **Switching language is a full page navigation** (`LanguagePicker` is plain anchors).
- `web/index.html` has an inline boot script that adds a prefix and sets `<html lang>`.

**Precedence:** explicit URL prefix > `users.locale` > `localStorage` > `navigator.language` > `en`. `users.locale` only decides transactional email and a new browser's first prefix-less visit.

**Server-side, messages are codes, not prose.** Every `ApiFailure` carries a `MessageRef` plus an English fallback `message`. `shared/validation/Validation.scala` fails with `MessageRef` too.

**The catalogs are one JSON file per language** — `web/public/locales/messages.{en,hu}.json`. The backend loads them at boot and fails the boot if one is missing or malformed. `MessagesSpec` enforces identical key sets, all `MessageKeys`/`UiKeys` present, matching placeholders, complete plural pairs.

Two Hungarian gotchas:

- No `"(s)"` idiom — use `MessageCatalog.plural`.
- The article `a`/`az` alternates — phrase around it.

`RouteSupport.RequestContext` carries `locale`, from an `X-Locale` header (falling back to `Accept-Language`, then `en`). The OAuth `/start` route takes `?locale=` instead.

**Page copy goes through `shared/i18n/UiKeys.scala`** (`ui.`-namespaced); server-minted messages are `MessageKeys`. Consequences:

- Field labels aren't duplicated.
- Only labels are translated, never stored values.
- `Formats.dateTime`/`.date` follow the page's language.
- Brand and language names stay untranslated (endonym rule).

Frontend specs assert on message **keys**. **e2e matches English copy.** `e2e/tests/translation.spec.ts` is the only place a real catalog renders.

### Frontend routing/auth

`AppRouter.Page` is the route ADT (locale-free). `Page.guardFor(page)` returns `RequireAuth`/`RequireAnon`/`Public`; `App.renderFor` redirects on it. Admin pages check `user.isAdmin` inline.

Pages render through `components/AppShell`. `AppShell.render` is authenticated; `.renderPublic` drops session-only chrome.

**The nav is built twice and shown once** — hamburger popover below `lg`, button row at `lg` and up.

**The theme control works signed out**, so `AppState` owns the theme. `Main` calls `AppState.initTheme()` before the first render.

`components/OAuthButtons` are plain anchors, never `ApiClient` calls.

`ApiClient`/`AdminApiClient` are generated from the endpoint descriptions, returning `EventStream[Either[ApiError, A]]`. `EndpointClient.run` maps declared failures to `ApiError` and flattens everything else into status `0`.

**Laminar note**: `.split` is deprecated in favor of `.splitSeq`.

### Vocabulary

The first feature: shared dictionary of English, German, Hungarian words, plus tags. `words`, `word_translations`, `tags`, `word_tags`, `word_tag_pairs` are owned by one `WordRepository`.

**A word belongs to nobody; a tag is owned by one account but visible to all; tagging is "in my vocabulary."** No `user_words` table — `mine=true` is a join through `word_tags`. `POST /api/words` is *ensure and attach*, not create-or-409.

**Ownership gates writes, not visibility.** `WordService.requireOwnTag` answers `TagNotFound` for anyone else's id. `GET /api/tags` answers every tag, marked `ownedByMe`. `POST /api/tags/{id}/copy` seeds a copy of the copier's own from another tag's name and word memberships, in one transaction. Tag names are unique per owner, case-insensitively.

**Two per-account quotas** (`AppConfig.quotas`): tags owned, and `word_tag_pairs` rows owned (a marked translation is two rows). Not time-windowed. Checked in `WordService` (`checkQuota`/`tagQuota`/`pairQuota`), never `RateLimitKey`. Each has a soft threshold (writes through with `dto.*Response.warning`) and a hard one (409 `WordFailure.*QuotaExceeded`). Enforced at `createTag`, `selectPair` (never charged for an already-marked pair), and `copyTag` (checks both dimensions before writing).

Four load-bearing columns:

- **`gender` is part of a word's identity** (`der See` and `die See` are two rows). `NOT NULL` with `''` for "not gendered". The column stores the gender itself (`masculine`/`feminine`/`neuter`), not an article.
- **`frequency_rank` is `NOT NULL` with a large sentinel.**
- **Search is a prefix match on `text_norm`** (`LIKE 'hau%'`), lowercased on write.
- **`is_form` is derived from `word_forms`, not authoritative.** It is what the listing's "main words only" filter reads, so the predicate is a column rather than a `NOT EXISTS`, and `idx_words_main_rank` (partial, `WHERE is_form = FALSE`) answers the default order. `word_forms` stays the truth: `WordRepository.insertForms` and `.deleteWordForms` are the only writers, each updating the flag in its own transaction, and a third writer of that table must do the same. Deleting one `(form, relation)` pair frees the word only when no relation is left.

**`LanguageProfile` (`shared/domain/LanguageProfile.scala`) is the only place an article literal may appear.** It maps each `WordLanguage` to the genders it has, the article each takes, the article forms its parser recognises, and whether its nouns capitalize. Every display, strip, or picker goes through it — a fifth language is a profile entry, not a grep for `WordLanguage.De`.

**Translation edges are stored in both directions.** `origin` is `dictionary`, `user`, or `pivot`.

**Two tag controls, not one.** `WordQuery.tagId` narrows the listing. The **collect tag** is page-local state in `localStorage`. Consequences: a tick reads the collect tag; creating a tag selects it; `reconcileCollectTag` re-points it.

**A tick means a word is being learned; a chip marks the answer translation** (inside the collect tag, `word_tag_pairs`):

- A join table.
- Both directions stored in one transaction.
- `untagWord` deletes both-direction pairs.
- Marks travel per row (`WordSummary.pairs`); the browser filters by collect tag.
- The three-translation display cap is unioned with the reader's marks.

**A word can have translations in more than one language.**

`GET /api/words` and `GET /api/words/{id}` use `RouteSupport.optionalUser` instead of `authenticated`.

**The dictionary is imported, not migrated** (`backend/tools/DictionaryImport`). `--seed` loads the committed sample; `--raw` streams the wiktextract dump. Data is CC BY-SA 4.0; `ui.words.attribution` is required.

### Bulk import: two paths, one panel

The tag editor's bulk-import panel sniffs its input (`shared/parsing/DelimitedText.sniff`) and forks. Prose keeps the old free-text path unchanged; a delimited paste or file goes through a column-mapping step instead.

**The difference is who decides the pairing.** `bulkImport` *infers* it — two words are a pair only where `word_translations` already links them. `tabularImport` takes the row as given: the reader put both cells on one line, so the pair is written even for words the dictionary has never seen. That is why `POST /api/tags/{tagId}/tabular-import` exists rather than a flag on the old endpoint.

- **The mapping is two language columns plus one extra column per side**, each extra belonging to a specific word. `POST /api/words/column-language-check` samples 20 words per column against **all four** languages and suggests the roles; the reader overrides them. The language guard is kept, moved to this step.
- **Cell parsing is `shared/parsing/WordCell`**, used by the server so the browser's preview and the import cannot disagree. It strips markers so they never reach `text_norm`, reads the gender, and assigns `PartOfSpeech.Phrase` to a cell left holding two or more words. The article is stripped *before* the words are counted, which is what keeps `der Hund` a noun and makes `guten Tag` a phrase with no special case.
- **`shared/parsing/MarkerVocabulary` holds every language's abbreviations, and `forPair` resolves the collisions.** A marker is written in the reader's language, not the column's: German writes `m`/`w`/`s` (where `w` is feminine and `s` neuter), Hungarian `hn`/`nn`/`sn` — on German words, since Hungarian has no gender. The column's own language wins a collision, then the tag's other language, then the rest. Articles stay out of this file; they come from `LanguageProfile`.
- **Gender lands in `words.gender`; a real inflected word in an extra column becomes a `word_forms` row; a bare government marker (`+D`, `(G)`) is stripped and discarded.** There is no lemma-level property column, and a `word_forms` row needs a second word to point at — a self-edge would set `is_form` and drop the lemma out of the listing. Stripping still earns its keep: it is what stops `helfen` and `helfen +D` becoming two rows.
- Rows are written **sequentially**, since `tagMemberships` answers in insertion order and that is the reader's own row order. `importPair` marks them `exact`: the file asserts the pair, which is a stronger claim than the dictionary agreeing. Neither bulk path is pair-quota-gated; `maxTabularRows` and the shared `RateLimitKey.wordUpload` budget are the bounds.
- **`repo.ensureWordCounted` reports whether a word was inserted.** Never infer that from `createdAt` — a frozen test clock and a same-millisecond insert both make it a lie.

### Guest accounts

**A guest is an ordinary `users` row with `is_guest` set and no address**, holding an ordinary session. Upgrading is an `UPDATE`. `users.email` is nullable.

Four decisions:

- **A guest is minted on the first write, never on a page view** — `POST /api/guest`.
- **The transfer code is the account's only other credential** — 16 Crockford base32 symbols, stored and answered once.
- **Each guest path has its own rate-limit namespace** (`guest:`, `claim:`).
- **`SessionReaper` sweeps only empty guests**, past `app.guest-retention-days`. Guest sessions last a year.

The four guest endpoints have three failure enums and four `ApiFailures` mappings.

**Two Postgres-only traps:** Quill names a SQL alias after the quoted lambda's parameter, and `user` is a reserved word in Postgres — name every quoted lambda over `users` `row`. Any new query over `users` belongs in `PostgresIntegrationSpec`.
