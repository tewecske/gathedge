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
# German and Hungarian are a few per cent.
#
# None of it belongs on a server. DictionaryImport's --export mode turns a dump into a flat TSV of
# just the words and pairs that survived the frequency cut, which at --limit 20000 is a few megabytes
# gzipped; that file is what gets shipped, and `--seed <path>` on the server loads it. So this script
# is the dev-machine half: fetch the inputs, run the export, and print the two commands that move the
# result across.
#
# The dump is cached rather than deleted, and the download resumes, because the whole point is that a
# second run at a different --limit costs nothing. --drop-dump opts out of that.
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

# Corpus frequency, as data/frequency/README.md documents it. Missing files are not an error to the
# importer, but without them --limit keeps everything and the search box loses its ordering, so they
# are fetched rather than left to chance.
readonly FREQ_DIR="data/frequency"
readonly FREQ_BASE="https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018"
readonly FREQ_LANGS=(en de hu)

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
  for lang in "${FREQ_LANGS[@]}"; do
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

export_seed() {
  local dump="$1" limit="$2" out="$3"
  head1 "Export (--limit $limit)"
  mkdir -p "$(dirname "$out")"
  # No database: DictionaryImport skips `store` whenever --export is given, so nothing needs to be
  # running. Reading the dump holds the whole en/de/hu subset in memory before the cut is applied,
  # which is what .jvmopts' -Xmx4G is for; raise it there if this dies with an OutOfMemoryError.
  sbt -batch -no-colors "backend/runMain $MAIN_CLASS \
    --raw $dump --limit $limit --frequencies $FREQ_DIR --export $out" \
    || die "the export failed (see the sbt output above)"
  [ -s "$out" ] || die "the export produced no file at $out"
}

report() {
  local out="$1"
  head1 "Result"
  ok "$out — $(du -h "$out" | cut -f1)"

  # Record types, as SeedFormat writes them: W is a word, T a direct translation pair. The pivot
  # (German-Hungarian) rows are not in the file; the importer re-derives them from the T rows when it
  # stores, which is why the seed stays this small.
  local reader=cat
  case "$out" in *.gz) reader="gzip -dc" ;; esac
  local words pairs
  words=$($reader "$out" | grep -c '^W' || true)
  pairs=$($reader "$out" | grep -c '^T' || true)
  say "  $words word(s), $pairs direct translation pair(s)"

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
  fetch_dump "$dump"
  export_seed "$dump" "$limit" "$out"
  report "$out"

  if [ "$drop_dump" = yes ]; then
    rm -f "$dump"
    head1 "Dump"
    ok "removed $dump — the next run downloads 2.6 GB again"
  fi
}

main "$@"
