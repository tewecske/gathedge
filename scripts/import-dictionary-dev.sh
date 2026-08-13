#!/usr/bin/env bash
#
# Loads a dictionary seed into the local development Postgres.
#
#   ./scripts/import-dictionary-dev.sh                       # newest seed under target/dictionary, else the sample
#   ./scripts/import-dictionary-dev.sh /tmp/seed-20000.tsv.gz
#   ./scripts/import-dictionary-dev.sh --sample              # force data/dictionary/seed.tsv
#   ./scripts/import-dictionary-dev.sh --no-counts           # skip the psql summary at the end
#
# WHY THIS EXISTS
#
# Two things about a dev import are easy to get wrong, and neither is visible until the words fail to
# appear on the page.
#
#   * `sbt runMain` does not read `.env`. Only the dev server does, through `reStart / envVars` in
#     build.sbt — sbt itself has no notion of the file. So a DB_PORT or DB_PASSWORD changed there
#     applies to the running backend and not to an import, which then silently talks to whatever
#     application.conf defaults to. This script reads `.env` itself and exports the values.
#   * `.env`'s DB_URL, when it is set at all, names the compose network's `postgres` host, which does
#     not resolve from the machine running sbt. The URL is therefore rebuilt against localhost and the
#     published DB_PORT. Set DEV_DB_URL to override that (and to point the import at a database
#     compose does not manage, which also turns off everything below that shells out to docker).
#
# The import itself is idempotent — it reads keys back rather than inserting them twice — so re-running
# after a bigger seed inserts only the difference, and running it against a populated database is safe.
#
# Build a seed with scripts/build-dictionary-seed.sh; see data/dictionary/README.md for both halves.

set -euo pipefail

# --- Paths and constants -------------------------------------------------------------------------

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

readonly SAMPLE_SEED="data/dictionary/seed.tsv"
readonly SEED_DIR="target/dictionary"
readonly MAIN_CLASS="gathedge.backend.tools.DictionaryImport"

# How long to wait for the container to answer pg_isready, in seconds.
readonly DB_WAIT_SECONDS=60

# --- Output --------------------------------------------------------------------------------------

if [ -t 1 ]; then
  readonly C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m' C_RED=$'\033[31m' C_BOLD=$'\033[1m' C_OFF=$'\033[0m'
else
  readonly C_GREEN='' C_YELLOW='' C_RED='' C_BOLD='' C_OFF=''
fi

say()   { printf '%s\n' "$*"; }
head1() { printf '\n%s%s%s\n' "$C_BOLD" "$*" "$C_OFF"; }
ok()    { printf '  %sok%s    %s\n' "$C_GREEN" "$C_OFF" "$*"; }
warn()  { printf '  %swarn%s  %s\n' "$C_YELLOW" "$C_OFF" "$*"; }
die()   { printf '%serror%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

usage() {
  sed -n '3,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# --- Steps -----------------------------------------------------------------------------------------

# The newest thing build-dictionary-seed.sh left behind, falling back to the committed sample. `ls -t`
# rather than a glob, so the choice is "the one just built" rather than "the one sorting last".
default_seed() {
  local newest=""
  newest="$(ls -t "$SEED_DIR"/*.tsv.gz "$SEED_DIR"/*.tsv 2>/dev/null | head -1 || true)"
  if [ -n "$newest" ]; then
    printf '%s' "$newest"
  else
    printf '%s' "$SAMPLE_SEED"
  fi
}

# Values compose would use, read from the same file it reads. Sourcing is good enough for the simple
# KEY=value lines .env.example documents; a value containing spaces would need quoting there anyway,
# since compose does no shell parsing of its own.
read_env_file() {
  if [ -f .env ]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
    ok "read .env"
  else
    warn "no .env — using application.conf's defaults (localhost:5432/gathedge, gathedge/gathedge)"
  fi
}

start_database() {
  local user="$1" name="$2"
  head1 "Database"

  command -v docker >/dev/null || die "docker is not on the PATH (set DEV_DB_URL to skip docker entirely)"

  if ! docker compose ps --status running --services 2>/dev/null | grep -qx postgres; then
    say "  starting the postgres service"
    docker compose up -d postgres || die "could not start the postgres service"
  fi

  local waited=0
  until docker compose exec -T postgres pg_isready -U "$user" -d "$name" >/dev/null 2>&1; do
    if [ "$waited" -ge "$DB_WAIT_SECONDS" ]; then
      die "postgres did not become ready within ${DB_WAIT_SECONDS}s"
    fi
    sleep 2
    waited=$((waited + 2))
  done
  ok "postgres is accepting connections"
}

run_import() {
  local seed="$1"
  head1 "Import ($seed)"
  # DB_* are exported by main() rather than passed as -D flags: application.conf reads them as
  # ${?ENV_VAR} substitutions, which Typesafe Config resolves from the real process environment.
  sbt -batch -no-colors "backend/runMain $MAIN_CLASS --seed $seed" \
    || die "the import failed (see the sbt output above)"
  ok "imported"
}

show_counts() {
  local user="$1" name="$2" schema="$3"
  head1 "Rows now in $schema"
  docker compose exec -T postgres psql -U "$user" -d "$name" -v ON_ERROR_STOP=1 \
    -c "select language, count(*) from $schema.words group by 1 order by 1" \
    -c "select origin, count(*) from $schema.word_translations group by 1 order by 1" \
    || warn "could not read the counts back (the import itself reported success)"
}

# --- Entry point -----------------------------------------------------------------------------------

main() {
  cd "$REPO_ROOT"

  local seed="" counts=yes
  while [ $# -gt 0 ]; do
    case "$1" in
      --sample)     seed="$SAMPLE_SEED" ;;
      --seed)       [ $# -gt 1 ] || die "--seed needs a path"; seed="$2"; shift ;;
      --no-counts)  counts=no ;;
      -h | --help)  usage; exit 0 ;;
      --*)          die "unrecognised argument '$1' (see --help)" ;;
      *)            seed="$1" ;;
    esac
    shift
  done

  command -v sbt >/dev/null || die "sbt is not on the PATH (nix develop provides it)"

  head1 "Configuration"
  read_env_file

  local db_name="${DB_NAME:-gathedge}"
  local db_user="${DB_USER:-gathedge}"
  local db_password="${DB_PASSWORD:-gathedge}"
  local db_port="${DB_PORT:-5432}"
  local db_schema="${DB_SCHEMA:-gathedge}"

  # An external database means no compose service to start and none to read the counts out of.
  local managed=yes
  if [ -n "${DEV_DB_URL:-}" ]; then
    managed=no
  fi
  local db_url="${DEV_DB_URL:-jdbc:postgresql://localhost:$db_port/$db_name}"

  # This is the override the whole script exists for: whatever .env said DB_URL was, the import runs on
  # this machine and reaches the database through the published port.
  export DB_URL="$db_url"
  export DB_USER="$db_user"
  export DB_PASSWORD="$db_password"
  export DB_SCHEMA="$db_schema"
  ok "$db_url (user $db_user, schema $db_schema)"

  seed="${seed:-$(default_seed)}"
  [ -f "$seed" ] || die "no seed file at '$seed' — build one with ./scripts/build-dictionary-seed.sh"
  ok "seed $seed ($(du -h "$seed" | cut -f1))"

  if [ "$managed" = yes ]; then
    start_database "$db_user" "$db_name"
  else
    warn "DEV_DB_URL is set, so docker is left alone — the database must already be reachable"
  fi

  run_import "$seed"

  if [ "$counts" = yes ] && [ "$managed" = yes ]; then
    show_counts "$db_user" "$db_name" "$db_schema"
  fi
}

main "$@"
