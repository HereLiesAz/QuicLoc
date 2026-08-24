#!/data/data/com.termux/files/usr/bin/bash
#
# run-and-logcat.sh — launch QuicLoc and capture its logcat, from Termux.
#
# Reading another app's logcat is blocked for a normal Termux process on
# stock Android (each app only sees its own UID's log lines since Android
# 4.1) — root, or an adb shell connected via wireless debugging, both run
# as a UID that CAN read every app's log. This script uses whichever one
# it finds; with neither, it still runs but logcat will show ~nothing.
#
# One-time adb setup (no root needed), Android 11+:
#   1. pkg install android-tools
#   2. On the phone: Settings > Developer options > Wireless debugging >
#      "Pair device with pairing code" — note the IP:port and 6-digit code.
#   3. adb pair <ip>:<pairing-port>      (enter the 6-digit code)
#   4. adb connect <ip>:<port>           (the port shown on the main
#      Wireless debugging screen, NOT the pairing port)
#   adb stays connected across runs of this script until the phone reboots
#   or wireless debugging is turned off.
#
# Usage:
#   ./run-and-logcat.sh                 # launch + tail logcat (Ctrl-C to stop)
#   ./run-and-logcat.sh -c               # also clear old log lines first
#   ./run-and-logcat.sh -o crash.txt     # tee output to a file too
#   ./run-and-logcat.sh -p               # don't launch, just attach and watch
#
set -euo pipefail

PACKAGE="com.hereliesaz.quicloc"
ACTIVITY=".MainActivity"
OUT_FILE=""
DO_CLEAR=0
LAUNCH=1

while getopts "co:ph" opt; do
  case "$opt" in
    c) DO_CLEAR=1 ;;
    o) OUT_FILE="$OPTARG" ;;
    p) LAUNCH=0 ;;
    h)
      sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) exit 1 ;;
  esac
done

# --- Pick the first log reader that actually works: root su, then adb, ---
# --- then a bare `logcat` (works only if the device permits it).       ---
# `su -c true` alone isn't a reliable check: some root managers (Magisk in
# particular) exit 0 from a non-interactive `su` call even when the actual
# grant was denied or timed out waiting for a tap on the on-screen prompt.
# Actually checking the resulting UID catches that.
LOGCAT_CMD=""
SU_ID_OUTPUT=""
if command -v su >/dev/null 2>&1; then
  SU_ID_OUTPUT=$(su -c id 2>&1 || true)
fi
if [ -n "$SU_ID_OUTPUT" ] && echo "$SU_ID_OUTPUT" | grep -q 'uid=0'; then
  LOGCAT_CMD="su -c logcat"
  RUNNER="root (su)"
elif command -v su >/dev/null 2>&1; then
  echo "Have 'su', but it didn't grant root (got: ${SU_ID_OUTPUT:-<no output>})." >&2
  echo "If a Magisk/root-manager prompt appeared on screen, tap Allow and re-run." >&2
fi
if [ -z "$LOGCAT_CMD" ] && command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
  LOGCAT_CMD="adb logcat"
  RUNNER="adb"
else
  LOGCAT_CMD="logcat"
  RUNNER="plain (unprivileged — likely won't see QuicLoc's own log lines; see the setup notes at the top of this script)"
fi
echo "Log reader: $RUNNER"

# --- Launch the app -----------------------------------------------------
if [ "$LAUNCH" -eq 1 ]; then
  echo "Launching $PACKAGE/$ACTIVITY ..."
  if command -v am >/dev/null 2>&1; then
    am start -n "$PACKAGE/$ACTIVITY" >/dev/null 2>&1 \
      || echo "am start failed — is the app installed? Continuing to watch logcat anyway."
  elif [ "$RUNNER" = "adb" ]; then
    adb shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null 2>&1 \
      || echo "adb shell am start failed — is the app installed? Continuing to watch logcat anyway."
  else
    echo "No 'am' binary and no adb — can't auto-launch. Open QuicLoc by hand, then re-run with -p."
  fi
fi

# --- Clear old log lines, if asked --------------------------------------
if [ "$DO_CLEAR" -eq 1 ]; then
  echo "Clearing old logcat buffer..."
  eval "$LOGCAT_CMD -c" || true
fi

# --- Resolve the app's current PID (best-effort, for a tighter filter) --
# Small delay to give the process time to actually start after `am start`.
[ "$LAUNCH" -eq 1 ] && sleep 1

PID=""
if [ "$RUNNER" = "root (su)" ]; then
  PID=$(su -c "pidof $PACKAGE" 2>/dev/null || true)
elif [ "$RUNNER" = "adb" ]; then
  PID=$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
fi

echo "Streaming logcat for $PACKAGE${PID:+ (pid $PID)} — Ctrl-C to stop."
echo "---"

# threadtime format timestamps every line. With a resolved PID, show
# everything from that process (--pid is the only reliable way to scope
# logcat to one app by process, not by tag). AndroidRuntime:E is kept
# un-silenced either way so an uncaught crash on a background thread —
# exactly the qb2/eg2 crash shape this app has open — always shows, even
# if it's logged before/after the PID filter would otherwise catch it.
if [ -n "$PID" ]; then
  FILTER_ARGS="-v threadtime --pid=$PID *:V"
else
  echo "(couldn't resolve a PID — showing crash-only output; launch the app by hand and retry with -p for full per-process logs)"
  FILTER_ARGS="-v threadtime *:S AndroidRuntime:E"
fi

if [ -n "$OUT_FILE" ]; then
  eval "$LOGCAT_CMD $FILTER_ARGS" | tee "$OUT_FILE"
else
  eval "$LOGCAT_CMD $FILTER_ARGS"
fi
