import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("configure", ROOT / "scripts/configure.py")
configure = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configure)


class PairingTests(unittest.TestCase):
    def test_both_devices_share_credentials_and_custom_port(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            mac, android = configure.configure(root, "example.local", 5040)
            self.assertEqual(mac["password"], android["password"])
            self.assertEqual(mac["username"], android["username"])
            self.assertEqual(android["server"], "http://example.local:5040")
            self.assertGreaterEqual(len(mac["password"]), 32)
            for name in ["config.json", "android-config.json"]:
                self.assertEqual((root / name).stat().st_mode & 0o777, 0o600)

    def test_upgrade_preserves_pairing_and_user_options(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            first, _ = configure.configure(root, "old.local", 5040)
            first["flashDuration"] = 0.2
            configure.write_private(root / "config.json", first)
            second, client = configure.configure(root, "new.local")
            self.assertEqual(first["password"], second["password"])
            self.assertEqual(second["flashDuration"], 0.2)
            self.assertEqual(client["server"], "http://new.local:5040")
            rotated, _ = configure.configure(root, "new.local", reset_token=True)
            self.assertNotEqual(second["password"], rotated["password"])

    def test_invalid_port_does_not_write_a_pairing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with self.assertRaises(ValueError):
                configure.configure(root, "example.local", 65536)
            self.assertFalse((root / "config.json").exists())


if __name__ == "__main__":
    unittest.main()
