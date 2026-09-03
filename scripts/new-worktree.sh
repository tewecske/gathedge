#!/usr/bin/env bash
#
# Creates a parallel dev git worktree that never collides with the main checkout
# or another worktree: its own dev ports and its own Postgres schema, cloned from
# the shared `gathedge` schema so the dictionary and all data come with it.
#
#   ./scripts/new-worktree.sh my-branch                  # base off master
#   ./scripts/new-worktree.sh my-branch --base tag-editor # base off another ref
#   ./scripts/new-worktree.sh my-branch --force          # drop a leftover schema first
#
# The worktree is `../wt-<n>-<branch>`, where <n> is the lowest free slot among
# the existing `wt-<n>-*` worktrees. <n> alone decides the port offset, so two
# worktrees can never clash:
#
#   SERVER_PORT = 8080 + n*10      VITE_PORT = 5173 + n*10
#
# (each is then bumped past anything already listening). The worktree `.env` is
# copied from the main checkout's `.env` with SERVER_PORT, VITE_PORT,
# PUBLIC_BASE_URL, DB_SCHEMA (= gathedge_wt<n>) and DB_URL (pinned to localhost)
# overridden. `.env` is git-ignored and per-worktree.
#
# The Postgres schema is cloned with `pg_dump --schema=gathedge | rename | psql`.
# psql/pg_dump on the PATH are used directly; otherwise the running `postgres`
# compose service is used (`docker compose exec`). The rename is a word-boundary
# sed on the token `gathedge`; no dictionary word is that token.
#
# All progress goes to stderr. The one stdout line is the absolute worktree path,
# so `WT=$(./scripts/new-worktree.sh my-branch)` works.
#
# Tear a worktree down again with scripts/rm-worktree.sh.

set -euo pipefail

readonly SRC_SCHEMA_DEFAULT="gathedge"

# --- Output (stderr; stdout is reserved for the worktree path) ------------------

if [ -t 2 ]; then
  readonly C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m' C_RED=$'\033[31m' C_BOLD=$'\033[1m' C_OFF=$'\033[0m'
else
  readonly C_GREEN='' C_YELLOW='' C_RED='' C_BOLD='' C_OFF=''
fi

say()   { printf '%s\n' "$*" >&2; }
head1() { printf '\n%s%s%s\n' "$C_BOLD" "$*" "$C_OFF" >&2; }
ok()    { printf '  %sok%s    %s\n' "$C_GREEN" "$C_OFF" "$*" >&2; }
warn()  { printf '  %swarn%s  %s\n' "$C_YELLOW" "$C_OFF" "$*" >&2; }
die()   { printf '%serror%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

usage() {
  sed -n '3,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# --- Postgres transport -------------------------------------------------------------

# Chosen once by pick_pg_mode(): "host" (psql/pg_dump on PATH) or "docker"
# (docker compose exec -T postgres ...).
PG_MODE=""

pick_pg_mode() {
  if command -v psql >/dev/null 2>&1 && command -v pg_dump >/dev/null 2>&1; then
    PG_MODE=host
    ok "postgres client: host psql/pg_dump"
    return
  fi
  command -v docker >/dev/null 2>&1 \
    || die "no psql/pg_dump on the PATH and no docker either — cannot reach Postgres"
  if ! docker compose ps --status running --services 2>/dev/null | grep -qx postgres; then
    die "no psql/pg_dump on the PATH and the 'postgres' compose service is not running (docker compose up -d postgres)"
  fi
  PG_MODE=docker
  ok "postgres client: docker compose exec -T postgres"
}

psql_do() {  # SQL via -c/-tAc args or stdin; talks to the shared database
  if [ "$PG_MODE" = host ]; then
    PGPASSWORD="$DB_PASSWORD" psql -h localhost -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" "$@"
  else
    docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" "$@"
  fi
}

pg_dump_schema() {  # plain-SQL dump of $SRC_SCHEMA, structure + data, to stdout
  if [ "$PG_MODE" = host ]; then
    PGPASSWORD="$DB_PASSWORD" pg_dump -h localhost -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
      --schema="$SRC_SCHEMA" --no-owner --no-privileges
  else
    docker compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" \
      --schema="$SRC_SCHEMA" --no-owner --no-privileges
  fi
}

# --- .env helpers ---------------------------------------------------------------

# Value of KEY from an env file (first active KEY=... line), else empty.
env_value() { sed -n -E "s/^$2=(.*)/\\1/p" "$1" | head -1; }

# set_key FILE KEY VALUE — replace the active KEY= line in place, else append one.
set_key() {
  local f=$1 k=$2 v=$3 esc
  if grep -qE "^${k}=" "$f"; then
    esc=${v//\\/\\\\}; esc=${esc//&/\\&}; esc=${esc//\//\\/}
    sed -i -E "s/^${k}=.*/${k}=${esc}/" "$f"
  else
    printf '%s=%s\n' "$k" "$v" >> "$f"
  fi
}

# --- Steps -------------------------------------------------------------------------

port_in_use() {
  local p=$1
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | grep -q ":${p} "
  elif command -v nc >/dev/null 2>&1; then
    nc -z localhost "$p" >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/localhost/$p") >/dev/null 2>&1
  fi
}

free_port() {  # echo the first free port at or after $1
  local p=$1
  while port_in_use "$p"; do p=$((p + 1)); done
  printf '%s' "$p"
}

# Lowest integer >= 1 not already a wt-<n>- worktree.
pick_slot() {
  local used p b n
  used=$(git worktree list --porcelain \
    | awk '/^worktree /{print $2}' \
    | while read -r p; do
        b=$(basename "$p")
        case "$b" in
          wt-[0-9]*-*) printf '%s\n' "${b#wt-}" | sed 's/-.*//' ;;
        esac
      done)
  n=1
  while printf '%s\n' "$used" | grep -qx "$n"; do n=$((n + 1)); done
  printf '%s' "$n"
}

clone_schema() {  # $1 = target schema
  local target=$1 exists
  head1 "Postgres schema"
  pick_pg_mode

  [ "$SRC_SCHEMA" != public ] \
    || die "source schema is 'public' — the rename would corrupt the dump; set DB_SCHEMA to a named schema"

  exists=$(psql_do -tAc \
    "SELECT 1 FROM information_schema.schemata WHERE schema_name = '$target'" | tr -d '[:space:]')
  if [ "$exists" = 1 ]; then
    if [ "$FORCE" = yes ]; then
      warn "schema $target exists — dropping it (--force)"
      psql_do -v ON_ERROR_STOP=1 -c "DROP SCHEMA \"$target\" CASCADE"
    else
      die "schema $target already exists (pass --force to drop and recreate it)"
    fi
  fi

  say "  cloning $SRC_SCHEMA -> $target"
  pg_dump_schema \
    | sed -E "s/\\b${SRC_SCHEMA}\\b/${target}/g" \
    | psql_do -v ON_ERROR_STOP=1 >/dev/null
  ok "schema $target cloned (structure + data)"
}

# --- Entry point -----------------------------------------------------------------

main() {
  local branch="" base="master"
  FORCE=no
  while [ $# -gt 0 ]; do
    case "$1" in
      --base)      [ $# -gt 1 ] || die "--base needs a ref"; base="$2"; shift ;;
      --force)     FORCE=yes ;;
      -h | --help) usage; exit 0 ;;
      --*)         die "unrecognised argument '$1' (see --help)" ;;
      *)           [ -z "$branch" ] || die "only one branch name, got '$branch' and '$1'"; branch="$1" ;;
    esac
    shift
  done
  [ -n "$branch" ] || { usage; exit 1; }

  command -v git >/dev/null || die "git is not on the PATH"

  # The main checkout is the first entry of `git worktree list`.
  local main_dir
  main_dir=$(git worktree list --porcelain | awk '/^worktree /{print $2; exit}')
  [ -n "$main_dir" ] || die "could not locate the main worktree"
  cd "$main_dir"

  head1 "Configuration"
  ok "main checkout: $main_dir"

  local env_src=""
  if [ -f .env ]; then
    env_src=.env
  elif [ -f .env.example ]; then
    env_src=.env.example
    warn "no .env — starting the worktree .env from .env.example"
  else
    die "neither .env nor .env.example in $main_dir"
  fi

  DB_NAME=$(env_value "$env_src" DB_NAME);     DB_NAME=${DB_NAME:-gathedge}
  DB_USER=$(env_value "$env_src" DB_USER);     DB_USER=${DB_USER:-gathedge}
  DB_PASSWORD=$(env_value "$env_src" DB_PASSWORD); DB_PASSWORD=${DB_PASSWORD:-gathedge}
  DB_PORT=$(env_value "$env_src" DB_PORT);     DB_PORT=${DB_PORT:-5432}
  SRC_SCHEMA=$(env_value "$env_src" DB_SCHEMA); SRC_SCHEMA=${SRC_SCHEMA:-$SRC_SCHEMA_DEFAULT}

  local n slug wt_dir server_port vite_port schema
  n=$(pick_slot)
  slug=$(printf '%s' "$branch" | sed 's#/#-#g')
  wt_dir="$main_dir/../wt-$n-$slug"
  schema="gathedge_wt$n"

  server_port=$(free_port $((8080 + n * 10)))
  vite_port=$(free_port $((5173 + n * 10)))

  ok "slot n=$n  ports $server_port / $vite_port  schema $schema"

  head1 "Worktree"
  git worktree add "$wt_dir" -b "$branch" "$base" >&2
  wt_dir=$(cd "$wt_dir" && pwd)
  ok "added $wt_dir on branch $branch (base $base)"

  head1 "Worktree .env"
  cp "$env_src" "$wt_dir/.env"
  set_key "$wt_dir/.env" SERVER_PORT     "$server_port"
  set_key "$wt_dir/.env" VITE_PORT       "$vite_port"
  set_key "$wt_dir/.env" PUBLIC_BASE_URL "http://localhost:$vite_port"
  set_key "$wt_dir/.env" DB_SCHEMA       "$schema"
  set_key "$wt_dir/.env" DB_URL          "jdbc:postgresql://localhost:$DB_PORT/$DB_NAME"
  ok "wrote $wt_dir/.env"

  clone_schema "$schema"

  head1 "Ready"
  say "  worktree   $wt_dir"
  say "  branch     $branch  (base $base)"
  say "  backend    http://localhost:$server_port"
  say "  frontend   http://localhost:$vite_port   (PUBLIC_BASE_URL)"
  say "  db schema  $schema   in $DB_NAME on localhost:$DB_PORT"
  say ""
  say "  cd $wt_dir && npm run dev"
  say ""
  say "  tear down:  scripts/rm-worktree.sh $n"

  printf '%s\n' "$wt_dir"
}

main "$@"
