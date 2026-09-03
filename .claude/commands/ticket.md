---
description: Implement the task described in a GitHub issue in an isolated worktree, then open a PR
argument-hint: <issue-number>
allowed-tools: Bash(gh issue view:*), Bash(gh issue comment:*), Bash(gh pr create:*), Bash(gh pr view:*), Bash(git*), Bash(scripts/new-worktree.sh:*), Bash(scripts/rm-worktree.sh:*), Bash(docker compose:*), Bash(psql:*), Bash(ss:*), Bash(nc:*), Bash(sbt*), Bash(npm*)
---

The repo is `tewecske/gathedge`. The base branch is `master`.

Task source: GitHub issue **#$ARGUMENTS** (call it `N` below).

All work happens in a **dedicated git worktree** with its own dev ports and its
own Postgres schema in the shared `gathedge` database, so it never collides with
the main checkout or another worktree.

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

WT=$(scripts/new-worktree.sh "$BR")   # prints the worktree path on stdout
cd "$WT"
```

`scripts/new-worktree.sh` does the whole setup:

- picks the lowest free `wt-<n>-` slot and puts the worktree at `../wt-<n>-$BR`;
- offsets the dev ports by `n*10` (`SERVER_PORT=8080+n*10`, `VITE_PORT=5173+n*10`),
  each bumped past anything already listening;
- writes the worktree `.env` (copied from the main checkout's, with `SERVER_PORT`,
  `VITE_PORT`, `PUBLIC_BASE_URL`, `DB_SCHEMA=gathedge_wt<n>` and a localhost
  `DB_URL` overridden) — `.env` is git-ignored and per-worktree;
- clones the `gathedge` schema (structure **and** data, so the dictionary comes
  with it) into `gathedge_wt<n>` in the same Postgres.

Run every remaining step with `$WT` as the working directory.

## 3. Do the work

- Implement the change on branch `$BR`. Follow `CLAUDE.md` — `-noindent` Scala 3
  (explicit `{ }`), `-Werror`, ASD-STE100 writing style, the dual-dialect DB
  rules, endpoint/DTO parity, i18n message-key rules, and anything else in scope.
- Keep commits focused; write commit messages in the repo's style and end each with:
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`

## 4. Gate before finishing

- `sbt scalafmtAll`
- `sbt test` (add `sbt backend/test` / `sbt frontend/test` / `sbt sharedJVM/test`
  as the change requires). These use the SQLite test DB and need none of the dev
  ports or the cloned schema.
- If you touched migrations or anything referential-integrity related, note that
  `RUN_POSTGRES_TESTS=1 sbt backend/test` should be run and say why.
- Only start the dev stack (`npm run dev` from `$WT`) if you must verify behavior
  in the running app. It will use the ports and schema set up above. Stop it
  when done — a second sbt server plus Vite is heavy.

Everything must be green. If a pre-existing failure is unrelated to your change,
say so explicitly rather than silently ignoring it.

## 5. If blocked

Only when you cannot proceed without a decision that is mine to make (product
behavior, an ambiguous requirement, a missing credential): post **one** comment
with `gh issue comment $ARGUMENTS --body "..."` stating the specific question and
the options as you see them, then stop and tell me you're blocked.

Do not comment for progress updates or to think out loud.

## 6. Open the PR

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

## 7. Tear down (only after I confirm the PR is merged or closed)

```bash
cd -   # back to the main checkout
scripts/rm-worktree.sh --branch "issue-$ARGUMENTS-$SLUG" --yes            # keep the branch
# once the PR is merged and the branch is no longer needed:
scripts/rm-worktree.sh --branch "issue-$ARGUMENTS-$SLUG" --yes --delete-branch
```

`scripts/rm-worktree.sh` removes the worktree and drops the `gathedge_wt<n>`
schema. Do not tear down on your own — leave the worktree and schema in place
until I say so, in case the PR needs changes.
