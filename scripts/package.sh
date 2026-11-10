#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="$(cat "$ROOT/VERSION")"
STAGING="$ROOT/build/release/PadPaste"
if [[ ! -d "$ROOT/dist/PadPaste.app" ]]; then bash "$ROOT/scripts/build-macos.sh"; fi
rm -rf "$STAGING"
mkdir -p "$STAGING/dist"
for name in README.md LICENSE THIRD_PARTY_NOTICES.md VERSION android config docs scripts macos; do
  cp -R "$ROOT/$name" "$STAGING/$name"
done
python3 - "$STAGING" <<'PY_CLEAN'
import pathlib,shutil,sys
root=pathlib.Path(sys.argv[1])
for p in root.rglob('__pycache__'): shutil.rmtree(p)
for pattern in ['*.pyc','.DS_Store']:
    for p in root.rglob(pattern): p.unlink()
PY_CLEAN
ditto "$ROOT/dist/PadPaste.app" "$STAGING/dist/PadPaste.app"
ditto --norsrc --noextattr -c -k --keepParent "$STAGING" "$ROOT/dist/padpaste-v$VERSION-macos-arm64.zip"
(cd "$ROOT/dist" && shasum -a 256 "padpaste-v$VERSION-macos-arm64.zip" > SHA256SUMS)
printf 'Packaged %s\n' "$ROOT/dist/padpaste-v$VERSION-macos-arm64.zip"
