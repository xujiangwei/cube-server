#!/bin/sh
# Deterministic process-table tests: never signal a real service.
set -u
TEST_DIR=$(cd "$(dirname "$0")" && pwd)
. "$TEST_DIR/../bin/cell-process.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' 0
CHECKS=0

check() {
  if ! "$@"; then echo "FAIL: $*" >&2; exit 1; fi
  CHECKS=$((CHECKS + 1))
}

setup_case() {
  CASE_DIR="$TEST_ROOT/$1"
  mkdir -p "$CASE_DIR/base/bin"
  CC_BASE="$CASE_DIR/base"
  CC_HOME="$CC_BASE"
  CC_PID="$CC_BASE/bin/custom.pid"
  TAG=dispatcher
  FORCE=0
  CC_STOP_TIMEOUT=1
  CC_TERM_TIMEOUT=1
  MODE=stuck
  TABLE="$CASE_DIR/table"
  SIGNALS="$CASE_DIR/signals"
  : > "$TABLE"
  : > "$SIGNALS"
  _RUNJAVA=/bin/true
}

add_process() {
  printf '%s|S|birth-%s|java -Dcube.base=%s cell.carpet.Cell start -tag %s\n' "$1" "$1" "$2" "$3" >> "$TABLE"
}

change_state() {
  awk -F '|' -v OFS='|' -v id="$1" -v state="$2" '$1==id {$2=state} {print}' "$TABLE" > "$TABLE.next"
  mv "$TABLE.next" "$TABLE"
}

# Fixture argv for fake PIDs; production reads the NUL separated Linux /proc file.
cell_arguments() {
  if [ -f "$CASE_DIR/argv-$1" ]; then cat "$CASE_DIR/argv-$1"; return; fi
  awk -F '|' -v id="$1" '$1==id {print $4}' "$TABLE" | tr ' ' '\n'
}

# Emulate Linux ps with a table; unknown PIDs are only the stop client's real child.
ps() {
  case " $* " in
    *" -e "*) awk -F '|' '$2!="dead" {print $1 " " $4}' "$TABLE"; return ;;
  esac
  process_id=
  previous=
  for argument in "$@"; do
    if [ "$previous" = -p ]; then process_id=$argument; break; fi
    previous=$argument
  done
  if awk -F '|' -v id="$process_id" '$1==id && $2!="dead" {found=1} END {exit !found}' "$TABLE"; then
    case " $* " in
      *" stat="*) awk -F '|' -v id="$process_id" '$1==id {print $2}' "$TABLE" ;;
      *" args="*) awk -F '|' -v id="$process_id" '$1==id {print $4}' "$TABLE" ;;
      *" lstart="*) awk -F '|' -v id="$process_id" '$1==id {print $3}' "$TABLE" ;;
      *) return 1 ;;
    esac
  elif command kill -0 "$process_id" 2>/dev/null; then
    case " $* " in
      *" ppid="*) echo "$$" ;;
      *" stat="*) echo S ;;
      *" lstart="*) echo client-birth ;;
      *) return 1 ;;
    esac
  else
    return 1
  fi
}

kill() {
  case "$*" in
    *90123*)
      signal=-TERM
      case "$1" in -*) signal=$1; shift ;; esac
      printf '%s %s\n' "$signal" "$1" >> "$SIGNALS"
      case "$MODE:$signal" in term:-TERM|*:-KILL) change_state "$1" dead ;; esac ;;
    *) command kill "$@" ;;
  esac
}

sleep() {
  case "$MODE" in
    graceful) change_state 901234 dead ;;
    reuse) sed 's/birth-901234/reused-901234/' "$TABLE" > "$TABLE.next"; mv "$TABLE.next" "$TABLE" ;;
  esac
  command sleep 0.03
}

setup_case matching
add_process 901234 "$CC_BASE" dispatcher
add_process 901235 "$CC_BASE" service
add_process 901236 "$CC_BASE/other" dispatcher
add_process 901237 "$CC_BASE" dispatcher-backup
check cell_matches 901234
check test "$(cell_matches 901235; echo $?)" -ne 0
check test "$(cell_matches 901236; echo $?)" -ne 0
check test "$(cell_matches 901237; echo $?)" -ne 0
check cell_find
check test "$CELL_TARGET" = 901234

setup_case missing-pid
add_process 901234 "$CC_BASE" dispatcher
check cell_find
check test "$CELL_TARGET" = 901234

setup_case stale-pid
add_process 901235 "$CC_BASE" service
echo 901235 > "$CC_PID"
check test "$(cell_find >/dev/null 2>&1; echo $?)" -ne 0
check test ! -s "$SIGNALS"

setup_case duplicate
add_process 901234 "$CC_BASE" dispatcher
add_process 901235 "$CC_BASE" dispatcher
check test "$(cell_find >/dev/null 2>&1; echo $?)" -ne 0

setup_case legacy
printf '901234|S|birth-901234|java -classpath :%s/bin/cell.jar:/libs/a.jar cell.carpet.Cell start -tag dispatcher\n' "$CC_HOME" > "$TABLE"
check cell_find
check test "$CELL_TARGET" = 901234

setup_case shared-home
printf '901234|S|birth-901234|java -Dcube.base=%s/other -classpath :%s/bin/cell.jar:/libs/a.jar cell.carpet.Cell start -tag dispatcher\n' "$CC_BASE" "$CC_HOME" > "$TABLE"
check test "$(cell_matches 901234; echo $?)" -ne 0

setup_case spaced-path
CC_BASE="$CASE_DIR/base with spaces"
add_process 901234 "$CC_BASE" dispatcher
add_process 901235 "$CC_BASE backup" dispatcher
printf '%s\n' java "-Dcube.base=$CC_BASE" cell.carpet.Cell start -tag dispatcher > "$CASE_DIR/argv-901234"
printf '%s\n' java "-Dcube.base=$CC_BASE backup" cell.carpet.Cell start -tag dispatcher > "$CASE_DIR/argv-901235"
check cell_matches 901234
check test "$(cell_matches 901235; echo $?)" -ne 0

setup_case graceful
add_process 901234 "$CC_BASE" dispatcher
echo 901234 > "$CC_PID"
MODE=graceful
check sh -n "$TEST_DIR/../bin/cell.sh"
check sh -n "$TEST_DIR/../bin/cell-process.sh"
check test "$( (cell_stop >/dev/null 2>&1); echo $?)" -eq 0
check test ! -f "$CC_PID"
check test ! -s "$SIGNALS"
check test ! -d "$CC_PID.lock"

setup_case term
add_process 901234 "$CC_BASE" dispatcher
MODE=term
check test "$( (cell_stop >/dev/null 2>&1); echo $?)" -eq 0
check test "$(cat "$SIGNALS")" = '-TERM 901234'

setup_case timeout
add_process 901234 "$CC_BASE" dispatcher
echo 901234 > "$CC_PID"
check test "$( (cell_stop >/dev/null 2>&1); echo $?)" -ne 0
check test -f "$CC_PID"
check test "$(cat "$SIGNALS")" = '-TERM 901234'

setup_case forced
add_process 901234 "$CC_BASE" dispatcher
add_process 901235 "$CC_BASE" service
FORCE=1
check test "$( (cell_stop >/dev/null 2>&1); echo $?)" -eq 0
check test "$(wc -l < "$SIGNALS" | tr -d ' ')" -eq 2
check test "$(tail -n 1 "$SIGNALS")" = '-KILL 901234'
check cell_alive 901235

setup_case reused
add_process 901234 "$CC_BASE" dispatcher
MODE=reuse
FORCE=1
check test "$( (cell_stop >/dev/null 2>&1); echo $?)" -eq 0
check test ! -s "$SIGNALS"

setup_case hanging-client
add_process 901234 "$CC_BASE" dispatcher
MODE=term
_RUNJAVA="$CASE_DIR/java"
cat > "$_RUNJAVA" <<'CLIENT'
#!/bin/sh
exec sleep 20
CLIENT
chmod +x "$_RUNJAVA"
check test "$( (cell_stop >/dev/null 2>&1); echo $?)" -eq 0
check test ! -d "$CC_PID.lock"

setup_case zombie
add_process 901234 "$CC_BASE" dispatcher
change_state 901234 Z
check cell_find
check test -z "$CELL_TARGET"

setup_case lock
mkdir "$CC_PID.lock"
check test "$( (cell_stop >/dev/null 2>&1); echo $?)" -ne 0
check test ! -s "$SIGNALS"

setup_case wrapper
cp "$TEST_DIR/../stop-dispatcher.sh" "$CASE_DIR/stop-dispatcher.sh"
cat > "$CASE_DIR/shutdown.sh" <<'WRAPPER'
#!/bin/sh
printf '%s\n' "$@" > "$WRAPPER_ARGS"
exit 7
WRAPPER
chmod +x "$CASE_DIR/shutdown.sh"
export WRAPPER_ARGS="$CASE_DIR/args"
check test "$(sh "$CASE_DIR/stop-dispatcher.sh" -force >/dev/null 2>&1; echo $?)" -eq 7
check test "$(head -n 1 "$WRAPPER_ARGS")" = dispatcher
check test "$(tail -n 1 "$WRAPPER_ARGS")" = -force

echo "cell-process-test: $CHECKS assertions passed"
