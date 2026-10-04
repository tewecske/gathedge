#!/usr/bin/env bash
#
# Builds a dictionary seed file from the wiktextract dump, for loading into a deployment.
#
#   ./scripts/build-dictionary-seed.sh                  # 20k words per language
#   ./scripts/build-dictionary-seed.sh --limit 50000
#   ./scripts/build-dictionary-seed.sh --out /tmp/x.tsv.gz --dump ~/dumps/raw.jsonl.gz
#   ./scripts/build-dictionary-seed.sh --drop-dump      # delete the 2.6 GB dump when finished
#
# WHY THIS EXISTS
#
# The source dictionary is the English Wiktionary as extracted by wiktextract: every language it
# covers (~1000 of them), one fat JSON object per entry — etymology, IPA, audio references,
# inflection tables, sense examples, categories — plus a separate entry per inflected form. That is
# 22.9 GB uncompressed, 2.6 GB gzipped, of which the six fields WiktextractParser reads for English,
# German, Spanish and Hungarian are a few per cent.
#
# None of it belongs on a server. DictionaryImport's --export mode turns a dump into a flat TSV of
# just the words, pairs, and word forms (plurals, verb tenses, declension/case tables) that survived
# the frequency cut; most of the file's size is forms, since German, Spanish and Hungarian words can
# carry dozens of them each regardless of --limit. That file is what gets shipped, and `--seed <path>` on
# the server loads it. So this script is the dev-machine half: fetch the inputs, run the export, and
# print the two commands that move the result across.
#
# The dump is cached rather than deleted, and the download resumes, because the whole point is that a
# second run at a different --limit costs nothing. --drop-dump opts out of that.
#
# The export never reads the dump itself. A first step cuts it into one gzipped shard per language
# (DictionaryImport --extract), and the export reads those. The shards stay in data/dictionary/shards, so a later
# run skips the dump, and a run after a new dump download cuts them again. A shard missing or older
# than the dump is cut again; the others are left alone, so a new language costs one pass of the dump.
#
# WHAT IT DELIBERATELY DOES NOT DO
#
# It does not copy anything to a server or touch a database. The export is an offline transformation
# (DictionaryImport runs `store` only when --export is absent), and the scp is printed for you to
# run — the same rule scripts/release.sh follows about a script holding credentials for a host.

set -euo pipefail

# --- Paths and constants -------------------------------------------------------------------------

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

readonly DUMP_URL="https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz"
readonly DEFAULT_DUMP="data/dictionary/raw-wiktextract-data.jsonl.gz"
readonly SHARD_DIR="data/dictionary/shards"

# Corpus frequency, as "Frequency lists" in README.md documents it. Missing files are not an error to the
# importer, but without them --limit keeps everything and the search box loses its ordering, so they
# are fetched rather than left to chance.
readonly FREQ_DIR="data/frequency"
readonly FREQ_BASE="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018"
# The languages a seed holds: one frequency list and one shard each. The export is told this list, so a
# language the code knows (WordLanguage) is left out of the seed until it is added here.
readonly LANGS=(en de es fr hu pt)

readonly MAIN_CLASS="gathedge.backend.tools.DictionaryImport"

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

fetch_frequencies() {
  head1 "Frequency lists ($FREQ_DIR)"
  mkdir -p "$FREQ_DIR"
  local lang file
  for lang in "${LANGS[@]}"; do
    file="$FREQ_DIR/${lang}_50k.txt"
    if [ -s "$file" ]; then
      ok "$file already present"
    else
      say "  fetching ${lang}_50k.txt"
      curl -fL --progress-bar -o "$file" "$FREQ_BASE/$lang/${lang}_50k.txt" \
        || die "could not fetch $FREQ_BASE/$lang/${lang}_50k.txt"
      ok "$file"
    fi
  done
}

fetch_dump() {
  local dump="$1"
  head1 "Dump ($dump)"
  if [ -s "$dump" ]; then
    ok "already present, $(du -h "$dump" | cut -f1) — delete it to re-download"
    return
  fi
  mkdir -p "$(dirname "$dump")"
  warn "2.6 GB download; -C - resumes a partial file, so an interrupted run can simply be repeated"
  curl -fL -C - --progress-bar -o "$dump" "$DUMP_URL" || die "could not fetch $DUMP_URL"
  ok "$(du -h "$dump" | cut -f1)"
}

# The languages whose shard is missing or older than the dump, comma-separated.
stale_shards() {
  local dump="$1" lang shard stale=()
  for lang in "${LANGS[@]}"; do
    shard="$SHARD_DIR/$lang.jsonl.gz"
    if [ ! -s "$shard" ] || [ "$dump" -nt "$shard" ]; then
      stale+=("$lang")
    fi
  done
  (IFS=,; printf '%s' "${stale[*]}")
}

export_seed() {
  local dump="$1" limit="$2" out="$3"
  mkdir -p "$(dirname "$out")"

  # One sbt session for both steps: its startup is most of a small run.
  local commands=() stale
  stale="$(stale_shards "$dump")"
  head1 "Shards ($SHARD_DIR)"
  if [ -n "$stale" ]; then
    say "  cutting $stale from the dump: one pass, a few minutes"
    commands+=("backend/runMain $MAIN_CLASS --raw $dump --extract $SHARD_DIR --languages $stale")
  else
    ok "all present and newer than the dump"
  fi

  head1 "Export (--limit $limit)"
  # No database: DictionaryImport skips `store` whenever --export is given, so nothing need be running.
  # The export holds the pairs and one language at a time; .jvmopts' -Xmx4G is ample.
  local languages
  languages="$(IFS=,; echo "${LANGS[*]}")"
  commands+=("backend/runMain $MAIN_CLASS --shards $SHARD_DIR --languages $languages --limit $limit --frequencies $FREQ_DIR --export $out")
  sbt -batch -no-colors "${commands[@]}" || die "the export failed (see the sbt output above)"
  [ -s "$out" ] || die "the export produced no file at $out"
}

report() {
  local out="$1"
  head1 "Result"
  ok "$out — $(du -h "$out" | cut -f1)"

  # Record types, as SeedFormat writes them: W is a word, T a direct translation pair, F a form
  # relation (plural, past tense, declension/case table cell, ...). The pivot (German-Hungarian) rows
  # are not in the file; the importer re-derives them from the T rows when it stores.
  local reader=cat
  case "$out" in *.gz) reader="gzip -dc" ;; esac
  local words pairs forms
  words=$($reader "$out" | grep -c '^W' || true)
  pairs=$($reader "$out" | grep -c '^T' || true)
  forms=$($reader "$out" | grep -c '^F' || true)
  say "  $words word(s), $pairs direct translation pair(s), $forms form relation(s)"

  head1 "Load it into the deployment"
  say "  scp $out <host>:/tmp/"
  say ""
  say "  # on the host — DB_URL/DB_USER as nix/module.nix sets them, DB_PASSWORD from the env file"
  say "  sudo sh -c 'set -a; . /var/lib/secrets/gathedge.env; set +a"
  say "    DB_URL=jdbc:postgresql://127.0.0.1:5432/gathedge DB_USER=gathedge \\"
  say "    gathedge-dictionary-import --seed /tmp/$(basename "$out")'"
  say ""
  say "  The import is idempotent, so a later run with a bigger seed inserts only the difference."
}

# --- Entry point -----------------------------------------------------------------------------------

main() {
  cd "$REPO_ROOT"

  local limit=20000 out="" dump="$DEFAULT_DUMP" drop_dump=no
  while [ $# -gt 0 ]; do
    case "$1" in
      --limit)     [ $# -gt 1 ] || die "--limit needs a number"; limit="$2"; shift ;;
      --out)       [ $# -gt 1 ] || die "--out needs a path";     out="$2";   shift ;;
      --dump)      [ $# -gt 1 ] || die "--dump needs a path";    dump="$2";  shift ;;
      --drop-dump) drop_dump=yes ;;
      -h | --help) usage; exit 0 ;;
      *)           die "unrecognised argument '$1' (see --help)" ;;
    esac
    shift
  done

  [[ "$limit" =~ ^[0-9]+$ ]] && [ "$limit" -gt 0 ] || die "--limit needs a positive number, got '$limit'"
  out="${out:-target/dictionary/seed-$limit.tsv.gz}"
  # readSeed/writeSeed pick GZIPStream off the suffix and nothing else, so a name they cannot read
  # back is worth refusing here rather than on the server.
  case "$out" in
    *.tsv | *.tsv.gz) ;;
    *) die "--out must end in .tsv or .tsv.gz, got '$out'" ;;
  esac

  # Checked before the download rather than after it.
  command -v curl >/dev/null || die "curl is not on the PATH"
  command -v sbt  >/dev/null || die "sbt is not on the PATH (nix develop provides it)"

  fetch_frequencies
  # Current shards make the dump unneeded, even when --drop-dump removed it last time.
  if [ -n "$(stale_shards "$dump")" ]; then
    fetch_dump "$dump"
  fi
  export_seed "$dump" "$limit" "$out"
  report "$out"

  if [ "$drop_dump" = yes ]; then
    rm -f "$dump"
    head1 "Dump"
    ok "removed $dump — the shards stay, so the next run needs it only for a new language"
  fi
}

main "$@"
