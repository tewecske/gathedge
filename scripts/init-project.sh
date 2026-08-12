#!/usr/bin/env bash
#
# Renames this skeleton into a new project, in place.
#
#   ./scripts/init-project.sh myapp "My App"
#
# Run it once, in a fresh copy of the repository, before writing any code. It renames the Scala
# package root, the sbt project and organization, the Docker image names, the database name/user/
# password/schema defaults, the Nix attributes, the tmux session and the wordmark — everything the
# skeleton calls "gathedge".
#
# The rename is a plain search-and-replace, and that is safe here for one specific reason: the
# repository contains the token `gathedge` in exactly one casing and nowhere as a substring of some
# other word. The check at the bottom of this script re-asserts that. If you have already renamed
# once, run it in a copy of *this* skeleton rather than of your project — see the guard below.

set -euo pipefail

readonly OLD_SLUG="gathedge"

usage() {
  cat <<'USAGE'
Usage: ./scripts/init-project.sh <slug> ["Display Name"] [options]

  <slug>            Lowercase identifier: [a-z][a-z0-9]*. Becomes the Scala package root, the sbt
                    project name, the Docker image names, and the database name and schema. No
                    dashes or underscores — it has to be a legal Scala package segment.
  "Display Name"    What a reader sees: the navbar wordmark and the browser tab. Defaults to <slug>.

Options:
  --org <group>     sbt organization. Default: com.example.<slug>
  --reinit-git      Delete .git and start a fresh repository with one commit. Destructive, and off
                    by default: it discards the skeleton's history, including the worked Todo and
                    Group examples that were removed from it.
  --force           Proceed even though the working tree has uncommitted changes.
  -h, --help        This message.

Example:
  ./scripts/init-project.sh invoices "Invoice Tracker" --org com.acme
USAGE
}

die() {
  echo "error: $*" >&2
  exit 1
}

slug=""
display_name=""
org=""
reinit_git=false
force=false

while [ $# -gt 0 ]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --org)
      [ $# -ge 2 ] || die "--org needs a value"
      org="$2"
      shift 2
      ;;
    --reinit-git)
      reinit_git=true
      shift
      ;;
    --force)
      force=true
      shift
      ;;
    -*)
      die "unknown option: $1"
      ;;
    *)
      if [ -z "$slug" ]; then
        slug="$1"
      elif [ -z "$display_name" ]; then
        display_name="$1"
      else
        die "unexpected argument: $1"
      fi
      shift
      ;;
  esac
done

[ -n "$slug" ] || {
  usage
  exit 1
}

# The slug reaches a Scala `package` declaration, a Docker image name, and a Postgres database name
# and schema. The intersection of what all four accept is narrower than any one of them — this also
# keeps the schema a legal unquoted Postgres identifier, which is what `search_path` is set to.
[[ "$slug" =~ ^[a-z][a-z0-9]*$ ]] || die "slug must match [a-z][a-z0-9]* — got '$slug'"
[ "$slug" != "$OLD_SLUG" ] || die "slug is already '$OLD_SLUG'; pick a different one"

display_name="${display_name:-$slug}"
org="${org:-com.example.$slug}"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

[ -f build.sbt ] && [ -d modules ] || die "this does not look like the skeleton's root: $root"

# Renaming a second time would leave the previous name behind in half the files, because this only
# ever replaces `gathedge`. Better to refuse than to half-finish.
grep -rqI --exclude-dir={.git,node_modules,target,.bloop,.metals,dist,logs} "$OLD_SLUG" . ||
  die "found no '$OLD_SLUG' to rename — has this project been renamed already?"

if [ -d .git ] && [ "$force" != true ]; then
  git diff --quiet && git diff --cached --quiet ||
    die "working tree has uncommitted changes; commit them or pass --force"
fi

echo "Renaming '$OLD_SLUG' -> '$slug'"
echo "  display name: $display_name"
echo "  organization: $org"
echo

# --- 1. The package directories --------------------------------------------------------------
# The package root is a path as well as a declaration, so the directories move before the text is
# rewritten. `git mv` where the repository is present, plain `mv` otherwise.
for source_root in \
  modules/shared/shared/src/main/scala \
  modules/shared/shared/src/test/scala \
  modules/backend/src/main/scala \
  modules/backend/src/test/scala \
  modules/frontend/src/main/scala \
  modules/frontend/src/test/scala; do
  if [ -d "$source_root/$OLD_SLUG" ]; then
    if [ -d .git ]; then
      git mv "$source_root/$OLD_SLUG" "$source_root/$slug"
    else
      mv "$source_root/$OLD_SLUG" "$source_root/$slug"
    fi
    echo "  moved $source_root/$OLD_SLUG -> $source_root/$slug"
  fi
done

# --- 2. Every occurrence in text -------------------------------------------------------------
# Build output, dependencies and test artefacts are excluded: they hold generated copies of the old
# package path and are rebuilt anyway. `-I` skips binary files. One list, used again by the check in
# step 5 — when the two drifted apart, that check reported a stale Playwright artefact as a file the
# rename had missed.
readonly SKIP_DIRS='{.git,node_modules,target,.bloop,.metals,dist,logs,test-results,playwright-report}'

find_remaining() {
  eval "grep -rlI --exclude-dir=$SKIP_DIRS --exclude='*.log' '$OLD_SLUG' ." 2>/dev/null
}

mapfile -t files < <(find_remaining | sort)

[ ${#files[@]} -gt 0 ] || die "no files to rewrite"

for file in "${files[@]}"; do
  # `|` as the delimiter: a slug cannot contain one, and neither can it contain a `/` to escape.
  sed -i "s|$OLD_SLUG|$slug|g" "$file"
done
echo "  rewrote ${#files[@]} files"

# --- 3. The parts that are not just the slug ---------------------------------------------------
# `organization` defaults to com.example.<slug> and is already correct after step 2; this only has
# to run when --org named something else.
sed -i "s|^ThisBuild / organization := .*|ThisBuild / organization := \"$org\"|" build.sbt

# The wordmark and the browser tab. `Branding.appName` is the one place the display name lives in
# Scala; index.html carries its own copy because it is parsed before the bundle loads.
branding="modules/shared/shared/src/main/scala/$slug/shared/Branding.scala"
[ -f "$branding" ] || die "expected $branding to exist"
sed -i "s|^  val appName: String = .*|  val appName: String = \"$display_name\"|" "$branding"
sed -i "s|<title>[^<]*</title>|<title>$display_name</title>|" web/index.html
echo "  set the display name to '$display_name'"

# --- 4. Local configuration --------------------------------------------------------------------
if [ ! -f .env ]; then
  cp .env.example .env
  echo "  created .env from .env.example"
fi

# --- 5. Verify ---------------------------------------------------------------------------------
# The whole approach rests on the old name being gone afterwards. Say so rather than assume it.
if remaining=$(find_remaining); then
  echo
  echo "warning: '$OLD_SLUG' still appears in:" >&2
  echo "$remaining" >&2
  echo "Rewrite those by hand — the rename is otherwise complete." >&2
fi

# --- 6. Git ------------------------------------------------------------------------------------
if [ "$reinit_git" = true ]; then
  rm -rf .git
  git init -q
  git add -A
  git commit -qm "Initial commit from the $OLD_SLUG skeleton"
  echo "  reinitialised git with a single commit"
fi

cat <<NEXT

Done. Next:

  docker compose up -d postgres    # the dev database
  npm install && npm run dev       # backend :8080, Scala.js watch, vite :5173
  sbt test                         # should be green before you change anything

Then open modules/frontend/src/main/scala/$slug/frontend/pages/HomePage.scala — the placeholder
landing page — and see docs/ADDING-A-FEATURE.md for where a new feature's pieces go.
NEXT
