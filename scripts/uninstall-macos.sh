#!/bin/bash
set -euo pipefail
LABEL="io.github.fantuani.padpaste"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
rm -rf "$HOME/Applications/PadPaste.app"
if [[ "${1:-}" == "--purge" ]]; then
  rm -rf "$HOME/Library/Application Support/PadPaste"
fi
printf 'PadPaste uninstalled.\n'
