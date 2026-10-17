"""Focused final-APK packaging regression checks; no Android SDK required."""

import gzip
import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest
import warnings
import zipfile


spec = importlib.util.spec_from_file_location(
    "verify_apk_runtime", pathlib.Path(__file__).with_name("verify-apk-runtime.py")
)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ApkRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = pathlib.Path(self.temp.name)
        self.payload = gzip.compress(b"runtime fixture")
        self.manifest = json.dumps({
            "format": 1, "size": len(self.payload),
            "sha256": hashlib.sha256(self.payload).hexdigest()
        }).encode()
        self.manifest_path = self.directory / "runtime-manifest.json"
        self.manifest_path.write_bytes(self.manifest)

    def check(self, entries, compression=zipfile.ZIP_STORED):
        apk = self.directory / "fixture.apk"
        with zipfile.ZipFile(apk, "w", compression=compression) as archive:
            archive.writestr(checker.MANIFEST, self.manifest)
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                for name, data in entries:
                    archive.writestr(name, data)
        checker.verify(apk, self.manifest_path)

    def test_preserves_gzip_payload(self):
        self.check([(checker.ARCHIVE, self.payload)])

    def test_rejects_agp_expanded_tar(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.check([("assets/runtime/runtime.tar", gzip.decompress(self.payload))])

    def test_rejects_duplicate_payload(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.check([(checker.ARCHIVE, self.payload)] * 2)

    def test_rejects_changed_payload(self):
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.check([(checker.ARCHIVE, self.payload[:-1] + b"\xff")])

    def test_rejects_non_gzip_payload(self):
        with self.assertRaisesRegex(ValueError, "gzip stream"):
            self.check([(checker.ARCHIVE, b"x" * len(self.payload))])

    def test_rejects_wrong_size(self):
        with self.assertRaisesRegex(ValueError, "size mismatch"):
            self.check([(checker.ARCHIVE, self.payload + b"extra")])

    def test_rejects_changed_manifest(self):
        self.manifest_path.write_bytes(b"{}")
        with self.assertRaisesRegex(ValueError, "manifest differs"):
            self.check([(checker.ARCHIVE, self.payload)])

    def test_rejects_zip_recompression(self):
        with self.assertRaisesRegex(ValueError, "ZIP recompression"):
            self.check([(checker.ARCHIVE, self.payload)], zipfile.ZIP_DEFLATED)


if __name__ == "__main__":
    unittest.main()
