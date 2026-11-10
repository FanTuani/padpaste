#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/dist/PadPaste.app"
SDK="$(xcrun --sdk macosx --show-sdk-path)"
mkdir -p "$ROOT/build" "$APP/Contents/MacOS"
xcrun swiftc -O -sdk "$SDK" -target arm64-apple-macosx12.0 -file-prefix-map "$ROOT=." \
  "$ROOT/macos/main.swift" "$ROOT/macos/AppConfiguration.swift" "$ROOT/macos/FastScreenshotReceiver.swift" \
  -o "$APP/Contents/MacOS/PadPaste" \
  -framework AppKit -framework QuartzCore -framework Network -framework ImageIO
python3 - "$ROOT" <<'PY'
import pathlib,plistlib,sys
root=pathlib.Path(sys.argv[1])
info={"CFBundleExecutable":"PadPaste","CFBundleIdentifier":"io.github.fantuani.padpaste","CFBundleName":"PadPaste","CFBundleDisplayName":"PadPaste","CFBundleVersion":root.joinpath('VERSION').read_text().strip(),"CFBundleShortVersionString":root.joinpath('VERSION').read_text().strip(),"CFBundlePackageType":"APPL","LSUIElement":True,"NSHighResolutionCapable":True,"LSMinimumSystemVersion":"12.0","NSLocalNetworkUsageDescription":"Receive screenshots from your Android device over Wi-Fi."}
(root/'dist/PadPaste.app/Contents/Info.plist').write_bytes(plistlib.dumps(info))
PY
codesign --force --sign - "$APP"
printf 'Built %s\n' "$APP"
