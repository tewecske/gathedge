# Deploying gathedge to NixOS

Native Nix build — no Docker daemon on the server. The backend runs as a systemd unit,
nginx serves the SPA and proxies `/api`, and the database is a plain database inside the
host's own Postgres cluster.

The Docker path (`Dockerfile`, `docker-compose.yml`) still works and is unchanged; this is
an alternative, not a replacement.

## What the flake exposes

| Output | What it is |
| --- | --- |
| `packages.<system>.backend` | `bin/gathedge-backend` — the sbt `backend/stage` output, wrapped with the stdout-only logback config |
| `packages.<system>.web` | `web/dist` — the built SPA, used as the nginx vhost root |
| `nixosModules.default` | `services.gathedge.*`, plus the overlay that provides both packages |
| `devShells.default` | JDK 21, sbt, Node 22, psql — the versions the build uses |

## Server configuration

```nix
{
  inputs.gathedge.url = "github:you/gathedge";  # or path:/srv/gathedge

  # ... in the host's module list:
  imports = [ inputs.gathedge.nixosModules.default ];

  # The host owns these; the app module only contributes a database and a vhost.
  services.postgresql.enable = true;
  services.nginx.enable = true;

  services.gathedge = {
    enable = true;
    hostName = "gathedge.lan";
    publicBaseUrl = "http://gathedge.lan";
    environmentFile = "/var/lib/secrets/gathedge.env";
  };
}
```

Two options decide how the deployment is exposed, and both are easy to get wrong:

| Option | Default | What it is |
| --- | --- | --- |
| `production` | `false` | `APP_ENV=production` + `SESSION_COOKIE_SECURE=true`. See **Security** below — switching it on before an administrator exists leaves the deployment with no account. |
| `trustedProxyHops` | `1` | Proxies between the browser and the backend, counted from the **right** of `X-Forwarded-For`. `1` is this module's nginx alone; add one per further hop (a Cloudflare tunnel, a CDN, an ingress). |

`trustedProxyHops` is a security setting in both directions. Too low and every request carries
nginx's address rather than the client's, so `AuthService` rate-limits the whole deployment as one
client — five failed sign-ins from anybody block sign-in, sign-up and verification resends for
*every* account, for as long as failures keep arriving. Too high and an entry of an
attacker-supplied header is treated as the client address. Never claim more hops than you run.

Then on the server:

```
nixos-rebuild switch --flake /path/to/repo#<host>
```

Adding a second app later means importing its module too — `ensureDatabases`,
`ensureUsers` and `virtualHosts` all merge, so nothing here needs to change.

## The secret file

Not in the Nix store — the store is world-readable. Create it by hand (or via
sops-nix/agenix later, no module change needed):

```
install -Dm0400 /dev/stdin /var/lib/secrets/gathedge.env <<'EOF'
DB_PASSWORD=<generate one>
BOOTSTRAP_ADMIN_EMAIL=you@example.com
BOOTSTRAP_ADMIN_PASSWORD=<generate one>
# GOOGLE_CLIENT_ID=
# GOOGLE_CLIENT_SECRET=
# GOOGLE_REDIRECT_URI=http://gathedge.lan/api/auth/google/callback
EOF
```

`DB_PASSWORD` is read twice: by the backend, and by the `gathedge-db-password` oneshot unit
that sets the Postgres role's password to match (`services.postgresql.ensureUsers` cannot
set passwords).

## Security — read before first boot

Over plain HTTP the app must run with `production = false` (the default): under
`APP_ENV=production`, `AppConfig.productionIssues` refuses to start unless
`SESSION_COOKIE_SECURE=true`, `PUBLIC_BASE_URL` is `https://`, and `DB_PASSWORD` differs from the
development default.

The consequence is that `AdminSeeder` auto-provisions an admin account on first start.
**Set `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` before the first
`nixos-rebuild switch`**, or the app comes up with `admin@example.com` / `changeme123`
usable by anyone who can reach port 80. Session cookies also travel in the clear over HTTP,
so keep this on a trusted network.

**`AdminSeeder` is skipped entirely under `production = true`**, and nothing else creates an
administrator from nothing — `AdminService.createUser` needs an authenticated one already. So the
order is fixed: boot once with `production = false`, sign in as the bootstrap admin, change the
password, and only then switch it on. Going straight to production leaves a deployment nobody can
administer.

Moving to a real domain later, in one of two shapes:

- **TLS terminated by this host** — add `enableACME = true; forceSSL = true;` to the vhost, and
  open port 443.
- **TLS terminated in front of it** (a Cloudflare tunnel, a CDN, an ingress) — leave the vhost on
  plain HTTP and raise `trustedProxyHops` by one for the extra hop.

Either way, set `publicBaseUrl` to the `https://` URL and set `production = true`. A `Secure`
cookie is never sent over plain HTTP, so from that point the app can only be signed into over the
https origin: reaching it by bare IP will still load the SPA and then fail to authenticate.

## Releasing a new version

The server builds this repository *from its git remote*, at whatever revision the deployment's
`flake.lock` names. **The push is the artifact** — there is no build output in between to test, so
a release is three steps in two repositories:

```
this repo:      commit and push
your NixOS cfg: nix flake update gathedge   (moves the locked revision), commit, push
the server:     git pull && sudo nixos-rebuild switch --flake .
```

`scripts/release.sh` prepares the first of those and prints the other two:

```
./scripts/release.sh              # what changed, refresh hashes, build, smoke-test, next steps
./scripts/release.sh --check      # ~3s, no build — the pre-push gate
./scripts/release.sh --mark       # record this revision as released, once the server is up
./scripts/release.sh --install-hook
```

**What "prepare" means depends on the diff, which is why the script computes it.** It diffs the
newest `released/*` tag against `HEAD` and reports what that range implies: a hash to recompute, a
migration to rehearse against real Postgres (`RUN_POSTGRES_TESTS=1`, plus a `pg_dump` on the server
before switching), a new configuration key that may need to reach the deployment. Most releases
need none of it and the script says so.

It then builds both packages and asserts the four things that build green and fail at *runtime* —
the catalogs being inside the backend jar, the launcher working with an empty `PATH`, the Tailwind
scan having produced a real stylesheet, and the catalogs reaching `dist/locales`. Each of those has
failed here at least once.

`--install-hook` sets `core.hooksPath` to `scripts/githooks`, so every push runs `--check` on the
commit being pushed (not on the working tree). That check reads the commit's blobs and evaluates
the flake at that revision; it costs about three seconds and refuses a push whose fixed-output
hashes are stale, whose `flake.lock` no longer satisfies `flake.nix`, or whose source filters drop
a directory `build.sbt` reads. `git push --no-verify` skips it.

**What it cannot tell you.** It proves the revision is buildable and self-consistent, not that your
*host* can build it — the evaluation uses this repo's `flake.lock` nixpkgs while the server builds
against its own. It knows nothing about the host either: a port already taken, a secret missing a
key, a systemd unit's `PATH`. And its smoke tests run the packages, not the app, so Flyway against
your real data is still first exercised on the server. That is what the `pg_dump` step and
`nixos-rebuild --rollback` are for.

## Maintenance: the two hashes

Both are fixed-output derivations, so both must be refreshed by hand — `scripts/release.sh` is that
hand, and `nix/inputs.sha256` is how it (and the pre-push check) notice one has gone stale: it
records the digest of each hash's inputs as they stood when that hash was last computed. Nix
evaluation accepts a wrong fixed-output hash quite happily and only fails in the build that needs
it, which on this deployment means on the server.

- **`depsSha256` in `nix/scala.nix`** — whenever `build.sbt` or `project/plugins.sbt`
  dependencies change. The script runs the build and writes back the `got:` value from the
  mismatch; by hand, set it to `lib.fakeSha256`, run `nix build .#backend`, copy the `got:` value.
- **`npmDepsHash` in `nix/web.nix`** — whenever `web/package-lock.json` changes:
  `nix run nixpkgs#prefetch-npm-deps -- web/package-lock.json`.

A nixpkgs bump can in principle move either, since it moves `sbt` and `nodejs` — the stamp
deliberately does not track `flake.lock`, because tripping on every unrelated input bump would
train everyone to ignore it. Both hashes fail loudly, so the build tells you.

## How the JDK is pinned

nixpkgs' `sbt` bakes `-java-home ${jre.home}` into its `conf/sbtopts`, and that beats
`JAVA_HOME`. Setting the environment variable therefore does nothing; the JDK is pinned by
overriding the sbt package's `jre` and feeding it through `mkSbtDerivation.withOverrides`,
which applies it to both the build and the dependency derivation.

On nixpkgs 25.05 the default `sbt` already uses JDK 21, so the override currently resolves
to the same store path — it is there so a nixpkgs bump that changes the default does not
silently move the build to a different JDK.

## How the frontend build stays hermetic

`vite build` is not self-contained: `@scala-js/vite-plugin-scalajs` spawns
`sbt "print frontend/fullLinkJSOutput"` from the repo root and uses the last line of stdout
as the directory to resolve `scalajs:main.js` against.

Rather than patch `web/vite.config.ts`, `nix/web.nix` puts a one-line stub `sbt` on `PATH`
that echoes the store path of the linker output already produced by `nix/scala.nix`. No
source changes, and it works for both the fast and full link tasks since the stub ignores
its arguments.

The web derivation's source is the repo root, not `web/`, because `web/main.css` declares
`@source "../modules/frontend/src"` — Tailwind scans the Scala sources for class names. A
`web/`-only source silently yields a CSS file missing most utilities, with no build error.
That is what step 3 of the verification below checks.

## Verifying a build

Flakes only see files git knows about, so the first time — and after adding any new file
under `nix/` — run `git add` before building, or Nix reports the file as missing:

```
git add flake.nix nix/
nix flake check
nix build .#backend && ./result/bin/gathedge-backend      # needs a reachable Postgres
nix build .#web && ls -la result result/assets
```

The CSS in `result/assets` should be tens of KB. A ~5 KB file means the Tailwind `@source`
scan missed `modules/frontend/src`.

On the server after switching:

```
systemctl status gathedge-backend nginx postgresql
journalctl -u gathedge-backend -f      # Flyway creates the schema and applies V1, then binds
curl -I http://<server>/              # 200, Cache-Control: no-cache
curl http://<server>/api/docs/openapi # proves the nginx -> backend proxy path
```

Then in a browser: sign in, open Settings, and reload on a deep link (that last one
exercises the `/index.html` fallback that Waypoint's routing needs).

`nixos-rebuild --rollback` returns to the previous generation.
