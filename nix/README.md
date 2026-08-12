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

Over plain HTTP the app must run `APP_ENV=dev`: under `APP_ENV=production`,
`AppConfig.productionIssues` refuses to start unless `SESSION_COOKIE_SECURE=true`,
`PUBLIC_BASE_URL` is `https://`, and `DB_PASSWORD` differs from the development default.

The consequence is that `AdminSeeder` auto-provisions an admin account on first start.
**Set `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` before the first
`nixos-rebuild switch`**, or the app comes up with `admin@example.com` / `changeme123`
usable by anyone who can reach port 80. Session cookies also travel in the clear over HTTP,
so keep this on a trusted network.

Moving to a real domain later: add `enableACME = true; forceSSL = true;` to the vhost, set
`publicBaseUrl` to the `https://` URL, and flip `APP_ENV` / `SESSION_COOKIE_SECURE` in
`nix/module.nix` (worth turning into an `enableTls` option at that point).

## Maintenance: the two hashes

Both are fixed-output derivations, so both must be refreshed by hand.

- **`depsSha256` in `nix/scala.nix`** — whenever `build.sbt` or `project/plugins.sbt`
  dependencies change. Set it to `lib.fakeSha256`, run `nix build .#backend`, copy the
  `got:` value from the error.
- **`npmDepsHash` in `nix/web.nix`** — whenever `web/package-lock.json` changes:
  `nix run nixpkgs#prefetch-npm-deps -- web/package-lock.json`.

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
journalctl -u gathedge-backend -f      # Flyway applies V1-V3, then the server binds
curl -I http://<server>/              # 200, Cache-Control: no-cache
curl http://<server>/api/docs/openapi # proves the nginx -> backend proxy path
```

Then in a browser: sign in, open Settings, and reload on a deep link (that last one
exercises the `/index.html` fallback that Waypoint's routing needs).

`nixos-rebuild --rollback` returns to the previous generation.
