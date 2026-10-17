"""Check the actual APK runtime assets after Android asset merging."""

import argparse
import hashlib
import json
import pathlib
import zipfile


ARCHIVE = "assets/runtime/runtime.payload"
MANIFEST = "assets/runtime/runtime-manifest.json"


def verify(apk: pathlib.Path, expected_manifest: pathlib.Path) -> None:
    expected = expected_manifest.read_bytes()
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        for required in (ARCHIVE, MANIFEST):
            if names.count(required) != 1:
                raise ValueError(f"APK must contain exactly one {required}")
        if any(name in names for name in (
            "assets/runtime/runtime.tar", "assets/runtime/runtime.tar.gz"
        )):
            raise ValueError("APK contains a legacy or auto-expanded runtime asset")
        info = archive.getinfo(MANIFEST)
        if info.file_size > 65536 or archive.read(info) != expected:
            raise ValueError("APK runtime manifest differs from the verified source manifest")
        manifest = json.loads(expected)
        if manifest.get("format") != 1:
            raise ValueError("Unsupported runtime manifest format")
        info = archive.getinfo(ARCHIVE)
        if info.file_size != manifest.get("size"):
            raise ValueError("APK runtime payload size mismatch")
        if info.compress_type != zipfile.ZIP_STORED:
            raise ValueError("Gzip runtime payload must be stored without ZIP recompression")
        digest = hashlib.sha256()
        with archive.open(info) as stream:
            header = stream.read(3)
            if header != b"\x1f\x8b\x08":
                raise ValueError("APK runtime payload is not the original gzip stream")
            digest.update(header)
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != manifest.get("sha256"):
            raise ValueError("APK runtime payload SHA-256 mismatch")
        print(f"Verified {ARCHIVE}: {info.file_size} bytes, SHA-256 {digest.hexdigest()}")
        print("Final APK contains the unchanged gzip runtime and pinned manifest.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    args = parser.parse_args()
    verify(args.apk, args.manifest)
