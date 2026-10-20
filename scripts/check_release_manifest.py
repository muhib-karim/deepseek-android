"""CI gate: test dependencies must never widen release permissions (INTERNET, runtime microphone and photo access only)."""
from pathlib import Path
import xml.etree.ElementTree as ET

android = "{http://schemas.android.com/apk/res/android}"
expected = {"android.permission.INTERNET", "android.permission.RECORD_AUDIO", "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_EXTERNAL_STORAGE"}
paths = list(Path("app/build/intermediates/merged_manifests/release").rglob("AndroidManifest.xml"))
assert paths, "No merged release manifest found"
for path in paths:
    manifest = ET.parse(path).getroot()
    actual = {element.get(android + "name") for element in manifest if element.tag.startswith("uses-permission")}
    assert actual == expected, f"Unexpected release permissions: {actual ^ expected}"
    assert not manifest.findall("permission"), "Unexpected custom release permission"
print("Merged release manifest: INTERNET plus runtime RECORD_AUDIO and photo access (READ_MEDIA_IMAGES; READ_EXTERNAL_STORAGE up to API 32) only")
