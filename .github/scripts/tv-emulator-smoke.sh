#!/usr/bin/env bash
# Runs only with an ephemeral Android TV emulator and an anonymous test install.
set -euo pipefail

apk='tv-apk/tvApp-debug.apk'
package='com.starlit.tv.debug'
activity='com.starlit.tv.TelevisionActivity'

test -s "$apk"
adb install -r "$apk"
adb shell pm path "$package" | grep -q '^package:'

# Do not use real user accounts or persistent device state in the smoke test.
adb logcat -c
adb shell am start -W -n "$package/$activity"

running() { adb shell pidof "$package" | tr -d '\r' | grep -Eq '^[0-9]+'; }
for attempt in $(seq 1 20); do
  if running; then break; fi
  sleep 2
done
if ! running; then
  echo '::error::Starlit TV did not start on the emulator.'
  exit 1
fi

# Cover D-pad, select, Back and a return to the launcher while no touch input
# is available. Startup / focus handling must not crash the process.
adb shell input keyevent KEYCODE_DPAD_DOWN
adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_DPAD_CENTER
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_DPAD_UP
# Back can correctly finish the activity at a root/onboarding screen. Relaunch
# before checking liveness, then look for actual fatal crashes in the log.
adb shell am start -W -n "$package/$activity"
sleep 5

adb exec-out screencap -p > tv-smoke.png || true
if ! running; then
  echo '::error::Starlit TV exited during remote navigation.'
  adb logcat -d -b crash -s AndroidRuntime:E | tail -n 120
  exit 1
fi
if adb logcat -d -b crash -s AndroidRuntime:E | grep -q "Process: $package,"; then
  echo '::error::Starlit TV crashed during startup or remote navigation.'
  adb logcat -d -b crash -s AndroidRuntime:E | tail -n 120
  exit 1
fi
echo 'Starlit TV launched and survived D-pad/Back navigation on Android TV.'
