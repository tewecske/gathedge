---
description: Implement the task described in a GitHub issue in an isolated worktree, then open a PR
argument-hint: <issue-number>
allowed-tools: Bash(gh issue view:*), Bash(gh issue comment:*), Bash(gh pr create:*), Bash(gh pr view:*), Bash(git*), Bash(psql:*), Bash(ss:*), Bash(nc:*), Bash(sbt*), Bash(npm*)
---

The repo is `tewecske/gathedge`. The base branch is `master`.

Task source: GitHub issue **#$ARGUMENTS** (call it `N` below).

All work happens in a **dedicated git worktree** with its own dev ports and its
own copy of the database, so it never collides with the main checkout or another
worktree.

## 1. Read the ticket

Run `gh issue view $ARGUMENTS --comments`.

Restate the task in one sentence. If the issue is unclear enough that you would
build the wrong thing, stop here and ask me directly in the chat (do not comment
on the issue yet, and do not create the worktree).

## 2. Create the isolated worktree

```bash
N=$ARGUMENTS
SLUG=$(gh issue view "$N" --json title -q .title \
  | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-|-$//g' | cut -c1-40)
BR="issue-$N-$SLUG"
WT="../gathedge-issue-$N"

git worktree add "$WT" -b "$BR" master
cd "$WT"
```

Run every remaining step with `$WT` as the working directory.

## 3. Pick free dev ports

The backend and Vite both read the **worktree-local `.env`** (via
`reStart / envVars` in `build.sbt` and `loadEnv` in `web/vite.config.ts`), so
each worktree just needs its own values.

```bash
OFF=$(( (N % 50) * 10 ))
SERVER_PORT=$(( 8080 + OFF ))
VITE_PORT=$(( 5173 + OFF ))
# bump past anything already listening
while ss -ltn | grep -q ":$SERVER_PORT "; do SERVER_PORT=$(( SERVER_PORT + 1 )); done
while ss -ltn | grep -q ":$VITE_PORT ";   do VITE_PORT=$(( VITE_PORT + 1 ));   done
```

## 4. Copy the database

Same Postgres server as the main checkout, but a **separate database** cloned
from `gathedge` (this keeps Flyway history, the imported dictionary, and all data
without a re-import). `CREATE DATABASE ... TEMPLATE` needs no other sessions on
the template.

```bash
ADMIN="postgresql://gathedge:gathedge@localhost:5432/postgres"
psql "$ADMIN" -c "CREATE DATABASE gathedge_wt_$N TEMPLATE gathedge;"
```

If that fails with *"source database is being accessed by other users"*, tell me
— I will either pause the main dev stack for a moment, or you fall back to a
fresh database:

```bash
psql "$ADMIN" -c "CREATE DATABASE gathedge_wt_$N;"
# schema + tables are created by Flyway on first backend boot;
# then load the sample dictionary:
sbt "backend/runMain gathedge.backend.tools.DictionaryImport --seed"
```

(Keep `DB_SCHEMA=gathedge` unchanged — the isolation is at the database level.)

## 5. Write the worktree `.env`

Start from the main checkout's `.env` if it is readable, otherwise from
`.env.example`, then override exactly these keys:

```
SERVER_PORT=<SERVER_PORT>
VITE_PORT=<VITE_PORT>
DB_URL=jdbc:postgresql://localhost:5432/gathedge_wt_<N>
DB_SCHEMA=gathedge
PUBLIC_BASE_URL=http://localhost:<VITE_PORT>
```

`.env` is git-ignored and per-worktree; do not commit it.

## 6. Do the work

- Implement the change on branch `$BR`. Follow `CLAUDE.md` — `-noindent` Scala 3
  (explicit `{ }`), `-Werror`, ASD-STE100 writing style, the dual-dialect DB
  rules, endpoint/DTO parity, i18n message-key rules, and anything else in scope.
- Keep commits focused; write commit messages in the repo's style and end each with:
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`

## 7. Gate before finishing

- `sbt scalafmtAll`
- `sbt test` (add `sbt backend/test` / `sbt frontend/test` / `sbt sharedJVM/test`
  as the change requires). These use the SQLite test DB and need none of the dev
  ports or the cloned database.
- If you touched migrations or anything referential-integrity related, note that
  `RUN_POSTGRES_TESTS=1 sbt backend/test` should be run and say why.
- Only start the dev stack (`npm run dev` from `$WT`) if you must verify behavior
  in the running app. It will use the ports and database set up above. Stop it
  when done — a second sbt server plus Vite is heavy.

Everything must be green. If a pre-existing failure is unrelated to your change,
say so explicitly rather than silently ignoring it.

## 8. If blocked

Only when you cannot proceed without a decision that is mine to make (product
behavior, an ambiguous requirement, a missing credential): post **one** comment
with `gh issue comment $ARGUMENTS --body "..."` stating the specific question and
the options as you see them, then stop and tell me you're blocked.

Do not comment for progress updates or to think out loud.

## 9. Open the PR

When the work is done and tests pass:

```bash
git push -u origin "$BR"
gh pr create --base master --head "$BR" \
  --title "<concise imperative summary>" \
  --body "$(cat <<'EOF'
## Summary
- <what changed and why, 1-3 bullets>

## Testing
- <commands run and their result>

Closes #$ARGUMENTS

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Paste the PR URL back to me.

## 10. Tear down (only after I confirm the PR is merged or closed)

```bash
cd -                       # back to the main checkout
git worktree remove "../gathedge-issue-$ARGUMENTS" --force
psql "postgresql://gathedge:gathedge@localhost:5432/postgres" \
  -c "DROP DATABASE gathedge_wt_$ARGUMENTS;"
```

Do not tear down on your own — leave the worktree and database in place until I
say so, in case the PR needs changes.
