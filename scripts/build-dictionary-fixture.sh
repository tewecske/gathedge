#!/usr/bin/env bash
# Builds the dictionary fixture PostgresIntegrationSpec restores into each test's own schema.
#
#   ./scripts/build-dictionary-fixture.sh
#
# One-time, and re-run only when `data/dictionary/seed.tsv` changes or a migration changes the
# shape of one of the three dictionary tables. The output is committed:
#
#   modules/backend/src/test/resources/dictionary-fixture.tsv.gz
#
# What it does, and why in this order:
#
#   1. Migrates a scratch schema (`gathedge_fixture`) to the latest version.
#   2. Runs `DictionaryImport --seed` into it. That is the expensive half — deduping homographs,
#      pivoting German-Hungarian pairs through English, deriving form-to-form edges — and it is the
#      whole reason this file exists rather than the test reading `seed.tsv` directly. The
#      derivation runs once, here, not once per test run.
#   3. Exports `words`, `word_translations` and `word_forms` as Postgres COPY text, one section per
#      table, each headed by its own column list.
#   4. Drops the scratch schema.
#
# **Data only, never structure.** The schema comes from Flyway at restore time, so a migration that
# adds a column needs no new fixture: COPY names the columns the fixture holds and the new one takes
# its default. A migration that renames or drops one fails loudly on the next run, which is correct
# — the fixture is then genuinely stale.
#
# Needs the `postgres` compose service up (or psql/pg_dump on the PATH), and `data/dictionary/seed.tsv`.
set -euo pipefail

cd "$(dirname "$0")/.."

FIXTURE_SCHEMA=gathedge_fixture
OUTPUT=modules/backend/src/test/resources/dictionary-fixture.tsv.gz
SEED=data/dictionary/seed.tsv
TABLES=(words word_translations word_forms)

say()  { printf '\033[36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[32m✓ %s\033[0m\n' "$*"; }
die()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# Value of KEY from an env file (first active KEY=... line), else empty. Same helper new-worktree.sh uses.
env_value() { sed -n -E "s/^$2=(.*)/\\1/p" "$1" | head -1; }

env_src=.env
[ -f "$env_src" ] || env_src=.env.example
[ -f "$env_src" ] || die "neither .env nor .env.example — copy .env.example to .env first"

DB_NAME=$(env_value "$env_src" DB_NAME);         DB_NAME=${DB_NAME:-gathedge}
DB_USER=$(env_value "$env_src" DB_USER);         DB_USER=${DB_USER:-gathedge}
DB_PASSWORD=$(env_value "$env_src" DB_PASSWORD); DB_PASSWORD=${DB_PASSWORD:-gathedge}
DB_PORT=$(env_value "$env_src" DB_PORT);         DB_PORT=${DB_PORT:-5432}

[ -f "$SEED" ] || die "$SEED is missing — see data/dictionary/README.md"

# Host psql when there is one, the compose container otherwise. Identical to new-worktree.sh's rule.
if command -v psql >/dev/null 2>&1; then
  PG_MODE=host
  say "postgres client: host psql"
else
  command -v docker >/dev/null 2>&1 || die "no psql on the PATH and no docker either"
  docker compose ps --status running --services 2>/dev/null | grep -qx postgres \
    || die "no psql on the PATH and the 'postgres' compose service is not running (docker compose up -d postgres)"
  PG_MODE=docker
  say "postgres client: docker compose exec postgres psql"
fi

psql_do() {
  if [ "$PG_MODE" = host ]; then
    PGPASSWORD="$DB_PASSWORD" psql -h localhost -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" "$@"
  else
    docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" "$@"
  fi
}

drop_schema() { psql_do -v ON_ERROR_STOP=1 -q -c "DROP SCHEMA IF EXISTS \"$FIXTURE_SCHEMA\" CASCADE"; }

trap drop_schema EXIT

say "Recreating schema $FIXTURE_SCHEMA"
drop_schema
psql_do -v ON_ERROR_STOP=1 -q -c "CREATE SCHEMA \"$FIXTURE_SCHEMA\""

# `run` never sees `.env` — `build.sbt` wires it into `reStart` only — so the overrides are exported here.
export DB_URL="jdbc:postgresql://localhost:$DB_PORT/$DB_NAME"
export DB_USER DB_PASSWORD
export DB_SCHEMA="$FIXTURE_SCHEMA"

say "Migrating $FIXTURE_SCHEMA"
sbt -batch "backend/runMain gathedge.backend.tools.Migrate" >/dev/null \
  || die "migration failed"

say "Importing $SEED (this is the slow part)"
sbt -batch "backend/runMain gathedge.backend.tools.DictionaryImport --seed" >/dev/null \
  || die "dictionary import failed"

say "Exporting ${TABLES[*]}"
mkdir -p "$(dirname "$OUTPUT")"
{
  for table in "${TABLES[@]}"; do
    # The column list travels with the data, so the restore names exactly the columns the fixture
    # holds rather than whatever the current migrations happen to define.
    columns=$(psql_do -tAc "
      SELECT string_agg(quote_ident(column_name), ',' ORDER BY ordinal_position)
      FROM information_schema.columns
      WHERE table_schema = '$FIXTURE_SCHEMA' AND table_name = '$table'" | tr -d '[:space:]')
    [ -n "$columns" ] || die "table $table has no columns in $FIXTURE_SCHEMA — did the import run?"

    printf '#table\t%s\t%s\n' "$table" "$columns"
    psql_do -v ON_ERROR_STOP=1 -q \
      -c "\\copy (SELECT $columns FROM \"$FIXTURE_SCHEMA\".\"$table\") TO STDOUT"
  done
} | gzip -9 > "$OUTPUT"

ok "wrote $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
say "Commit it: git add $OUTPUT"
