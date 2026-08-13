# gathedge

A full-stack Scala 3 web application: a ZIO HTTP backend, a Scala.js + Laminar single-page
frontend, Postgres, and one shared module that both ends compile against.

**The application is a vocabulary trainer** for English, German and Hungarian: a shared dictionary
imported from Wiktionary — every word with its part of speech and, for German nouns, its
`der`/`die`/`das` — that a reader searches and tags with whatever they are learning. It needs no
sign-up: browsing is anonymous, and the first word somebody tags mints them a **guest account**,
which can be carried to another machine with a transfer code or turned into a real account later,
in place, keeping everything on it.

Under that sits the part of an application that is the same every time and tedious to get right:

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

## The vocabulary

`/en/words` is the whole of it: pick a tag, type, and click the rows worth learning. There is no save
button — a click is the entire action.

A row's translations are clickable too. Ticking a word says you are learning it; clicking one of its
translations says *that* is the answer you want to be asked for, which is what the practice screen
will check against. Several translations may be marked for one word, and the one you mark joins the
tag as a word in its own right, so the pair works in both directions.

The dictionary is imported rather than typed. Load the committed sample once, and the dev stack has
real words in it:

```
sbt "backend/runMain gathedge.backend.tools.DictionaryImport --seed"
```

The real thing is a 2.6 GB wiktextract dump of the English Wiktionary, turned into a few-megabyte
seed file by `./scripts/build-dictionary-seed.sh` — a server is given that, never the dump. See
[`data/dictionary/README.md`](data/dictionary/README.md) for both, for the frequency lists that
decide search ranking, and for how German–Hungarian translations are derived (no free source states
them directly). Word data is **CC BY-SA 4.0**, which the word list attributes on screen.

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
