#!/bin/sh
# Sourced by cell.sh. All process selection is scoped to this installation and tag.

cell_alive() {
  case "$1" in ''|*[!0-9]*|0|1) return 1 ;; esac
  cell_state=$(ps -p "$1" -o stat= 2>/dev/null) || return 1
  case "$cell_state" in ''|*Z*) return 1 ;; esac
  return 0
}

cell_arguments() {
  # Linux /proc preserves argv boundaries. Flattened ps output cannot distinguish
  # /opt/cube from a single argument containing /opt/cube backup.
  [ -r "/proc/$1/cmdline" ] || return 1
  tr '\000' '\n' < "/proc/$1/cmdline"
}

cell_matches() {
  cell_alive "$1" || return 1
  cell_args=$(cell_arguments "$1") || return 1
  if printf '%s\n' "$cell_args" | awk -v tag="$TAG" -v base="$CC_BASE" -v jar="$CC_HOME/bin/cell.jar" '
    {
      if (previous == "cell.carpet.Cell" && $0 == "start") main=1;
      if (previous == "-tag") tagged=($0 == tag);
      if (index($0, "-Dcube.base=") == 1) { hasbase=1; samebase=($0 == "-Dcube.base=" base) }
      if (previous == "-classpath" || previous == "-cp") {
        samejar=0; n=split($0, paths, ":");
        for (i=1; i<=n; i++) if (paths[i] == jar) samejar=1;
      }
      previous=$0;
    }
    END {
      if (!(main && tagged)) exit 1;
      if (hasbase) exit !samebase;
      if (samejar) exit 0;
      exit 2;
    }'; then
    return 0
  else
    cell_match_status=$?
  fi
  [ "$cell_match_status" -eq 2 ] || return 1
  # Legacy JVMs have no base property. On Linux their working directory identifies
  # the installation; never use the old shared bin/pid as authority for a signal.
  [ -d "/proc/$1/cwd" ] || return 1
  cell_cwd=$(readlink "/proc/$1/cwd" 2>/dev/null) || return 1
  [ "$cell_cwd" = "$CC_BASE" ]
}

cell_identity() {
  # A PID may be reused during a long stop. Recheck its creation time before signals.
  if [ -r "/proc/$1/stat" ]; then
    sed 's/^.*) //' "/proc/$1/stat" | awk '{print $20}'
  else
    ps -p "$1" -o lstart= 2>/dev/null
  fi
}

cell_same_process() {
  cell_matches "$CELL_TARGET" &&
    [ "$(cell_identity "$CELL_TARGET")" = "$CELL_IDENTITY" ]
}

cell_find() {
  CELL_TARGET=
  if [ -f "$CC_PID" ]; then
    cell_saved=$(cat "$CC_PID")
    if cell_matches "$cell_saved"; then
      CELL_TARGET=$cell_saved
    elif cell_alive "$cell_saved"; then
      echo "Refusing stale PID file $CC_PID: PID $cell_saved belongs to another process" >&2
      return 1
    fi
  fi
  # Also find legacy deployments and missing PID files, and reject duplicate JVMs.
  cell_inventory=$(ps -e -ww -o pid= -o args=) || return 1
  for cell_candidate in $(printf '%s\n' "$cell_inventory" | awk '
    { for (i=2; i<=NF; i++) if ($i == "cell.carpet.Cell" && $(i+1) == "start") { print $1; break } }'); do
    [ "$cell_candidate" = "$CELL_TARGET" ] && continue
    if cell_alive "$cell_candidate" && ! cell_arguments "$cell_candidate" >/dev/null 2>&1; then
      echo "Cannot read JVM arguments for PID $cell_candidate; use the service account to stop it" >&2
      return 1
    fi
    if cell_matches "$cell_candidate"; then
      if [ -n "$CELL_TARGET" ]; then
        echo "Multiple $TAG JVMs in $CC_BASE ($CELL_TARGET, $cell_candidate); refusing ambiguous operation" >&2
        return 1
      fi
      CELL_TARGET=$cell_candidate
    fi
  done
  if [ -n "$CELL_TARGET" ]; then
    CELL_IDENTITY=$(cell_identity "$CELL_TARGET")
    [ -n "$CELL_IDENTITY" ] || return 1
  fi
}

cell_lock() {
  mkdir -p "$(dirname "$CC_PID")" || return 1
  if ! mkdir "$CC_PID.lock" 2>/dev/null; then
    echo "Lifecycle lock exists: $CC_PID.lock; another start/stop may be running" >&2
    return 1
  fi
  trap 'rmdir "$CC_PID.lock" 2>/dev/null' 0
  trap 'exit 1' HUP INT TERM
}

cell_wait() {
  cell_remaining=$1
  while cell_same_process; do
    [ "$cell_remaining" -gt 0 ] || return 1
    sleep 1
    cell_remaining=$((cell_remaining - 1))
  done
  # If identity cannot be read while PID is still alive, do not report success.
  if cell_alive "$CELL_TARGET"; then
    cell_current_identity=$(cell_identity "$CELL_TARGET")
    [ -n "$cell_current_identity" ] && [ "$cell_current_identity" != "$CELL_IDENTITY" ]
  else
    return 0
  fi
}

cell_stop_client() {
  if [ -n "$CELL_STOP_CLIENT" ]; then
    cell_client_parent=$(ps -p "$CELL_STOP_CLIENT" -o ppid= 2>/dev/null | tr -d ' ')
    if { [ -n "$CELL_STOP_IDENTITY" ] &&
        [ "$(cell_identity "$CELL_STOP_CLIENT")" = "$CELL_STOP_IDENTITY" ]; } ||
        [ "$cell_client_parent" = "$$" ]; then
      kill "$CELL_STOP_CLIENT" 2>/dev/null
      kill -9 "$CELL_STOP_CLIENT" 2>/dev/null
    fi
    if ! cell_alive "$CELL_STOP_CLIENT"; then
      wait "$CELL_STOP_CLIENT" 2>/dev/null
    fi
    CELL_STOP_CLIENT=
  fi
}

cell_stop() {
  CC_STOP_TIMEOUT=${CC_STOP_TIMEOUT:-30}
  CC_TERM_TIMEOUT=${CC_TERM_TIMEOUT:-10}
  for cell_timeout in "$CC_STOP_TIMEOUT" "$CC_TERM_TIMEOUT"; do
    case "$cell_timeout" in ''|*[!0-9]*) echo "Stop timeouts must be integer seconds" >&2; return 1 ;; esac
  done
  cell_lock || return 1
  cell_find || return 1
  if [ -z "$CELL_TARGET" ]; then
    rm -f "$CC_PID"
    echo "$TAG is not running in $CC_BASE"
    return 0
  fi
  # Persist a recovered legacy PID so a failed stop still leaves useful diagnostics.
  (umask 077; echo "$CELL_TARGET" > "$CC_PID") || return 1
  echo "Stopping $TAG (PID $CELL_TARGET), waiting up to ${CC_STOP_TIMEOUT}s"
  "$_RUNJAVA" -classpath "$CC_HOME/bin/cell.jar" cell.carpet.Cell stop "$TAG" &
  CELL_STOP_CLIENT=$!
  CELL_STOP_IDENTITY=$(cell_identity "$CELL_STOP_CLIENT")
  trap 'cell_stop_client; rmdir "$CC_PID.lock" 2>/dev/null' 0
  cell_stopped=false
  if cell_wait "$CC_STOP_TIMEOUT"; then
    cell_stopped=true
  elif cell_same_process; then
    echo "Graceful stop timed out for $TAG; sending TERM to PID $CELL_TARGET" >&2
    kill -TERM "$CELL_TARGET" || return 1
    if cell_wait "$CC_TERM_TIMEOUT"; then
      cell_stopped=true
    elif [ "$FORCE" -eq 1 ] && cell_same_process; then
      echo "TERM timed out; sending KILL to verified $TAG PID $CELL_TARGET" >&2
      kill -KILL "$CELL_TARGET" || return 1
      if cell_wait 5; then cell_stopped=true; fi
    fi
  fi
  cell_stop_client
  if $cell_stopped; then
    rm -f "$CC_PID"
    echo "$TAG stopped (PID $CELL_TARGET)"
    return 0
  fi
  echo "$TAG is still running or cannot be verified (PID $CELL_TARGET). PID file retained: $CC_PID" >&2
  echo "Capture jstack $CELL_TARGET; use stop-$TAG.sh -force to allow KILL after the timeouts" >&2
  return 1
}
