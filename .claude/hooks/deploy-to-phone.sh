#!/usr/bin/env bash
# Stop hook: install the play debug build on a connected physical phone (emulators are skipped),
# but only when this conversation changed the app. track-app-changes.sh marks a conversation when
# one of its tool calls changes app sources or build files; changes made by other conversations,
# an editor, or to files outside the app (ROADMAP.md, README.md, .claude/) don't trigger an install.
# Exit 2 on a failed install so Claude is woken to report it (the hook runs with asyncRewake).
# A failure is reported once; the same failure again stays quiet until the app changes.
set -u
cd "$(dirname "$0")/../.." || exit 0
. .claude/hooks/app-fingerprint.sh

hook_input="$(cat)"
session="$(safe_name "$(hook_field session_id)")"
[ -n "$session" ] || exit 0
marker="$state_dir/sessions/$session.changed"
[ -f "$marker" ] || exit 0

ADB="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$(command -v adb)" || exit 0

serial="$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')"
[ -n "$serial" ] || exit 0

fingerprint="$(app_fingerprint)"
stamp="$state_dir/$(safe_name "$serial")"
if [ "$(cat "$stamp" 2>/dev/null)" = "$fingerprint" ]; then
    rm -f "$marker" # this exact app is already on the phone
    exit 0
fi

# One install at a time; the marker stays, so a later turn retries.
lock="$state_dir/lock"
mkdir "$lock" 2>/dev/null || exit 0
trap 'rmdir "$lock"' EXIT

export JAVA_HOME="${JAVA_HOME_MURMUR:-$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2}"
log="$state_dir/last-install.log"
failed="$state_dir/sessions/$session.failed"
if ANDROID_SERIAL="$serial" ./gradlew :app:installPlayDebug -q >"$log" 2>&1; then
    printf '%s' "$fingerprint" >"$stamp"
    rm -f "$marker" "$failed"
    exit 0
fi
[ "$(cat "$failed" 2>/dev/null)" = "$fingerprint" ] && exit 0
printf '%s' "$fingerprint" >"$failed"
echo "Auto-deploy to phone $serial failed. Last lines of $log:" >&2
grep -vE '^w: |warning:' "$log" | tail -15 >&2
exit 2
