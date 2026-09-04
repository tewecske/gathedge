#!/usr/bin/env bash
#
# Prepares a revision of this repository for release, and checks that one is prepared.
#
#   ./scripts/release.sh              # prepare: what changed, hashes, build, smoke-test, next steps
#   ./scripts/release.sh --check      # fast: HEAD, no build  (this is the pre-push gate)
#   ./scripts/release.sh --check REV  # fast: one commit      (what scripts/githooks/pre-push calls)
#   ./scripts/release.sh --mark [REV] # record REV as released, after the server is actually up
#   ./scripts/release.sh --install-hook
#
# WHY THIS EXISTS
#
# The NixOS deployment (see nix/README.md) builds this repository straight from its git remote, at
# whatever revision the host's flake.lock names. The push *is* the artifact: there is no build
# output to test in between, and a defect in the Nix half of the repo is discovered by a long build
# on the server that ends in a systemd restart loop. Two such defects have already shipped:
#
#   * the i18n catalogs were missing from the backend jar, because flake.nix's `scalaSrc` filter did
#     not cover a directory build.sbt reads;
#   * a fixed-output hash (`depsSha256`, `npmDepsHash`) went stale, which nothing notices until the
#     build that needs it runs.
#
# Neither is visible to `sbt test`, and both are knowable from the repository alone. So: `--check`
# answers them in about three seconds and hangs off pre-push, while the default mode does the slow,
# conclusive half — build both packages and assert the things that build green and die at runtime.
#
# WHAT IT DELIBERATELY DOES NOT DO
#
# It touches this repository only. Bumping the flake input in your NixOS configuration and running
# `nixos-rebuild switch` on the server are printed for you to run, not performed: a script holding
# credentials for two repositories and a host is a much bigger thing to trust than the two commands
# it would save.

set -euo pipefail

# --- Paths and constants -------------------------------------------------------------------------

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly STAMP_FILE="nix/inputs.sha256"
readonly HOOKS_DIR="scripts/githooks"

# The files that determine each fixed-output hash. A change to one of these is the only thing that
# can invalidate its hash — sbt sources cannot, since compiling different code resolves the same
# dependency set.
readonly DEPS_INPUTS=(build.sbt project/build.properties project/plugins.sbt)
readonly NPM_INPUTS=(web/package-lock.json)

# Everything the flake's source filters can reach. Used for the untracked-file warning: a flake
# only sees git-tracked files, so an untracked file under one of these builds locally and vanishes
# on the server.
readonly BUILD_ROOTS=(build.sbt flake.nix flake.lock project modules web nix docker)

# Files the build reads and would fail without.
readonly REQUIRED_FILES=(
  flake.nix
  flake.lock
  nix/scala.nix
  nix/web.nix
  nix/module.nix
  build.sbt
  project/build.properties
  project/plugins.sbt
  web/package-lock.json
  docker/logback.xml
  web/public/locales/messages.en.json
  web/public/locales/messages.hu.json
)

# --- Output --------------------------------------------------------------------------------------

if [ -t 1 ]; then
  readonly C_RED=$'\033[31m' C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m' C_BOLD=$'\033[1m' C_OFF=$'\033[0m'
else
  readonly C_RED='' C_GREEN='' C_YELLOW='' C_BOLD='' C_OFF=''
fi

failures=0

say()  { printf '%s\n' "$*"; }
head1() { printf '\n%s%s%s\n' "$C_BOLD" "$*" "$C_OFF"; }
ok()   { printf '  %sok%s    %s\n' "$C_GREEN" "$C_OFF" "$*"; }
warn() { printf '  %swarn%s  %s\n' "$C_YELLOW" "$C_OFF" "$*"; }
bad()  { printf '  %sFAIL%s  %s\n' "$C_RED" "$C_OFF" "$*"; failures=$((failures + 1)); }
die()  { printf '%serror%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

usage() {
  sed -n '3,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# --- Small helpers -------------------------------------------------------------------------------

# Digest of a list of files, read from the working tree. Composed the same way as digest_rev so the
# two are comparable: a digest per file, concatenated, hashed again.
digest_worktree() {
  local file sums=""
  for file in "$@"; do
    [ -f "$REPO_ROOT/$file" ] || die "missing $file"
    sums+="$(sha256sum "$REPO_ROOT/$file" | cut -d' ' -f1)"
  done
  printf '%s' "$sums" | sha256sum | cut -d' ' -f1
}

# The same digest, read out of a commit rather than the working tree.
digest_rev() {
  local rev="$1" file sums=""
  shift
  for file in "$@"; do
    # A file absent at that revision digests as empty rather than aborting: check 1 already
    # reports missing build inputs, and it says so far more clearly than a git error would.
    sums+="$({ git show "$rev:$file" 2>/dev/null || true; } | sha256sum | cut -d' ' -f1)"
  done
  printf '%s' "$sums" | sha256sum | cut -d' ' -f1
}

stamp_value() { # stamp_value <key> [file]
  local key="$1" file="${2:-$REPO_ROOT/$STAMP_FILE}"
  [ -f "$file" ] || return 1
  awk -v k="$key" '$1 == k { print $2 }' "$file"
}

write_stamp() {
  cat >"$REPO_ROOT/$STAMP_FILE" <<EOF
# Written by scripts/release.sh. Each line records the digest of the files that determine one
# fixed-output hash, as they stood when that hash was last computed.
#
# This is the only way to notice a stale hash without building: Nix evaluation accepts a wrong
# fixed-output hash quite happily and fails much later, in the build that needs it.
depsSha256 $(digest_worktree "${DEPS_INPUTS[@]}")
npmDepsHash $(digest_worktree "${NPM_INPUTS[@]}")
EOF
}

nix_system() {
  nix eval --impure --raw --expr builtins.currentSystem
}

# Rewrite nix/scala.nix's depsSha256. Pass a real "sha256-..." string, or the word "fake" for the
# lib.fakeSha256 sentinel. Matches the current value whether quoted or the bare sentinel.
set_deps_hash() {
  local value="$1" repl
  if [ "$value" = fake ]; then repl='lib.fakeSha256'; else repl="\"$value\""; fi
  sed -i -E "s|depsSha256 = [^;]+;|depsSha256 = $repl;|" "$REPO_ROOT/nix/scala.nix"
}

# The `got: sha256-...` value Nix prints on a fixed-output hash mismatch, or empty.
harvested_got() {
  grep -oE 'got:[[:space:]]+sha256-[A-Za-z0-9+/=]+' "$1" | head -1 \
    | grep -oE 'sha256-[A-Za-z0-9+/=]+' || true
}

# The release gate's two-package build. Args: <logbase> <system>. Output paths land in
# "<logbase>.out", the log in "<logbase>".
build_packages() {
  local log="$1" system="$2"
  nix build "$REPO_ROOT#packages.$system.backend" "$REPO_ROOT#packages.$system.web" \
    --no-link --print-out-paths >"$log.out" 2>"$log"
}

# The newest release marker, or empty if none exists yet.
last_release_tag() {
  git -C "$REPO_ROOT" tag --list 'released/*' --sort=-creatordate | head -1
}

# --- Check mode ----------------------------------------------------------------------------------
#
# Everything here reads the *commit*, not the working tree, so it answers the question a pre-push
# hook is actually asking. Ordered cheapest first.

check_mode() {
  local rev="${1:-HEAD}"
  local sha
  sha="$(git -C "$REPO_ROOT" rev-parse --verify "$rev^{commit}")" || die "not a commit: $rev"

  head1 "Release check of $(git -C "$REPO_ROOT" rev-parse --short "$sha") ($(git -C "$REPO_ROOT" log -1 --format=%s "$sha"))"

  # 1. Everything the build reads is tracked at that revision. An untracked file is invisible to a
  #    flake however well it works under sbt.
  local tracked missing=()
  tracked="$(git -C "$REPO_ROOT" ls-tree -r --name-only "$sha")"
  local file
  for file in "${REQUIRED_FILES[@]}"; do
    grep -qxF "$file" <<<"$tracked" || missing+=("$file")
  done
  if [ ${#missing[@]} -eq 0 ]; then
    ok "all ${#REQUIRED_FILES[@]} build inputs are tracked"
  else
    bad "not tracked at this revision: ${missing[*]} (git add them)"
  fi

  # 2. No placeholder hash survived into the commit. Matched on the assignment rather than on the
  #    word, because nix/scala.nix's comment explains the lib.fakeSha256 trick.
  local placeholders
  placeholders="$(git -C "$REPO_ROOT" grep -l -E \
    '(depsSha256|npmDepsHash)[[:space:]]*=[[:space:]]*(lib\.fake|"sha256-A{16})' \
    "$sha" -- 'nix/*.nix' 2>/dev/null || true)"
  if [ -z "$placeholders" ]; then
    ok "no placeholder hashes in nix/"
  else
    bad "placeholder hash still in ${placeholders//$sha:/} (run ./scripts/release.sh)"
  fi

  # 3. Each fixed-output hash was computed from the inputs this revision actually has.
  local stamp
  if ! stamp="$(git -C "$REPO_ROOT" show "$sha:$STAMP_FILE" 2>/dev/null)"; then
    bad "$STAMP_FILE is missing at this revision (run ./scripts/release.sh, then commit it)"
  else
    local tmp
    tmp="$(mktemp)"
    printf '%s\n' "$stamp" >"$tmp"
    local key inputs digest recorded
    for key in depsSha256 npmDepsHash; do
      if [ "$key" = depsSha256 ]; then inputs=("${DEPS_INPUTS[@]}"); else inputs=("${NPM_INPUTS[@]}"); fi
      digest="$(digest_rev "$sha" "${inputs[@]}")"
      recorded="$(stamp_value "$key" "$tmp" || true)"
      if [ "$digest" = "$recorded" ]; then
        ok "$key matches its inputs (${inputs[*]})"
      else
        bad "$key is stale: ${inputs[*]} changed since it was computed. Run ./scripts/release.sh"
      fi
    done
    rm -f "$tmp"
  fi

  # 4. Every directory build.sbt reads as a resource is inside a source root the flake keeps. This
  #    is the catalog bug stated as a rule rather than as one remembered path.
  check_source_roots "$sha"

  # 5. Every Nix file parses. This exists for nix/module.nix, which nothing else here reaches:
  #    the packages do not import it, so a broken NixOS module evaluates fine from this side and
  #    breaks the *host's* rebuild instead. A parse is the half of that which is cheap — an
  #    undefined variable or a bad option in the module still only surfaces on the server.
  local parse_dir parse_ok=yes nix_files
  parse_dir="$(mktemp -d)"
  nix_files="$(git -C "$REPO_ROOT" ls-tree -r --name-only "$sha" -- flake.nix nix)"
  while IFS= read -r file; do
    [[ "$file" == *.nix ]] || continue
    mkdir -p "$parse_dir/$(dirname "$file")"
    git -C "$REPO_ROOT" show "$sha:$file" >"$parse_dir/$file"
    if ! nix-instantiate --parse "$parse_dir/$file" >/dev/null 2>"$parse_dir/err"; then
      bad "$file does not parse:"
      sed 's/^/        /' "$parse_dir/err" >&2
      parse_ok=no
    fi
  done <<<"$nix_files"
  [ "$parse_ok" = yes ] && ok "every .nix file parses (including the NixOS module)"
  rm -rf "$parse_dir"

  # 6. The revision evaluates — with its own lock, which is what --no-update-lock-file asserts.
  #    Catches a Nix-level error anywhere in flake.nix or nix/*.nix, and a lock that no longer
  #    satisfies flake.nix's inputs. About 2.5s warm; it is the whole cost of this mode.
  local system flake_ref target
  system="$(nix_system)"
  flake_ref="git+file://$REPO_ROOT?rev=$sha"
  for target in backend web; do
    if nix eval --no-update-lock-file --raw \
      "$flake_ref#packages.$system.$target.drvPath" >/dev/null 2>"$REPO_ROOT/.release-eval.log"; then
      ok "packages.$system.$target evaluates"
    else
      bad "packages.$system.$target does not evaluate:"
      sed 's/^/        /' "$REPO_ROOT/.release-eval.log" >&2
    fi
    rm -f "$REPO_ROOT/.release-eval.log"
  done

  # 7. Advisory: what was built locally is not what is being pushed.
  if ! git -C "$REPO_ROOT" diff --quiet "$sha" -- "${BUILD_ROOTS[@]}" 2>/dev/null; then
    warn "the working tree differs from this commit under ${BUILD_ROOTS[*]} — what you built locally is not what the server will build"
  fi
  local untracked
  untracked="$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- "${BUILD_ROOTS[@]}")"
  if [ -n "$untracked" ]; then
    warn "untracked files under the build roots (a flake cannot see these):"
    sed 's/^/          /' <<<"$untracked"
  fi

  if [ "$failures" -gt 0 ]; then
    say ""
    die "$failures check(s) failed. Fix, or push with --no-verify if you know better."
  fi
  say ""
  say "Ready to release."
}

# Parses `Compile / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "a" / "b"`
# out of build.sbt and asserts each path is covered by a root in flake.nix's scalaSrc list.
check_source_roots() {
  local rev="$1"
  local build_sbt flake_nix
  build_sbt="$(git -C "$REPO_ROOT" show "$rev:build.sbt")"
  flake_nix="$(git -C "$REPO_ROOT" show "$rev:flake.nix")"

  local roots
  roots="$(sed -n '/scalaSrc = mkSrc/,/\];/p' <<<"$flake_nix" | grep -oE '"[^"]+"' | tr -d '"')"
  [ -n "$roots" ] || { bad "could not find scalaSrc's root list in flake.nix"; return; }

  local line path root covered
  while IFS= read -r line; do
    # "web" / "public" / "locales"  ->  web/public/locales
    path="$(grep -oE '"[^"]+"' <<<"$line" | tr -d '"' | paste -sd/ -)"
    [ -n "$path" ] || continue
    covered=no
    while IFS= read -r root; do
      case "$path" in
        "$root" | "$root"/*) covered=yes ;;
      esac
    done <<<"$roots"
    if [ "$covered" = yes ]; then
      ok "build.sbt reads $path, and scalaSrc keeps it"
    else
      bad "build.sbt reads $path, which flake.nix's scalaSrc filters out — the build would silently omit it"
    fi
  done < <(grep -E 'unmanagedResourceDirectories[[:space:]]*\+?=' <<<"$build_sbt" | grep 'baseDirectory')
}

# --- Prepare mode --------------------------------------------------------------------------------

prepare_mode() {
  local force="$1" run_tests="$2"

  command -v nix >/dev/null || die "nix is not on PATH"

  # What changed since the last release, which is what decides the rest.
  local since range
  since="$(last_release_tag)"
  if [ -n "$since" ]; then
    range="$since..HEAD"
  else
    range="HEAD~1..HEAD"
  fi

  head1 "Changes in $range${since:+ (last release: $since)}"
  [ -n "$since" ] || warn "no released/* tag yet — using $range. Run --mark after a successful deploy."
  local commits changed
  commits="$(git -C "$REPO_ROOT" rev-list --count "$range" 2>/dev/null || echo 0)"
  changed="$(git -C "$REPO_ROOT" diff --name-only "$range" 2>/dev/null || true)"
  say "  $commits commit(s), $(wc -l <<<"$changed") file(s)"

  local deps_drift=no npm_drift=no migrations=no config_change=no nix_change=no
  local deps_reason="" npm_reason=""
  grep -qE '^(build\.sbt|project/(plugins|build)\.(sbt|properties))$' <<<"$changed" \
    && { deps_drift=yes; deps_reason="build.sbt or project/ changed in this range"; }
  grep -qE '^web/package-lock\.json$' <<<"$changed" \
    && { npm_drift=yes; npm_reason="web/package-lock.json changed in this range"; }
  grep -q 'db/migration/' <<<"$changed" && migrations=yes
  grep -qE '(application\.conf|\.env\.example)$' <<<"$changed" && config_change=yes
  grep -qE '^(flake\.nix|nix/)' <<<"$changed" && nix_change=yes

  # The stamp is the more reliable of the two signals, since it does not depend on the range being
  # right — it compares the inputs against what they were when each hash was last computed.
  local deps_digest npm_digest
  deps_digest="$(digest_worktree "${DEPS_INPUTS[@]}")"
  npm_digest="$(digest_worktree "${NPM_INPUTS[@]}")"
  if [ ! -f "$REPO_ROOT/$STAMP_FILE" ]; then
    deps_drift=yes; deps_reason="no hash stamp recorded yet"
    npm_drift=yes; npm_reason="no hash stamp recorded yet"
  else
    [ "$deps_digest" = "$(stamp_value depsSha256 || true)" ] \
      || { deps_drift=yes; deps_reason="${deps_reason:-its inputs no longer match the recorded digest}"; }
    [ "$npm_digest" = "$(stamp_value npmDepsHash || true)" ] \
      || { npm_drift=yes; npm_reason="${npm_reason:-its inputs no longer match the recorded digest}"; }
  fi

  head1 "What this release needs"
  [ "$deps_drift" = yes ] && say "  * depsSha256 must be recomputed — $deps_reason. This is the slow one." \
    || say "  * depsSha256 is current"
  [ "$npm_drift" = yes ] && say "  * npmDepsHash must be recomputed — $npm_reason" \
    || say "  * npmDepsHash is current"
  if [ "$migrations" = yes ]; then
    say "  * a database migration is in this release:"
    grep 'db/migration/' <<<"$changed" | sed 's/^/      /'
    say "      -> the SQLite suite enforces no foreign key and never runs the Postgres dialect,"
    say "         so this needs:  docker compose up -d postgres && RUN_POSTGRES_TESTS=1 sbt backend/test"
    say "      -> and a backup on the server before switching (printed at the end)"
  fi
  if [ "$config_change" = yes ]; then
    # A new setting is only a deployment problem when it has no default: everything else is
    # already answered by application.conf, and needs neither a module change nor a new secret.
    local added_defaults added_vars
    added_defaults="$(git -C "$REPO_ROOT" diff "$range" -- '*application.conf' 2>/dev/null \
      | grep -E '^\+[[:space:]]*[a-z][a-z0-9.-]*[[:space:]]*=' | grep -v '\${?' | sed 's/^+[[:space:]]*//' || true)"
    added_vars="$(git -C "$REPO_ROOT" diff "$range" -- '*application.conf' .env.example 2>/dev/null \
      | grep '^+' | grep -oE '\$\{\?[A-Z0-9_]+\}|^\+[A-Z0-9_]+=' \
      | grep -oE '[A-Z0-9_]{3,}' | sort -u || true)"
    say "  * configuration changed:"
    if [ -n "$added_defaults" ]; then
      say "      new settings, each with a default (so nothing to do before the switch):"
      sed 's/^/        /' <<<"$added_defaults"
    fi
    if [ -n "$added_vars" ]; then
      say "      environment overrides touched: $(tr '\n' ' ' <<<"$added_vars")"
      say "        -> any of these WITHOUT a default above has to reach the deployment"
      say "           (an Environment= in the NixOS module, or a key in the secret) first"
    fi
  fi
  [ "$nix_change" = yes ] && say "  * flake.nix or nix/ changed — re-read your NixOS module against nix/README.md"

  # --- npm hash: cheap enough to just do ---
  if [ "$npm_drift" = yes ] || [ "$force" = yes ]; then
    head1 "Refreshing npmDepsHash"
    local npm_hash
    npm_hash="$(nix run nixpkgs#prefetch-npm-deps -- "$REPO_ROOT/web/package-lock.json" 2>/dev/null | tail -1)"
    [[ "$npm_hash" == sha256-* ]] || die "prefetch-npm-deps returned '$npm_hash'"
    sed -i -E "s|npmDepsHash = \"[^\"]*\";|npmDepsHash = \"$npm_hash\";|" "$REPO_ROOT/nix/web.nix"
    ok "npmDepsHash = $npm_hash"
  fi

  # --- Build, repairing a stale depsSha256 from the mismatch it produces -------------------------
  #
  # A stale hash self-heals only if Nix actually *builds* the dependency derivation and prints its
  # `got:` line. It will not when a store path with the stale hash still exists locally: a
  # fixed-output derivation is content-addressed, so Nix reuses that path unbuilt, and the offline
  # build phase then dies fetching the now-missing jars ("UnknownHostException") with no `got:`
  # anywhere. lib.fakeSha256 has no such path by construction. So: prime the hash with it whenever
  # drift is already known, and fall back to priming-then-retry if a build fails with nothing to
  # harvest. Either way the resolve is forced and the `got:` line appears.
  head1 "Building"
  local system out_paths log deps_primed=no
  system="$(nix_system)"
  log="$(mktemp)"

  if [ "$deps_drift" = yes ] || [ "$force" = yes ]; then
    set_deps_hash fake
    deps_primed=yes
    say "  primed depsSha256 = lib.fakeSha256 to force a re-resolve (${deps_reason:-forced})"
  fi

  if ! build_packages "$log" "$system"; then
    local got
    got="$(harvested_got "$log")"
    if [ -z "$got" ] && [ "$deps_primed" = no ]; then
      warn "build failed with no hash to harvest — a stale dependency path is being reused; forcing a re-resolve"
      set_deps_hash fake
      deps_primed=yes
      build_packages "$log" "$system" || true
      got="$(harvested_got "$log")"
    fi
    if [ -n "$got" ]; then
      warn "depsSha256 was stale; writing $got and rebuilding"
      set_deps_hash "$got"
      build_packages "$log" "$system" \
        || { sed 's/^/    /' "$log" >&2; die "build still fails after refreshing depsSha256 to $got"; }
    else
      sed 's/^/    /' "$log" >&2
      say ""
      say "  Could not self-heal. depsSha256 in nix/scala.nix is wrong and no 'got:' line was"
      say "  produced to correct it from. Recover by hand:"
      say ""
      say "    sed -i -E 's|depsSha256 = [^;]+;|depsSha256 = lib.fakeSha256;|' nix/scala.nix"
      say "    nix build .#backend 2>&1 | grep 'got:'      # copy the sha256-... it prints"
      say "    sed -i -E 's|depsSha256 = [^;]+;|depsSha256 = \"PASTE_IT_HERE\";|' nix/scala.nix"
      say "    ./scripts/release.sh                        # rerun"
      die "build failed"
    fi
  fi
  mapfile -t out_paths <"$log.out"
  rm -f "$log" "$log.out"
  local backend_out="${out_paths[0]}" web_out="${out_paths[1]}"
  ok "backend $backend_out"
  ok "web     $web_out"

  smoke_test "$backend_out" "$web_out" "$system"

  if [ "$run_tests" = yes ]; then
    head1 "Tests"
    command -v sbt >/dev/null || die "sbt is not on PATH (use --no-tests to skip)"
    (cd "$REPO_ROOT" && sbt -batch test) || die "sbt test failed"
    ok "sbt test"
    if [ "$migrations" = yes ]; then
      if [ "${RUN_POSTGRES_TESTS:-}" = 1 ]; then
        (cd "$REPO_ROOT" && RUN_POSTGRES_TESTS=1 sbt -batch backend/test) || die "Postgres integration tests failed"
        ok "RUN_POSTGRES_TESTS=1 sbt backend/test"
      else
        bad "this release changes a migration, and PostgresIntegrationSpec did not run."
        say "        docker compose up -d postgres"
        say "        RUN_POSTGRES_TESTS=1 ./scripts/release.sh --no-tests   # then rerun with tests"
        say "      or set RUN_POSTGRES_TESTS=1 for this script."
      fi
    fi
  fi

  write_stamp
  ok "wrote $STAMP_FILE"

  next_steps "$migrations"
  [ "$failures" -eq 0 ] || die "$failures item(s) above still need attention"
}

# The assertions that matter: each one is something that builds green and fails at runtime.
smoke_test() {
  local backend_out="$1" web_out="$2" system="$3"
  head1 "Smoke tests"

  # The staged application, where the jars live; the package itself is only a wrapper.
  local stage jar
  stage="$(nix build "$REPO_ROOT#packages.$system.backend.scala" --no-link --print-out-paths 2>/dev/null)"
  jar="$(ls "$stage"/lib/*backend*.jar 2>/dev/null | head -1 || true)"
  if [ -z "$jar" ]; then
    bad "no backend jar in $stage/lib"
  else
    local listing=""
    if command -v unzip >/dev/null; then
      listing="$(unzip -Z1 "$jar")"
    elif command -v python3 >/dev/null; then
      listing="$(python3 -c 'import sys,zipfile;print("\n".join(zipfile.ZipFile(sys.argv[1]).namelist()))' "$jar")"
    fi
    if [ -z "$listing" ]; then
      warn "no unzip or python3 — cannot look inside the jar"
    else
      # Messages.live fails the boot on a missing catalog, so a jar without these restart-loops.
      local locale
      for locale in en hu; do
        grep -qx "messages.$locale.json" <<<"$listing" \
          && ok "messages.$locale.json is in the jar" \
          || bad "messages.$locale.json is NOT in the jar — the backend would fail at boot"
      done
    fi
  fi

  # The launcher is a bash script that reads `java -version` through awk. Under systemd the PATH is
  # systemd's own (no awk) and it exits with "No java installations was detected." despite a
  # perfectly good JAVA_HOME. An interactive shell has awk, which is why this must run under env -i.
  local launcher launcher_out
  launcher="$(ls "$backend_out"/bin/* | head -1)"
  launcher_out="$(env -i "$launcher" -h 2>&1 || true)"
  if grep -q 'No java installations was detected' <<<"$launcher_out"; then
    bad "the launcher cannot find java in an empty environment — check the PATH prefix in nix/scala.nix"
  elif grep -q 'version' <<<"$launcher_out"; then
    ok "the launcher runs with an empty environment (awk, java both resolve)"
  else
    warn "could not tell whether the launcher works under env -i"
  fi

  # Tailwind scans modules/frontend/src through web/main.css's @source. When that scan finds
  # nothing the build still succeeds and emits a stylesheet of a few KB.
  local css css_size
  css="$(ls "$web_out"/assets/*.css 2>/dev/null | head -1 || true)"
  if [ -z "$css" ]; then
    bad "no stylesheet in $web_out/assets"
  else
    css_size="$(stat -c%s "$css")"
    if [ "$css_size" -gt 50000 ]; then
      ok "stylesheet is $((css_size / 1024)) KB (the Tailwind source scan worked)"
    else
      bad "stylesheet is only $((css_size / 1024)) KB — Tailwind scanned no Scala sources"
    fi
  fi

  local locale
  for locale in en hu; do
    [ -f "$web_out/locales/messages.$locale.json" ] \
      && ok "dist/locales/messages.$locale.json" \
      || bad "messages.$locale.json is missing from the built SPA"
  done
  [ -f "$web_out/index.html" ] && ok "index.html" || bad "no index.html in $web_out"
}

next_steps() {
  local migrations="$1"
  local sha
  sha="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"

  head1 "Next steps"
  cat <<EOF
  1. Commit and push this repository:

       git add -A && git commit -m "..." && git push origin master

  2. Point the deployment at the new revision, in your NixOS configuration repository:

       nix flake update gathedge
       git commit -am "Update gathedge" && git push

  3. On the server:
EOF
  if [ "$migrations" = yes ]; then
    cat <<'EOF'

       # this release migrates the database — take a dump first
       sudo -u postgres pg_dump gathedge > "gathedge-$(date +%F).sql"
EOF
  fi
  cat <<'EOF'

       git pull && sudo nixos-rebuild switch --flake .
       journalctl -u gathedge-backend -f       # Flyway, then "Starting gathedge backend"

  4. Once it is up and you have clicked around, record it:

       ./scripts/release.sh --mark
EOF
  say ""
  say "  Rollback is: sudo nixos-rebuild --rollback   (the previous generation still exists)"
  say "  Working tree HEAD is $sha."
}

# --- Marking and hook installation ----------------------------------------------------------------

mark_mode() {
  local rev="${1:-HEAD}" sha short tag
  sha="$(git -C "$REPO_ROOT" rev-parse --verify "$rev^{commit}")" || die "not a commit: $rev"
  short="$(git -C "$REPO_ROOT" rev-parse --short "$sha")"
  tag="released/$(date +%Y%m%d)-$short"
  git -C "$REPO_ROOT" tag -f "$tag" "$sha" >/dev/null
  say "Marked $short as released: $tag"
  say "This is what the next run diffs against. It records what was *released*, not what is running —"
  say "if you roll the server back, move it back too (git tag -d $tag)."
}

install_hook() {
  git -C "$REPO_ROOT" config core.hooksPath "$HOOKS_DIR"
  say "core.hooksPath = $HOOKS_DIR"
  say "Every push now runs ./scripts/release.sh --check on the commit being pushed (~3s)."
  say "Skip it once with: git push --no-verify"
}

# --- Entry point -----------------------------------------------------------------------------------

main() {
  cd "$REPO_ROOT"
  # Asked of git rather than of the filesystem: a worktree's .git is a *file* naming the main
  # checkout's worktrees directory, so a `-d .git` test says "not a git repository" in every worktree
  # scripts/new-worktree.sh makes — and the pre-push hook runs here from each of them.
  git rev-parse --git-dir >/dev/null 2>&1 || die "not a git repository: $REPO_ROOT"

  local mode=prepare force=no run_tests=yes rev=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --check)        mode=check; [ $# -gt 1 ] && [[ "$2" != --* ]] && { rev="$2"; shift; } ;;
      --mark)         mode=mark;  [ $# -gt 1 ] && [[ "$2" != --* ]] && { rev="$2"; shift; } ;;
      --install-hook) mode=hook ;;
      --force)        force=yes ;;
      --no-tests)     run_tests=no ;;
      -h | --help)    usage; exit 0 ;;
      *)              die "unrecognised argument '$1' (see --help)" ;;
    esac
    shift
  done

  case "$mode" in
    check)   check_mode "${rev:-HEAD}" ;;
    prepare) prepare_mode "$force" "$run_tests" ;;
    mark)    mark_mode "${rev:-HEAD}" ;;
    hook)    install_hook ;;
  esac
}

main "$@"
