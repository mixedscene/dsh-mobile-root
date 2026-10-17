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

    def test_license_and_arbitrary_doc_file_links_materialized_before_purge(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime, build = self.prepare(root)
            self.link(runtime / "copyright", "../build-dev/copyright")
            names = ["changelog.gz", "Changes.gz", "NEWS.gz", "README.Debian", "unclassified.gz"]
            for name in names:
                self.link(runtime / name, "../build-dev/changelog.gz")
            docs.preserve(root)
            self.assertFalse((runtime / "copyright").is_symlink())
            self.assertTrue(all(not (runtime / name).is_symlink() for name in names))
            shutil.rmtree(build)
            report = docs.cleanup(root)
            self.assertEqual((runtime / "copyright").read_text(), "license must survive")
            self.assertEqual(report["restoredLicenseFiles"], 1)
            self.assertEqual(len(report["removedOptionalLinks"]), 0)
            self.assertTrue(all((runtime / name).read_bytes() == b"optional" for name in names))
            self.assertEqual(len(report["materializedFileLinksBeforePurge"]), 6)
            self.assertTrue((root / docs.LICENSES / "ubuntu-docs/runtime/copyright").is_file())

    def test_changes_is_an_explicit_optional_cleanup_name(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime, build = self.prepare(root)
            (runtime / "copyright").write_text("license")
            docs.preserve(root)
            self.link(runtime / "Changes.gz", "../build-dev/changelog.gz")
            shutil.rmtree(build)
            report = docs.cleanup(root)
            self.assertEqual(len(report["removedOptionalLinks"]), 1)
            self.assertEqual(report["removedOptionalLinks"][0]["path"], "usr/share/doc/runtime/Changes.gz")
            self.assertFalse((runtime / "Changes.gz").exists())

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

    def test_optional_resource_trim_preserves_runtime_utf8_zoneinfo_and_licenses(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime, build = self.prepare(root)
            (runtime / "copyright").write_text("Ubuntu license")
            retained = ["usr/lib/locale/C.utf8/LC_CTYPE", "usr/share/zoneinfo/UTC", "usr/bin/node", "opt/dsh-mobile/install/node_modules/ui/locale/zh.json", "usr/share/common-licenses/GPL-3"]
            for logical in retained:
                path = root / logical
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("retained")
            for logical in ["usr/share/locale", "usr/share/man", "usr/share/info"]:
                path = root / logical
                path.mkdir(parents=True, exist_ok=True)
                (path / "optional-resource").write_text("optional")
            (root / "usr/share/locale/LICENSE").write_text("license retained as inventory")
            self.link(root / "usr/share/locale/broken.mo", "missing-catalog")
            report = docs.trim_optional_resources(root)
            self.assertEqual(len(report["removedPaths"]), 3)
            self.assertTrue(all(not (root / logical).exists() for logical in report["removedPaths"]))
            self.assertTrue(all((root / logical).read_text() == "retained" for logical in retained))
            self.assertEqual((runtime / "copyright").read_text(), "Ubuntu license")
            self.assertEqual(len(report["preservedLicenseFiles"]), 1)
            self.assertEqual((root / report["preservedLicenseFiles"][0]["snapshot"]).read_text(), "license retained as inventory")

    def test_optional_resource_directory_alias_is_unlinked_not_followed_for_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime, build = self.prepare(root)
            locale = root / "usr/lib/locale/C.utf8"
            locale.mkdir(parents=True)
            (locale / "LC_CTYPE").write_text("C UTF8")
            self.link(root / "usr/share/locale", "/usr/lib/locale/C.utf8", directory=True)
            docs.trim_optional_resources(root)
            self.assertFalse((root / "usr/share/locale").is_symlink())
            self.assertEqual((locale / "LC_CTYPE").read_text(), "C UTF8")

    def test_manual_slave_alternative_is_removed_but_binary_alternative_is_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.prepare(root)
            alternatives = root / "etc/alternatives"
            alternatives.mkdir(parents=True)
            binary = root / "usr/bin/gawk"
            binary.parent.mkdir(parents=True)
            binary.write_text("runtime binary")
            manual = root / "usr/share/man/man1/gawk.1.gz"
            manual.parent.mkdir(parents=True)
            manual.write_text("optional manual")
            self.link(alternatives / "awk", "/usr/bin/gawk")
            self.link(alternatives / "awk.1.gz", "/usr/share/man/man1/gawk.1.gz")
            for language in ["de", "ja"]:
                localized = root / f"usr/share/man/{language}/man1/which.debianutils.1.gz"
                localized.parent.mkdir(parents=True)
                localized.write_text("optional translated manual")
                self.link(alternatives / f"which.{language}1.gz", f"/usr/share/man/{language}/man1/which.debianutils.1.gz")
            report = docs.trim_optional_resources(root)
            self.assertTrue((alternatives / "awk").is_symlink())
            self.assertEqual(binary.read_text(), "runtime binary")
            self.assertFalse((alternatives / "awk.1.gz").is_symlink())
            self.assertEqual(len(report["removedManualAliases"]), 3)
            self.assertEqual(report["removedManualAliases"][0], {"path": "etc/alternatives/awk.1.gz", "target": "/usr/share/man/man1/gawk.1.gz"})
            self.assertFalse((alternatives / "which.de1.gz").is_symlink())
            self.assertFalse((alternatives / "which.ja1.gz").is_symlink())


if __name__ == "__main__":
    unittest.main()
