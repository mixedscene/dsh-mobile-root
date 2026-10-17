"""License-preservation and tightly scoped dangling-doc cleanup regressions."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("runtime_docs", Path(__file__).with_name("runtime-docs.py"))
docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docs)


class DocumentationTests(unittest.TestCase):
    def link(self, path, target, directory=False):
        try:
            path.symlink_to(target, target_is_directory=directory)
        except OSError as error:
            self.skipTest(f"host cannot create symlinks: {error}")

    def prepare(self, root):
        licenses = root / docs.LICENSES
        licenses.mkdir(parents=True)
        (licenses / "ubuntu-packages.tsv").write_text("runtime:arm64\t1.0\truntime\t1.0\n")
        runtime = root / "usr/share/doc/runtime"
        runtime.mkdir(parents=True)
        build = root / "usr/share/doc/build-dev"
        build.mkdir()
        (build / "copyright").write_text("license must survive")
        (build / "changelog.gz").write_bytes(b"optional")
        return runtime, build

    def test_license_materialized_before_purge_and_optional_docs_recorded(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime, build = self.prepare(root)
            self.link(runtime / "copyright", "../build-dev/copyright")
            for name in ["changelog.gz", "NEWS.gz", "README.Debian"]:
                self.link(runtime / name, "../build-dev/changelog.gz")
            docs.preserve(root)
            self.assertFalse((runtime / "copyright").is_symlink())
            shutil.rmtree(build)
            report = docs.cleanup(root)
            self.assertEqual((runtime / "copyright").read_text(), "license must survive")
            self.assertEqual(report["restoredLicenseFiles"], 1)
            self.assertEqual(len(report["removedOptionalLinks"]), 3)
            self.assertFalse((runtime / "changelog.gz").exists())
            self.assertTrue((root / docs.LICENSES / "ubuntu-docs/runtime/copyright").is_file())

    def test_purged_doc_directory_alias_license_is_restored(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime, build = self.prepare(root)
            runtime.rmdir()
            self.link(runtime, "/usr/share/doc/build-dev", directory=True)
            docs.preserve(root)
            shutil.rmtree(build)
            report = docs.cleanup(root)
            self.assertTrue(runtime.is_dir())
            self.assertFalse(runtime.is_symlink())
            self.assertEqual((runtime / "copyright").read_text(), "license must survive")
            self.assertIn("usr/share/doc/runtime", report["restoredLicenseDirectories"])

    def test_unknown_or_protected_dangling_doc_is_never_deleted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime, build = self.prepare(root)
            (runtime / "copyright").write_text("license")
            docs.preserve(root)
            self.link(runtime / "unclassified.gz", "missing")
            with self.assertRaisesRegex(ValueError, "unclassified.*runtime/unclassified.gz"):
                docs.cleanup(root)
            self.assertTrue((runtime / "unclassified.gz").is_symlink())
            (runtime / "unclassified.gz").unlink()
            self.link(runtime / "LICENSE.missing", "missing")
            with self.assertRaisesRegex(ValueError, "not removed.*LICENSE.missing"):
                docs.cleanup(root)
            self.assertTrue((runtime / "LICENSE.missing").is_symlink())

    def test_binary_dangling_link_remains_fatal_with_logical_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime, build = self.prepare(root)
            (runtime / "copyright").write_text("license")
            binary = root / "usr/bin"
            binary.mkdir(parents=True)
            self.link(binary / "broken", "/no-such-runtime-binary")
            docs.preserve(root)
            docs.cleanup(root)
            with self.assertRaisesRegex(ValueError, "logical path usr/bin/broken"):
                docs.packer.pack(root, root.parent / "out", "test")


if __name__ == "__main__":
    unittest.main()
