#!/usr/bin/env python3
"""Check the version-sensitive manifest contract used by Termux app.

Verifies two contracts that regress into on-device failures:

1. Storage permissions:
   - READ/WRITE_EXTERNAL_STORAGE capped at API 32 (legacy shared storage).
   - MANAGE_EXTERNAL_STORAGE must remain available on API 33+.

2. Dynamic-feature split (support_ui owns the launcher):
   - com.termux.app.TermuxActivity (LAUNCHER) must live ONLY in support_ui.
   - The app base must NOT declare TermuxActivity. A base-only install of a
     split-AAB app crashes cold start with ClassNotFoundException otherwise
     (reproduced on device 65009db7 with a plain assembleDebug APK).
"""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"


def parse_manifest(path: Path):
    return ET.parse(path).getroot()


def activities(root, name: str):
    found = []
    for node in root.findall(".//activity"):
        if node.get(ANDROID + "name") == name:
            found.append(node)
    return found


def is_launcher(node) -> bool:
    for action in node.findall(".//action"):
        if action.get(ANDROID + "name") == "android.intent.action.MAIN":
            return True
    return False


def main() -> int:
    repo = Path(__file__).parents[1]
    base = parse_manifest(repo / "app/src/main/AndroidManifest.xml")
    feature = parse_manifest(repo / "support_ui/src/main/AndroidManifest.xml")

    # --- contract 1: storage permissions -------------------------------
    permissions = {
        node.get(ANDROID + "name"): node
        for node in base.findall("uses-permission")
    }

    def require(name: str):
        if name not in permissions:
            raise AssertionError(f"missing manifest permission: {name}")
        return permissions[name]

    for name in (
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
    ):
        node = require(name)
        if node.get(ANDROID + "maxSdkVersion") != "32":
            raise AssertionError(f"{name} must be capped at API 32")

    manage = require("android.permission.MANAGE_EXTERNAL_STORAGE")
    if manage.get(ANDROID + "maxSdkVersion") is not None:
        raise AssertionError("MANAGE_EXTERNAL_STORAGE must remain available on API 33+")

    application = base.find("application")
    if application is None or application.get(ANDROID + "requestLegacyExternalStorage") != "true":
        raise AssertionError("legacy storage compatibility flag must remain enabled for targetSdk 28")

    # --- contract 2: support_ui owns the launcher activity --------------
    feature_activity = activities(feature, "com.termux.app.TermuxActivity")
    if not feature_activity:
        raise AssertionError("support_ui must declare com.termux.app.TermuxActivity")
    if not is_launcher(feature_activity[0]):
        raise AssertionError("support_ui TermuxActivity must carry the MAIN/LAUNCHER intent filter")

    base_activity = activities(base, "com.termux.app.TermuxActivity")
    if base_activity:
        raise AssertionError(
            "app base must not declare com.termux.app.TermuxActivity; it lives in the "
            "support_ui dynamic feature (a base-only install would crash cold start)"
        )

    print("PASS manifest compatibility: legacy storage <= API 32; managed storage API 33+; "
          "launcher activity only in support_ui")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, ET.ParseError) as error:
        print(f"FAIL manifest compatibility: {error}", file=sys.stderr)
        raise SystemExit(1)