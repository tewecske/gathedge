# gathedge

A skeleton for a full-stack Scala 3 web application: a ZIO HTTP backend, a Scala.js + Laminar
single-page frontend, Postgres, and one shared module that both ends compile against.

It is a *starting point*, not a demo — there is no example feature in it. What it does have is the
part of an application that is the same every time and tedious to get right:

- **Accounts and sessions** — sign-up, sign-in, an opaque session cookie (`HttpOnly`, `SameSite=Lax`),
  CSRF via a required custom header, and per-key rate limiting with a persisted attempt history.
- **Email confirmation** — single-use tokens, a resend endpoint that is not an account-enumeration
  oracle, and a switch deciding whether an unconfirmed account may sign in at all.
- **Social sign-in and account linking** — Google and Microsoft, behind one `OAuthClient` interface;
  identities live in their own table and an unlinkable last credential is refused.
- **An administrator surface** — user management, per-account diagnostics, an audit trail, and a
  system overview that reports the deployment without exposing a single configured secret.
- **Internationalization** — English and Hungarian across the whole stack, with the language in the
  URL and the catalogs shared by the browser and the server.
- **Themes**, light and dark, working signed out.
- **Paged, sorted and filtered listings** whose entire state lives in the address bar.
- **A declarative API**: every endpoint is described once in `modules/shared`, and the routes, the
  OpenAPI document and the frontend client are all derived from that one description.

## Starting a project from it

```bash
git clone <this repo> myapp && cd myapp
./scripts/init-project.sh myapp "My App"
```

That renames the Scala package root, the sbt project and organization, the Docker image names, the
database defaults, the Nix attributes and the wordmark — everything the skeleton calls `gathedge`.
Run it once, before writing any code. `--reinit-git` additionally starts a fresh git history; it is
off by default because the skeleton's history is worth keeping (see below).

Then:

```bash
cp .env.example .env             # init-project.sh does this for you if .env is absent
docker compose up -d postgres
npm install && npm run dev       # backend :8080, Scala.js watch, vite :5173
```

Open <http://localhost:5173>. `APP_ENV=dev` provisions a bootstrap administrator
(`admin@example.com` / `changeme123` unless `.env` says otherwise).

The first thing to replace is
`modules/frontend/src/main/scala/<slug>/frontend/pages/HomePage.scala` — the placeholder landing
page. [docs/ADDING-A-FEATURE.md](docs/ADDING-A-FEATURE.md) is the recipe for everything after that.

**The git history is a reference.** The skeleton used to ship two worked example features — a to-do
board and a group/invitation feature with roles, membership and emailed invites. They were removed
so a new project starts clean, but commit `fd57e99` still has both, end to end: endpoint
descriptions, repositories, services, routes, pages, and their tests. When
`docs/ADDING-A-FEATURE.md` says "a repository looks like this", that commit is where a complete one
lives.

## Commands

**Build**

```bash
sbt compile
```

**Dev stack** (backend + Scala.js watch + Vite, three panes)

```bash
npm run dev                 # concurrently
npm run dev:tmux            # same, in a tmux window
```

Vite serves the frontend at `:5173` and proxies `/api/*` to the backend at `:8080`. Postgres must be
up first: `docker compose up -d postgres` (naming the service matters — a bare `docker compose up`
builds the whole deployment stack).

**Tests**

```bash
sbt test                                                     # everything
sbt backend/test                                             # backend only
sbt "backend/testOnly <slug>.backend.service.AuthServiceSpec"  # one spec
sbt sharedJVM/test                                           # shared validation logic
sbt frontend/test                                            # Laminar components, jsdom
```

Backend and shared specs run against a fresh, migrated SQLite database per layer instantiation — no
external services needed. Two exceptions:

```bash
docker compose up -d postgres
RUN_POSTGRES_TESTS=1 sbt backend/test    # PostgresIntegrationSpec, via testcontainers
```

```bash
npm --prefix e2e install                 # needs the full stack running
npm --prefix e2e test
```

**Format**

```bash
sbt scalafmtAll
```

**Deployment**

```bash
cp .env.example .env         # COMPOSE_PROFILES=db in it bundles Postgres
docker compose up -d --build # http://localhost:${HTTP_PORT:-8080}
```

Two images out of one multi-stage `Dockerfile`: the backend on a JRE base, and nginx serving the
built SPA and proxying `/api` through to it. nginx is the only published port.

## Where things are

```
modules/shared/     cross-compiled: endpoint descriptions, DTOs, domain types, validation, i18n keys
modules/backend/    ZIO HTTP: routes, services, Quill repositories, Flyway migrations, config
modules/frontend/   Scala.js: Laminar pages, Waypoint routing, the generated API client
web/                Vite host for the frontend, plus the message catalogs both ends read
e2e/                Playwright, against the real stack
docs/               ADDING-A-FEATURE.md
scripts/            init-project.sh
nix/                a NixOS module and package definitions, as an alternative to Docker
```

`CLAUDE.md` is the architectural writeup: why the database strategy is what it is, how the endpoint
descriptions constrain the routes and the client, what the aspects do, and the rules that are easy
to get wrong. Read it before changing anything structural. `summary.md` is the product-level
description of what the skeleton's screens do.
