"""Small host-independent archive boundary regressions; no Android emulation."""
import importlib.util
import json
import os
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

    def test_hardlinks_only_point_back_to_regular_members(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            versions = root / "opt/dsh-mobile/tool-versions.json"
            versions.parent.mkdir(parents=True)
            versions.write_text('{}')
            first = root / "a-file"
            first.write_bytes(b"shared git binary")
            os.link(first, root / "b-hardlink")
            os.link(first, root / "c-hardlink")
            output = Path(temporary) / "out"
            packer.pack(root, output, "links")
            regular = set()
            links = []
            with tarfile.open(output / "runtime.tar.gz") as archive:
                for entry in archive:
                    if entry.isfile():
                        regular.add(entry.name)
                    elif entry.islnk():
                        self.assertIn(entry.linkname, regular)
                        self.assertFalse(entry.linkname.startswith("/"))
                        self.assertNotIn("..", entry.linkname.split("/"))
                        self.assertEqual(entry.size, 0)
                        links.append(entry)
                    else:
                        self.assertTrue(entry.isdir())
            self.assertEqual(len(links), 2)
            self.assertEqual([entry.linkname for entry in links], ["a-file", "a-file"])
            manifest = json.loads((output / "runtime-manifest.json").read_text())
            self.assertEqual(manifest["unpackedBytes"], len(b"shared git binary") + 2)
            self.assertEqual(manifest["hardLinks"], 2)

    def test_symbolic_file_alias_materializes_as_internal_hardlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            versions = root / "opt/dsh-mobile/tool-versions.json"
            versions.parent.mkdir(parents=True)
            versions.write_text('{}')
            (root / "a-file").write_bytes(b"data")
            try:
                (root / "z-link").symlink_to("/a-file")
            except OSError as error:
                self.skipTest(f"host cannot create symlinks: {error}")
            output = Path(temporary) / "out"
            packer.pack(root, output, "symbolic-alias")
            with tarfile.open(output / "runtime.tar.gz") as archive:
                alias = archive.getmember("z-link")
                self.assertTrue(alias.islnk())
                self.assertEqual(alias.linkname, "a-file")
                self.assertFalse(any(entry.issym() for entry in archive))

    def test_audit_reports_all_invalid_links_before_archive_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "root"
            root.mkdir()
            try:
                (root / "broken-a").symlink_to("missing-a")
                (root / "broken-b").symlink_to("/missing-b")
                (root / "escape").symlink_to("../outside")
            except OSError as error:
                self.skipTest(f"host cannot create symlinks: {error}")
            output = Path(temporary) / "out"
            with self.assertRaises(ValueError) as failure:
                packer.pack(root, output, "audit")
            for name in ["broken-a", "broken-b", "escape"]:
                self.assertIn(name, str(failure.exception))
            report = json.loads((output / "runtime-symlink-audit.json").read_text())
            self.assertEqual(len(report["invalidLinks"]), 3)
            self.assertFalse((output / "runtime.tar.gz").exists())


if __name__ == "__main__":
    unittest.main()
