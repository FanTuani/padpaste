#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$HOME/Applications/PadPaste.app"
SUPPORT="$HOME/Library/Application Support/PadPaste"
LABEL="io.github.fantuani.padpaste"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
if [[ ! -d "$ROOT/dist/PadPaste.app" ]]; then
  bash "$ROOT/scripts/build-macos.sh"
fi
python3 "$ROOT/scripts/configure.py" "$@"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
mkdir -p "$HOME/Applications" "$HOME/Library/LaunchAgents"
if [[ -d "$APP" ]]; then rm -rf "$APP"; fi
ditto "$ROOT/dist/PadPaste.app" "$APP"
python3 - "$APP" "$PLIST" "$SUPPORT" <<'PY'
import pathlib,plistlib,sys
app,plist,support=map(pathlib.Path,sys.argv[1:])
plist.write_bytes(plistlib.dumps({"Label":"io.github.fantuani.padpaste","ProgramArguments":[str(app/'Contents/MacOS/PadPaste')],"RunAtLoad":True,"KeepAlive":True,"ThrottleInterval":10,"StandardOutPath":str(support/'receiver.log'),"StandardErrorPath":str(support/'receiver.log')}))
PY
launchctl bootstrap "gui/$(id -u)" "$PLIST"
printf '\nPadPaste installed. Android pairing file:\n%s\n' "$SUPPORT/android-config.json"
