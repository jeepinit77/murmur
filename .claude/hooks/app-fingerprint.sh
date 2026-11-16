# Sourced by the deploy hooks. Run from the project root.

# What goes into the APK: app and library modules, plus the Gradle build setup.
app_paths=(app core gradle build.gradle.kts settings.gradle.kts gradle.properties)

state_dir=".claude/.deploy"

# Fingerprint of those paths only: committed trees, uncommitted changes and untracked (non-ignored) files.
app_fingerprint() {
    {
        git ls-tree HEAD -- "${app_paths[@]}"
        git diff HEAD -- "${app_paths[@]}"
        git ls-files -o --exclude-standard -z -- "${app_paths[@]}" | xargs -0 -r cat
    } 2>/dev/null | sha1sum | cut -d' ' -f1
}

# Prints one field of the hook's JSON input (stdin, saved in $hook_input), e.g. hook_field session_id.
hook_field() {
    printf '%s' "$hook_input" | python -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
v = d
for k in sys.argv[1].split("."):
    v = v.get(k) if isinstance(v, dict) else None
print(v if isinstance(v, str) else "")
' "$1"
}

# Session IDs and tool-use IDs are used in file names; keep only safe characters.
safe_name() { printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_'; }
