"""Preserve Ubuntu license files before purging build packages; clean only optional docs."""
import hashlib
import importlib.util
import json
import os
import posixpath
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile

spec = importlib.util.spec_from_file_location("runtime_packer", Path(__file__).with_name("pack-runtime.py"))
packer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packer)
DOC = "usr/share/doc"
LICENSES = "opt/dsh-mobile/licenses"
PROTECTED = re.compile(r"^(copyright|licen[cs]e|copying|notice)(?:$|[._-])", re.I)
OPTIONAL = re.compile(r"^(changelog|changes|news|readme)(?:$|[._-])", re.I)


def walk(root, logical=DOC, ancestors=frozenset()):
    """Traverse logical doc paths with the same confined link semantics as packing."""
    path = packer.resolve_confined(root, logical)
    try:
        metadata = path.stat()
    except FileNotFoundError:
        yield logical, None
        return
    identity = (metadata.st_dev, metadata.st_ino)
    if stat.S_ISDIR(metadata.st_mode):
        if identity in ancestors:
            raise ValueError(f"documentation directory cycle: {logical}")
        for child in sorted(path.iterdir(), key=lambda entry: entry.name):
            yield from walk(root, f"{logical}/{child.name}", ancestors | {identity})
    else:
        yield logical, path


def write_regular(parent, name, content, mode=0o644):
    """Atomically replace the leaf itself, never follow a destination symlink."""
    with tempfile.NamedTemporaryFile(dir=parent, prefix=".license-", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(content)
    os.chmod(temporary, mode & 0o777)
    os.replace(temporary, parent / name)


def preserve(root):
    """Snapshot licenses and materialize every valid document file link before purge."""
    root = root.resolve(strict=True)
    destination = root / LICENSES / "ubuntu-docs"
    destination.mkdir(parents=True, exist_ok=True)
    records = []
    materialized = []
    for logical, resolved in walk(root):
        if resolved is not None and resolved.is_file():
            parent = packer.resolve_confined(root, str(PurePosixPath(logical).parent))
            leaf = parent / PurePosixPath(logical).name
            if leaf.is_symlink():
                content = resolved.read_bytes()
                target = os.readlink(leaf)
                write_regular(parent, leaf.name, content, stat.S_IMODE(resolved.stat().st_mode))
                materialized.append({"path": logical, "target": target, "sha256": hashlib.sha256(content).hexdigest()})
                resolved = leaf
        if not PROTECTED.match(PurePosixPath(logical).name):
            continue
        if resolved is None or not resolved.is_file():
            raise ValueError(f"missing or nonregular license before purge: {logical}")
        content = resolved.read_bytes()
        relative = PurePosixPath(logical).relative_to(DOC)
        snapshot = destination.joinpath(*relative.parts)
        snapshot.parent.mkdir(parents=True, exist_ok=True)
        snapshot.write_bytes(content)
        os.chmod(snapshot, 0o644)
        parent = packer.resolve_confined(root, str(PurePosixPath(logical).parent))
        leaf = parent / PurePosixPath(logical).name
        if leaf.is_symlink():
            write_regular(parent, leaf.name, content)
        records.append({"path": logical, "snapshot": str(PurePosixPath(LICENSES) / "ubuntu-docs" / relative), "sha256": hashlib.sha256(content).hexdigest()})
    (root / LICENSES / "ubuntu-doc-preservation.json").write_text(json.dumps(records, indent=2) + "\n")
    (root / LICENSES / "ubuntu-doc-file-materialization.json").write_text(json.dumps(materialized, indent=2) + "\n")


def license_parent(root, logical, restorations):
    """Recreate a purged doc-directory alias only to restore a retained package license."""
    parts = PurePosixPath(logical).parent.parts
    for index in range(3, len(parts) + 1):
        prefix = PurePosixPath(*parts[:index])
        parent = packer.resolve_confined(root, str(prefix.parent))
        leaf = parent / prefix.name
        if leaf.is_symlink():
            target = packer.resolve_confined(root, str(prefix))
            if not target.exists():
                leaf.unlink()
                leaf.mkdir(mode=0o755)
                restorations.append(str(prefix))
            elif not target.is_dir():
                raise ValueError(f"license parent is not a directory: {prefix}")
        elif not leaf.exists():
            leaf.mkdir(mode=0o755)
        elif not leaf.is_dir():
            raise ValueError(f"license parent is not a directory: {prefix}")
    return packer.resolve_confined(root, str(PurePosixPath(logical).parent))


def cleanup(root):
    """Restore retained-package licenses, then remove only confirmed dangling optional docs."""
    root = root.resolve(strict=True)
    inventory = root / LICENSES / "ubuntu-packages.tsv"
    retained = {line.split("\t", 1)[0].split(":", 1)[0] for line in inventory.read_text().splitlines() if line}
    records = json.loads((root / LICENSES / "ubuntu-doc-preservation.json").read_text())
    restorations = []
    restored = 0
    for record in records:
        relative = PurePosixPath(record["path"]).relative_to(DOC)
        if relative.parts[0] not in retained:
            continue
        content = (root / record["snapshot"]).read_bytes()
        if hashlib.sha256(content).hexdigest() != record["sha256"]:
            raise ValueError(f"license snapshot checksum mismatch: {record['path']}")
        parent = license_parent(root, record["path"], restorations)
        write_regular(parent, relative.name, content)
        restored += 1
    removed = []
    for logical, resolved in walk(root):
        if resolved is not None:
            continue
        name = PurePosixPath(logical).name
        parent = packer.resolve_confined(root, str(PurePosixPath(logical).parent))
        leaf = parent / name
        if not leaf.is_symlink() or PROTECTED.match(name) or not OPTIONAL.match(name):
            raise ValueError(f"unclassified missing documentation link (not removed): {logical}")
        removed.append({"path": logical, "target": os.readlink(leaf), "reason": "optional documentation target removed by build-package purge"})
        leaf.unlink()
    materialized = json.loads((root / LICENSES / "ubuntu-doc-file-materialization.json").read_text())
    report = {"scope": "/usr/share/doc", "materializedFileLinksBeforePurge": materialized, "restoredLicenseFiles": restored, "restoredLicenseDirectories": restorations, "removedOptionalLinks": removed}
    (root / LICENSES / "dangling-optional-docs.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


def trim_optional_resources(root):
    """Remove only system translation catalogs/man/info, keeping any license-shaped files."""
    root = root.resolve(strict=True)
    report = {"removedPaths": [], "preservedLicenseFiles": [], "removedManualAliases": []}
    for logical in ["usr/share/locale", "usr/share/man", "usr/share/info"]:
        parent = packer.resolve_confined(root, str(PurePosixPath(logical).parent))
        leaf = parent / PurePosixPath(logical).name
        if not leaf.exists() and not leaf.is_symlink():
            continue
        for document, resolved in walk(root, logical):
            if not PROTECTED.match(PurePosixPath(document).name):
                continue
            if resolved is None or not resolved.is_file():
                raise ValueError(f"missing license in optional resource tree: {document}")
            content = resolved.read_bytes()
            snapshot = root / LICENSES / "trimmed-resource-licenses" / document
            snapshot.parent.mkdir(parents=True, exist_ok=True)
            snapshot.write_bytes(content)
            report["preservedLicenseFiles"].append({"path": document, "snapshot": snapshot.relative_to(root).as_posix(), "sha256": hashlib.sha256(content).hexdigest()})
        if leaf.is_symlink():
            leaf.unlink()
        elif leaf.is_dir():
            shutil.rmtree(leaf)
        else:
            raise ValueError(f"unexpected optional resource type: {logical}")
        report["removedPaths"].append(logical)
    alternatives = root / "etc/alternatives"
    if alternatives.is_dir() and not alternatives.is_symlink():
        for leaf in sorted(alternatives.iterdir()):
            if not leaf.is_symlink() or PROTECTED.match(leaf.name):
                continue
            target = os.readlink(leaf)
            normalized = posixpath.normpath(posixpath.join("/etc/alternatives", target))
            if not normalized.startswith("/usr/share/man/"):
                continue
            report["removedManualAliases"].append({"path": leaf.relative_to(root).as_posix(), "target": target})
            leaf.unlink()
    (root / LICENSES / "optional-resource-trim.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


if __name__ == "__main__":
    {"preserve": preserve, "cleanup": cleanup, "trim": trim_optional_resources}[sys.argv[1]](Path(sys.argv[2]))
