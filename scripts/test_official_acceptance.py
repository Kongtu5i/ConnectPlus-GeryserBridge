"""Regression for acceptance builds never reusing an existing delivery directory."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

module_path = Path(__file__).with_name("build-official-acceptance.py")
spec = importlib.util.spec_from_file_location("official_acceptance", module_path)
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class PackageStagingTest(unittest.TestCase):
    def test_each_build_starts_empty_and_excludes_previous_plugins_and_secrets(self):
        with tempfile.TemporaryDirectory(prefix="cp-official-staging-test-") as work:
            root = Path(work)
            first = builder.create_package_directory(root)
            (first / "plugins").mkdir()
            (first / "plugins/obsolete-patched-host.jar").write_bytes(b"old-host")
            (first / "secret.key").write_bytes(b"must-not-ship")
            second = builder.create_package_directory(root)
            self.assertNotEqual(first, second)
            self.assertEqual([], list(second.iterdir()))
            self.assertTrue(first.is_dir())
            self.assertEqual(b"must-not-ship", (first / "secret.key").read_bytes())


if __name__ == "__main__":
    unittest.main()
