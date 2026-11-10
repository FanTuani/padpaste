#!/usr/bin/env python3
"""Exercise the public receiver API; restore the original clipboard afterward."""
import base64
import json
import os
import pathlib
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
BINARY = ROOT / "dist/PadPaste.app/Contents/MacOS/PadPaste"


def run():
    with tempfile.TemporaryDirectory(prefix="padpaste-test-") as directory:
        home = pathlib.Path(directory)
        helper = home / "pasteboard"
        subprocess.run(["xcrun", "swiftc", str(ROOT / "tests/pasteboard.swift"), "-o", str(helper), "-framework", "AppKit"], check=True)
        snapshot = home / "clipboard.json"
        subprocess.run([str(helper), "snapshot", str(snapshot)], check=True)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        config = {"username": "test", "password": "local-test-token", "port": port, "flashEnabled": False}
        (home / "config.json").write_text(json.dumps(config))
        process = subprocess.Popen([str(BINARY)], env={**os.environ, "PADPASTE_HOME": str(home)}, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        auth = "Basic " + base64.b64encode(b"test:local-test-token").decode()
        image = (ROOT / "tests/fixtures/image.png").read_bytes()

        def request(name="Screenshot_Integration.png", body=image, authenticated=True, method="PUT", endpoint=None):
            headers = {"Authorization": auth} if authenticated else {}
            req = urllib.request.Request("http://127.0.0.1:{}{}".format(port, endpoint or "/screenshot/" + name), data=body if method=="PUT" else None, headers=headers, method=method)
            try:
                with urllib.request.urlopen(req, timeout=5) as response:
                    return response.status, response.read().decode()
            except urllib.error.HTTPError as error:
                return error.code, error.read().decode()

        try:
            deadline = time.monotonic() + 15
            while True:
                if process.poll() is not None:
                    raise RuntimeError(process.stderr.read().decode())
                try:
                    assert request(authenticated=False)[0] == 401
                    break
                except (urllib.error.URLError, ConnectionError):
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(0.1)
            original = json.loads(subprocess.check_output([str(helper), "inspect"], text=True))
            assert request(method="GET", endpoint="/health") == (200, "PadPaste ready")
            assert request(method="GET", endpoint="/health", authenticated=False)[0] == 401
            after_health = json.loads(subprocess.check_output([str(helper), "inspect"], text=True))
            assert after_health["changeCount"] == original["changeCount"]
            assert request(body=b"not an image")[0] == 422
            assert request(name="..%2Foutside.png")[0] == 400
            assert request()[0] == 200
            info = json.loads(subprocess.check_output([str(helper), "inspect"], text=True))
            assert info["width"] == 640 and info["height"] == 200
            assert base64.b64decode(info["png"]) == image
            assert info["fileURL"] == ""
            assert not (home / "received").exists()
            assert request()[1] == "duplicate"
            unchanged = json.loads(subprocess.check_output([str(helper), "inspect"], text=True))
            assert unchanged["changeCount"] == info["changeCount"]
            assert request(name="Screenshot_Second.png")[0] == 200
            assert request(name="Screenshot_Third.png")[0] == 200
            assert not (home / "received").exists()
            assert not any(p.suffix in (".png", ".jpg", ".jpeg", ".webp", ".tiff") for p in home.rglob("*"))
            print("PASS: authentication, complete images, filename handling, clipboard contents, duplicate suppression, memory-only clipboard without image files")
        finally:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
            subprocess.run([str(helper), "restore", str(snapshot)], check=True)


if __name__ == "__main__":
    run()
