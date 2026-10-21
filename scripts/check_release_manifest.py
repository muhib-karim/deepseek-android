"""CI gate: test dependencies must never widen release permissions.

play: INTERNET plus runtime microphone and photo access only.
full: the same plus what the official engine's local harness service needs (foreground data-sync service, its
notification and a wake lock). Neither flavor may declare custom permissions.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

android = "{http://schemas.android.com/apk/res/android}"
play = {"android.permission.INTERNET", "android.permission.RECORD_AUDIO", "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_EXTERNAL_STORAGE"}
expected = {
    "playRelease": play,
    "fullRelease": play | {"android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                           "android.permission.POST_NOTIFICATIONS", "android.permission.WAKE_LOCK"},
}
for variant, allowed in expected.items():
    paths = list(Path(f"app/build/intermediates/merged_manifests/{variant}").rglob("AndroidManifest.xml"))
    assert paths, f"No merged {variant} manifest found"
    for path in paths:
        manifest = ET.parse(path).getroot()
        actual = {element.get(android + "name") for element in manifest if element.tag.startswith("uses-permission")}
        assert actual == allowed, f"Unexpected {variant} permissions: {sorted(actual ^ allowed)}"
        assert not manifest.findall("permission"), f"Unexpected custom {variant} permission"
    print(f"Merged {variant} manifest: {sorted(p.rsplit('.', 1)[1] for p in allowed)}")
