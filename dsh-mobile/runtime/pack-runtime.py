"""Materialize a confined rootfs with backward-only internal file hardlinks."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import tarfile


def resolve_confined(root, relative):
    """Resolve POSIX absolute links against the image root, never the build host."""
    pending = list(PurePosixPath(relative).parts)
    parts = []
    hops = 0
    while pending:
        part = pending.pop(0)
        if part in ("", ".", "/"):
            continue
        if part == "..":
            if not parts:
                raise ValueError(f"link escapes root: {relative}")
            parts.pop()
            continue
        current = root.joinpath(*parts, part)
        if current.is_symlink():
            hops += 1
            if hops > 64:
                raise ValueError(f"link cycle: {relative}")
            target = os.readlink(current)
            if target.startswith("/"):
                parts = []
            pending = list(PurePosixPath(target).parts) + pending
        else:
            parts.append(part)
    return root.joinpath(*parts)


def pack(root, output, build_id):
    """Flatten symbolic links and deduplicate physical files into safe backward hardlinks."""
    root = root.resolve(strict=True)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / "runtime.tar.gz"
    total = 0
    files = 0
    entries = 0
    hardlinks = 0
    archived_files = {}
    with tarfile.open(archive, "w:gz", format=tarfile.PAX_FORMAT, compresslevel=6) as tar:
        def visit(relative, ancestors):
            nonlocal total, files, entries, hardlinks
            entries += 1
            if entries > 500_000:
                raise ValueError("rootfs exceeds entry budget")
            try:
                path = resolve_confined(root, relative)
                metadata = path.stat()
            except (OSError, ValueError) as error:
                raise ValueError(f"cannot archive logical path {relative}: {error}") from error
            identity = (metadata.st_dev, metadata.st_ino)
            if stat.S_ISDIR(metadata.st_mode):
                if identity in ancestors:
                    raise ValueError(f"directory link cycle: {relative}")
                info = tarfile.TarInfo(relative)
                info.type = tarfile.DIRTYPE
                info.mode = stat.S_IMODE(metadata.st_mode) & 0o777
                tar.addfile(info)
                for child in sorted(path.iterdir(), key=lambda entry: entry.name):
                    visit(f"{relative}/{child.name}", ancestors | {identity})
            elif stat.S_ISREG(metadata.st_mode):
                first = archived_files.get(identity)
                if first is not None:
                    info = tarfile.TarInfo(relative)
                    info.type = tarfile.LNKTYPE
                    info.linkname = first
                    info.mode = stat.S_IMODE(metadata.st_mode) & 0o777
                    tar.addfile(info)
                    hardlinks += 1
                    return
                total += metadata.st_size
                files += 1
                if total > 4 * 1024**3 or files > 500_000:
                    raise ValueError("rootfs exceeds expansion budget")
                info = tarfile.TarInfo(relative)
                info.size = metadata.st_size
                info.mode = stat.S_IMODE(metadata.st_mode) & 0o777
                with path.open("rb") as source:
                    tar.addfile(info, source)
                # Only a completed regular member may become a later link target.
                archived_files[identity] = relative
            else:
                raise ValueError(f"unsupported special entry: {relative}")

        for path in sorted(root.iterdir(), key=lambda entry: entry.name):
            visit(path.name, set())
    checksum = hashlib.sha256()
    with archive.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(chunk)
    digest = checksum.hexdigest()
    manifest = {
        "format": 1, "buildId": "mobile-root-" + build_id,
        "sha256": digest, "size": archive.stat().st_size,
        "unpackedBytes": total, "regularFiles": files,
        "entries": entries, "hardLinks": hardlinks,
        "archiveTypes": ["directory", "regular", "backward-internal-hardlink"],
        "architecture": "aarch64", "distribution": "Ubuntu noble",
        "tools": json.loads((root / "opt/dsh-mobile/tool-versions.json").read_text()),
        "profile": "mobile", "entrypoint": "/opt/dsh-mobile/start-mobile.sh",
        "licenseInventory": "/opt/dsh-mobile/licenses/SOURCES.txt",
        "documentationCleanup": "/opt/dsh-mobile/licenses/dangling-optional-docs.json",
        "limits": ["device root and SELinux unverified", "activity coverage incomplete; automatic freeze forbidden", "published CLI includes unused transitive packages"],
    }
    (output / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output / "runtime-sha256.txt").write_text(f"{digest}  runtime.tar.gz\n")
    (output / "runtime-validation.txt").write_text("Cloud smoke required before packing. No Android device/API compatibility claim.\n")


if __name__ == "__main__":
    pack(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
