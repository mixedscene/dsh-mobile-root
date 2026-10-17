"""Small host-independent archive boundary regressions; no Android emulation."""
import importlib.util
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("packer", Path(__file__).with_name("pack-runtime.py"))
packer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packer)


class ArchiveTests(unittest.TestCase):
    def test_regular_archive_and_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            versions = root / "opt/dsh-mobile/tool-versions.json"
            versions.parent.mkdir(parents=True)
            versions.write_text('{"node":"v24.11.0"}')
            output = Path(temporary) / "out"
            packer.pack(root, output, "unit")
            manifest = json.loads((output / "runtime-manifest.json").read_text())
            self.assertEqual(manifest["format"], 1)
            self.assertEqual(manifest["buildId"], "mobile-root-unit")
            self.assertEqual(len(manifest["sha256"]), 64)
            with tarfile.open(output / "runtime.tar.gz") as archive:
                self.assertTrue(all(entry.isdir() or entry.isfile() for entry in archive))

    def test_parent_escape_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, "escapes"):
                packer.resolve_confined(Path(temporary), "../outside")

    def test_absolute_link_confined_and_cycle_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "inside").write_text("ok")
            try:
                (root / "link").symlink_to("/inside")
                (root / "cycle").symlink_to("cycle")
            except OSError as error:
                self.skipTest(f"host cannot create symlinks: {error}")
            self.assertEqual(packer.resolve_confined(root, "link"), root / "inside")
            with self.assertRaisesRegex(ValueError, "cycle"):
                packer.resolve_confined(root, "cycle")


if __name__ == "__main__":
    unittest.main()
