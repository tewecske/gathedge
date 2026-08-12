#!/usr/bin/env bash
# Starts the full dev stack (both backends, the Scala.js watcher, and Vite)
# each in its own tmux pane, inside a new tmux window.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SESSION_NAME="gathedge"
WINDOW_NAME="dev"

cd "$ROOT_DIR"

if ! command -v tmux >/dev/null 2>&1; then
  echo "tmux is not installed." >&2
  exit 1
fi

# Prime sbt's shared background server once, synchronously, so the three
# concurrent `sbt --client` panes below don't race each other to boot it.
echo "Priming sbt server..."
sbt --client 'root/compile'

if [ -n "${TMUX:-}" ]; then
  # Already inside tmux: add a new window to the current session.
  WIN_ID=$(tmux new-window -P -F '#{window_id}' -n "$WINDOW_NAME" -c "$ROOT_DIR")
else
  # Not inside tmux: create (or reuse) a dedicated session.
  if tmux has-session -t "$SESSION_NAME" 2>/dev/null; then
    WIN_ID=$(tmux new-window -P -F '#{window_id}' -t "$SESSION_NAME" -n "$WINDOW_NAME" -c "$ROOT_DIR")
  else
    tmux new-session -d -s "$SESSION_NAME" -n "$WINDOW_NAME" -c "$ROOT_DIR"
    WIN_ID=$(tmux display-message -p -t "${SESSION_NAME}:${WINDOW_NAME}" '#{window_id}')
  fi
fi

PANE_BACKEND=$(tmux split-window -P -F '#{pane_id}' -h -t "$WIN_ID" -c "$ROOT_DIR")
tmux send-keys -t "$PANE_BACKEND" "sbt --client '~backend/reStart'" C-m

PANE_SCALAJS=$(tmux split-window -P -F '#{pane_id}' -c "$ROOT_DIR")
tmux send-keys -t "$PANE_SCALAJS" "sbt --client '~frontend/fastLinkJS'" C-m

PANE_VITE=$(tmux split-window -P -F '#{pane_id}' -v -t "$PANE_BACKEND" -c "$ROOT_DIR")
tmux send-keys -t "$PANE_VITE" "npm --prefix web run dev" C-m

tmux select-layout -t "$WIN_ID" tiled

tmux set-option -t "$WIN_ID" pane-border-status top
tmux select-pane -t "$PANE_BACKEND" -T "http :8080"
tmux select-pane -t "$PANE_SCALAJS" -T "scalajs fastLinkJS"
tmux select-pane -t "$PANE_VITE" -T "vite :5173"

if [ -z "${TMUX:-}" ]; then
  tmux attach -t "$SESSION_NAME"
else
  tmux select-window -t "$WIN_ID"
fi
