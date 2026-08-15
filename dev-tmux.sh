#!/usr/bin/env bash
# Starts the full dev stack (both backends, the Scala.js watcher, and Vite)
# each in its own tmux pane, inside a tmux window. If that window already
# exists (from a previous run), reuses its panes and restarts the commands
# in place instead of creating a new window/panes.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SESSION_NAME="gathedge"
WINDOW_NAME="dev"

CMD_BACKEND="sbt --client '~backend/reStart'"
CMD_SCALAJS="sbt --client '~frontend/fastLinkJS'"
CMD_VITE="npm --prefix web run dev"

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
  TARGET_SESSION=$(tmux display-message -p '#{session_name}')
else
  TARGET_SESSION="$SESSION_NAME"
fi

# restart_pane <pane_id> <command>: interrupt whatever is running, then
# re-issue the command in the same pane.
restart_pane() {
  tmux send-keys -t "$1" C-c
  sleep 0.3
  tmux send-keys -t "$1" "$2" C-m
}

WIN_ID=""
if tmux has-session -t "$TARGET_SESSION" 2>/dev/null \
  && tmux list-windows -t "$TARGET_SESSION" -F '#{window_id} #{window_name}' \
     | grep -q " ${WINDOW_NAME}\$"; then
  WIN_ID=$(tmux list-windows -t "$TARGET_SESSION" -F '#{window_id} #{window_name}' \
    | awk -v n="$WINDOW_NAME" '$2==n {print $1}')
fi

if [ -n "$WIN_ID" ]; then
  PANE_BACKEND=$(tmux list-panes -t "$WIN_ID" -F '#{pane_id} #{pane_title}' | awk '$2=="http :8080" {print $1}')
  PANE_SCALAJS=$(tmux list-panes -t "$WIN_ID" -F '#{pane_id} #{pane_title}' | awk '$2=="scalajs fastLinkJS" {print $1}')
  PANE_VITE=$(tmux list-panes -t "$WIN_ID" -F '#{pane_id} #{pane_title}' | awk '$2=="vite :5173" {print $1}')

  if [ -n "$PANE_BACKEND" ] && [ -n "$PANE_SCALAJS" ] && [ -n "$PANE_VITE" ]; then
    echo "Window '$WINDOW_NAME' already exists, restarting panes in place..."
    restart_pane "$PANE_BACKEND" "$CMD_BACKEND"
    restart_pane "$PANE_SCALAJS" "$CMD_SCALAJS"
    restart_pane "$PANE_VITE" "$CMD_VITE"
  else
    # Window exists but panes aren't the ones we expect (stale/partial
    # state from an interrupted run) - tear it down and rebuild fresh.
    tmux kill-window -t "$WIN_ID"
    WIN_ID=""
  fi
fi

if [ -z "$WIN_ID" ]; then
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
  tmux send-keys -t "$PANE_BACKEND" "$CMD_BACKEND" C-m

  PANE_SCALAJS=$(tmux split-window -P -F '#{pane_id}' -c "$ROOT_DIR")
  tmux send-keys -t "$PANE_SCALAJS" "$CMD_SCALAJS" C-m

  PANE_VITE=$(tmux split-window -P -F '#{pane_id}' -v -t "$PANE_BACKEND" -c "$ROOT_DIR")
  tmux send-keys -t "$PANE_VITE" "$CMD_VITE" C-m

  tmux select-layout -t "$WIN_ID" tiled

  tmux set-option -t "$WIN_ID" pane-border-status top
  tmux select-pane -t "$PANE_BACKEND" -T "http :8080"
  tmux select-pane -t "$PANE_SCALAJS" -T "scalajs fastLinkJS"
  tmux select-pane -t "$PANE_VITE" -T "vite :5173"
fi

if [ -z "${TMUX:-}" ]; then
  tmux attach -t "$SESSION_NAME"
else
  tmux select-window -t "$WIN_ID"
fi
