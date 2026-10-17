"""Basic source checks for the root-only Android release; not device validation."""

import pathlib
import re
import xml.etree.ElementTree as ET


def check_source(root: pathlib.Path) -> None:
    app = root / "app"
    manifest = ET.parse(app / "src/main/AndroidManifest.xml").getroot()
    android = "{http://schemas.android.com/apk/res/android}"
    application = manifest.find("application")
    assert application is not None
    assert application.get(android + "allowBackup") == "false", "Backup must be disabled"
    source = app / "src/main/java/com/dshmobile/app"
    text = "\n".join(p.read_text(encoding="utf-8") for p in source.rglob("*.java"))
    for legacy in ("ProotRunner", "NodePtyFixer", "CookieMinter", "BootstrapInstaller"):
        assert legacy not in text, f"Legacy runtime reference: {legacy}"
    for forbidden in ("setenforce 0", "pkill node", "safe.directory=*", "addJavascriptInterface("):
        assert forbidden not in text, f"Unsafe runtime pattern: {forbidden}"
    gradle = (app / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'io.github.mixedscene.dshroot' in gradle
    assert re.search(r"targetSdk\s*=\s*36", gradle)
    assert "dshmobile2026" not in gradle, "Do not sign with the upstream public key"
    assert (root.parent / "native/mobile-supervisor/CMakeLists.txt").is_file()
    print("Root-only Android source checks passed; real-device tests NOT performed.")


if __name__ == "__main__":
    check_source(pathlib.Path(__file__).resolve().parents[1])
