#!/usr/bin/env bash
#
# Tears down a worktree made by scripts/new-worktree.sh: removes the worktree and
# drops its `gathedge_wt<n>` Postgres schema. The branch is kept unless you ask.
#
#   ./scripts/rm-worktree.sh 1                      # by slot number
#   ./scripts/rm-worktree.sh ../wt-1-my-branch      # by directory
#   ./scripts/rm-worktree.sh --branch my-branch     # by branch name
#   ./scripts/rm-worktree.sh 1 --yes                # skip the confirmation
#   ./scripts/rm-worktree.sh 1 --yes --delete-branch # also `git branch -D`
#   ./scripts/rm-worktree.sh 1 --force              # `git worktree remove --force`
#
# It refuses any worktree whose directory is not named `wt-<n>-...`.
#
# psql on the PATH is used directly; otherwise the running `postgres` compose
# service is used (`docker compose exec`).

set -euo pipefail

# --- Output --------------------------------------------------------------------------

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
  sed -n '3,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# --- Postgres transport (same picker as new-worktree.sh) --------------------------

PG_MODE=""

pick_pg_mode() {
  if command -v psql >/dev/null 2>&1; then
    PG_MODE=host
    return
  fi
  command -v docker >/dev/null 2>&1 || die "no psql on the PATH and no docker either — cannot reach Postgres"
  docker compose ps --status running --services 2>/dev/null | grep -qx postgres \
    || die "no psql on the PATH and the 'postgres' compose service is not running"
  PG_MODE=docker
}

psql_do() {
  if [ "$PG_MODE" = host ]; then
    PGPASSWORD="$DB_PASSWORD" psql -h localhost -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" "$@"
  else
    docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" "$@"
  fi
}

env_value() { sed -n -E "s/^$2=(.*)/\\1/p" "$1" | head -1; }

# --- Resolve the target ----------------------------------------------------------

# Sets TARGET_PATH, TARGET_N, TARGET_BRANCH from `git worktree list --porcelain`.
resolve_target() {  # $1 = selector (number | path); or $BY_BRANCH set
  local sel=${1:-} path head branch base want_branch=${BY_BRANCH:-}
  TARGET_PATH="" TARGET_N="" TARGET_BRANCH=""

  while IFS= read -r line; do
    case "$line" in
      "worktree "*) path=${line#worktree }; head=""; branch="" ;;
      "HEAD "*)     head=${line#HEAD } ;;
      "branch "*)   branch=${line#branch refs/heads/} ;;
      "")
        base=$(basename "$path")
        if [ -n "$want_branch" ]; then
          [ "$branch" = "$want_branch" ] || { path=""; continue; }
        elif [ -n "$sel" ]; then
          case "$sel" in
            *[!0-9]*) [ "$base" = "$(basename "$sel")" ] || [ "$path" = "$sel" ] || { path=""; continue; } ;;
            *)        [ "$base" != "${base#wt-$sel-}" ] || { path=""; continue; } ;;
          esac
        else
          path=""; continue
        fi
        TARGET_PATH=$path
        TARGET_BRANCH=$branch
        return 0
        ;;
    esac
  done < <(git worktree list --porcelain; printf '\n')

  return 1
}

# --- Entry point -----------------------------------------------------------------

main() {
  local selector="" assume_yes=no delete_branch=no force=no
  BY_BRANCH=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --branch)        [ $# -gt 1 ] || die "--branch needs a name"; BY_BRANCH="$2"; shift ;;
      --yes | -y)      assume_yes=yes ;;
      --delete-branch) delete_branch=yes ;;
      --force)         force=yes ;;
      -h | --help)     usage; exit 0 ;;
      --*)             die "unrecognised argument '$1' (see --help)" ;;
      *)               [ -z "$selector" ] || die "only one target, got '$selector' and '$1'"; selector="$1" ;;
    esac
    shift
  done
  [ -n "$selector" ] || [ -n "$BY_BRANCH" ] || { usage; exit 1; }

  command -v git >/dev/null || die "git is not on the PATH"

  local main_dir
  main_dir=$(git worktree list --porcelain | awk '/^worktree /{print $2; exit}')
  [ -n "$main_dir" ] || die "could not locate the main worktree"
  cd "$main_dir"

  resolve_target "$selector" || die "no matching worktree (looked for '${BY_BRANCH:-$selector}')"

  local base n
  base=$(basename "$TARGET_PATH")
  case "$base" in
    wt-[0-9]*-*) n=$(printf '%s' "${base#wt-}" | sed 's/-.*//') ;;
    *)           die "refusing: '$TARGET_PATH' is not a wt-<n>- worktree" ;;
  esac
  TARGET_N=$n
  [ "$TARGET_PATH" != "$main_dir" ] || die "refusing to remove the main checkout"

  local schema="gathedge_wt$n" env_src=.env
  [ -f "$env_src" ] || env_src=.env.example
  DB_NAME=$(env_value "$env_src" DB_NAME);     DB_NAME=${DB_NAME:-gathedge}
  DB_USER=$(env_value "$env_src" DB_USER);     DB_USER=${DB_USER:-gathedge}
  DB_PASSWORD=$(env_value "$env_src" DB_PASSWORD); DB_PASSWORD=${DB_PASSWORD:-gathedge}
  DB_PORT=$(env_value "$env_src" DB_PORT);     DB_PORT=${DB_PORT:-5432}

  head1 "About to remove"
  say "  worktree   $TARGET_PATH"
  say "  branch     ${TARGET_BRANCH:-(detached)}$([ "$delete_branch" = yes ] && printf '   -> git branch -D')"
  say "  db schema  $schema   (DROP SCHEMA ... CASCADE, in $DB_NAME on localhost:$DB_PORT)"
  say ""

  if [ "$assume_yes" != yes ]; then
    printf '  type the slot number (%s) to confirm: ' "$n"
    local reply; read -r reply
    [ "$reply" = "$n" ] || die "cancelled"
  fi

  head1 "Worktree"
  local rm_args=(worktree remove "$TARGET_PATH")
  [ "$force" = yes ] && rm_args+=(--force)
  git "${rm_args[@]}"
  git worktree prune
  ok "removed $TARGET_PATH"

  head1 "Postgres schema"
  pick_pg_mode
  psql_do -v ON_ERROR_STOP=1 -c "DROP SCHEMA IF EXISTS \"$schema\" CASCADE" >/dev/null
  ok "dropped $schema"

  if [ "$delete_branch" = yes ]; then
    head1 "Branch"
    if [ -n "$TARGET_BRANCH" ]; then
      git branch -D "$TARGET_BRANCH"
      ok "deleted branch $TARGET_BRANCH"
    else
      warn "worktree was detached — no branch to delete"
    fi
  fi

  say ""
  ok "done"
}

main "$@"
