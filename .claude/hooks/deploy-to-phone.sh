#!/usr/bin/env bash
# Stop hook: install the play debug build on a connected physical phone (emulators are skipped),
# but only when the app's sources or build files changed since the last successful install on that
# phone. Changes elsewhere (ROADMAP.md, README.md, .claude/) don't trigger an install.
# Exit 2 on a failed install so Claude is woken to report it (the hook runs with asyncRewake).
set -u
cd "$(dirname "$0")/../.." || exit 0

ADB="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$(command -v adb)" || exit 0

serial="$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')"
[ -n "$serial" ] || exit 0

# What goes into the APK: app and library modules, plus the Gradle build setup.
app_paths=(app core gradle build.gradle.kts settings.gradle.kts gradle.properties)

# Fingerprint of those paths only: committed trees, uncommitted changes and untracked (non-ignored) files.
fingerprint="$({
    git ls-tree HEAD -- "${app_paths[@]}"
    git diff HEAD -- "${app_paths[@]}"
    git ls-files -o --exclude-standard -z -- "${app_paths[@]}" | xargs -0 -r cat
} 2>/dev/null | sha1sum | cut -d' ' -f1)"

stamp_dir=".claude/.deploy"
mkdir -p "$stamp_dir"
stamp="$stamp_dir/$(printf '%s' "$serial" | tr -c 'A-Za-z0-9._-' '_')"
[ "$(cat "$stamp" 2>/dev/null)" = "$fingerprint" ] && exit 0

# One install at a time; a later turn picks up anything this one missed.
lock="$stamp_dir/lock"
mkdir "$lock" 2>/dev/null || exit 0
trap 'rmdir "$lock"' EXIT

export JAVA_HOME="${JAVA_HOME_MURMUR:-$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2}"
log="$stamp_dir/last-install.log"
if ANDROID_SERIAL="$serial" ./gradlew :app:installPlayDebug -q >"$log" 2>&1; then
    printf '%s' "$fingerprint" >"$stamp"
    exit 0
fi
echo "Auto-deploy to phone $serial failed. Last lines of $log:" >&2
grep -vE '^w: |warning:' "$log" | tail -15 >&2
exit 2
