"""CI gate: test dependencies must never widen release install permissions."""
from pathlib import Path
import xml.etree.ElementTree as ET

android = "{http://schemas.android.com/apk/res/android}"
expected = {"android.permission.INTERNET", "android.permission.RECORD_AUDIO"}
paths = list(Path("app/build/intermediates/merged_manifests/release").rglob("AndroidManifest.xml"))
assert paths, "No merged release manifest found"
for path in paths:
    manifest = ET.parse(path).getroot()
    actual = {element.get(android + "name") for element in manifest if element.tag.startswith("uses-permission")}
    assert actual == expected, f"Unexpected release permissions: {actual ^ expected}"
    assert not manifest.findall("permission"), "Unexpected custom release permission"
print("Merged release manifest: INTERNET and runtime RECORD_AUDIO only")
