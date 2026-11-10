#!/usr/bin/env python3
"""Create local pairing files; preserve the existing pairing on upgrades."""
import argparse
import json
import os
import pathlib
import secrets
import socket
import subprocess


def write_private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    path.chmod(0o600)


def configure(directory, host, port=None, reset_token=False):
    directory.mkdir(parents=True, exist_ok=True)
    directory.chmod(0o700)
    path = directory / "config.json"
    config = json.loads(path.read_text()) if path.exists() else {}
    config.setdefault("username", "padpaste")
    if reset_token or not config.get("password"):
        config["password"] = secrets.token_urlsafe(32)
    config.setdefault("port", 5034)
    if port is not None:
        config["port"] = port
    if not 1 <= int(config["port"]) <= 65535:
        raise ValueError("port must be between 1 and 65535")
    config.setdefault("flashEnabled", True)
    config.setdefault("flashDuration", 0.3)
    config.setdefault("cacheLimit", 20)
    write_private(path, config)
    client = {
        "server": "http://{}:{}".format(host, config["port"]),
        "username": config["username"],
        "password": config["password"],
        "screenshotDirectory": "/sdcard/DCIM/Screenshots",
        "startOnBoot": True,
        "diagnostics": False,
    }
    write_private(directory / "android-config.json", client)
    return config, client


def default_host():
    try:
        result = subprocess.check_output(["scutil", "--get", "LocalHostName"], text=True).strip()
        return result + ".local"
    except (OSError, subprocess.CalledProcessError):
        return socket.gethostname()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--home", type=pathlib.Path, default=pathlib.Path(os.environ.get("PADPASTE_HOME", pathlib.Path.home() / "Library/Application Support/PadPaste")))
    parser.add_argument("--host", help="Mac hostname or LAN address visible to Android")
    parser.add_argument("--port", type=int)
    parser.add_argument("--reset-token", action="store_true")
    args = parser.parse_args()
    configure(args.home, args.host or default_host(), args.port, args.reset_token)
    print("Mac settings: " + str(args.home / "config.json"))
    print("Copy to Android: " + str(args.home / "android-config.json"))


if __name__ == "__main__":
    main()
