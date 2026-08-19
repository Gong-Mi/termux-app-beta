#!/usr/bin/env python3
"""Check the version-sensitive manifest contract used by Termux app."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"


def main() -> int:
    root = ET.parse(Path(__file__).parents[1] / "app/src/main/AndroidManifest.xml").getroot()
    permissions = {
        node.get(ANDROID + "name"): node
        for node in root.findall("uses-permission")
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

    application = root.find("application")
    if application is None or application.get(ANDROID + "requestLegacyExternalStorage") != "true":
        raise AssertionError("legacy storage compatibility flag must remain enabled for targetSdk 28")

    print("PASS manifest compatibility: legacy storage <= API 32; managed storage API 33+")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, ET.ParseError) as error:
        print(f"FAIL manifest compatibility: {error}", file=sys.stderr)
        raise SystemExit(1)
