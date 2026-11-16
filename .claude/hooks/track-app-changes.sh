#!/usr/bin/env bash
# PreToolUse/PostToolUse hook: records when a conversation changes the app, so the Stop hook
# (deploy-to-phone.sh) only deploys for conversations that actually changed it.
#   Edit, Write, MultiEdit, NotebookEdit: marks the conversation if the edited file is part of the app.
#   Bash, PowerShell: compares the app fingerprint before and after the command.
# Never blocks a tool: always exits 0.
set -u
cd "$(dirname "$0")/../.." || exit 0
. .claude/hooks/app-fingerprint.sh

hook_input="$(cat)"
session="$(safe_name "$(hook_field session_id)")"
[ -n "$session" ] || exit 0
event="$(hook_field hook_event_name)"
tool="$(hook_field tool_name)"
mkdir -p "$state_dir/sessions"
marker="$state_dir/sessions/$session.changed"

case "$tool" in
    Edit|Write|MultiEdit|NotebookEdit)
        [ "$event" = "PostToolUse" ] || exit 0
        file="$(hook_field tool_input.file_path)"
        [ -n "$file" ] || file="$(hook_field tool_input.notebook_path)"
        [ -n "$file" ] || exit 0
        command -v cygpath >/dev/null && file="$(cygpath -u "$file")" # Windows paths from Git Bash
        rel="$(realpath -m --relative-to=. "$file" 2>/dev/null)" || exit 0
        for p in "${app_paths[@]}"; do
            case "$rel" in "$p"|"$p"/*) touch "$marker"; exit 0 ;; esac
        done
        ;;
    Bash|PowerShell)
        call="$(safe_name "$(hook_field tool_use_id)")"
        before="$state_dir/sessions/$session.$call.before"
        if [ "$event" = "PreToolUse" ]; then
            app_fingerprint >"$before"
        elif [ -f "$before" ]; then
            [ "$(cat "$before")" = "$(app_fingerprint)" ] || touch "$marker"
            rm -f "$before"
        fi
        ;;
esac
exit 0
