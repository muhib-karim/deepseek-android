#!/usr/bin/env python3
"""Drive the official DeepSeek app on a disposable CI emulator and capture every reachable screen in light and dark.

Black-box only: the driver reads the accessibility hierarchy (`uiautomator dump`), taps by text / content description,
and saves `screencap` PNGs, sanitized hierarchies and a coverage manifest. It never reads the app's storage, never
modifies the APK and never works around a CAPTCHA or Google challenge: a blocked step is recorded as missing.

Signed-in screens need a dedicated test account in DS_REF_EMAIL / DS_REF_PASSWORD (repository secrets). Video
recording is stopped while credentials are typed and on screens that show account details (drawer, settings, profile);
email addresses and phone numbers are blacked out in screenshots and replaced in hierarchies.

CI only: scripts/capture_reference.sh runs it inside reactivecircus/android-emulator-runner.
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.deepseek.chat"
PRIVATE = [
    re.compile(r"[A-Za-z0-9._%+*-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),
    re.compile(r"\+?\d[\d\s*()-]{6,}\d"),
]
LOGIN_MARKERS = [r"^Log in$", r"^Login$", r"Continue with Google", r"Email / \+86", r"^Send code$", r"Continue with Password"]
CHAT_MARKERS = [r"^Type a message", r"^New chat$", r"^Message DeepSeek"]
CHAT_DESCRIPTIONS = [r"^Open sidebar$", r"^New chat$"]
CHALLENGE = re.compile(r"captcha|verif(y|ication)|slide to|puzzle|security check|robot", re.I)
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


class Node:
    __slots__ = ("text", "desc", "rid", "cls", "pkg", "clickable", "checkable", "checked", "editable", "bounds")

    def __init__(self, element):
        get = element.attrib.get
        self.text = get("text", "")
        self.desc = get("content-desc", "")
        self.rid = get("resource-id", "")
        self.cls = get("class", "")
        self.pkg = get("package", "")
        self.clickable = get("clickable") == "true"
        self.checkable = get("checkable") == "true"
        self.checked = get("checked") == "true"
        self.editable = self.cls.endswith("EditText")
        match = BOUNDS.match(get("bounds", ""))
        self.bounds = tuple(int(v) for v in match.groups()) if match else (0, 0, 0, 0)

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    @property
    def area(self):
        x1, y1, x2, y2 = self.bounds
        return max(0, x2 - x1) * max(0, y2 - y1)


def parse(xml_text):
    root = ET.fromstring(xml_text)
    return [Node(element) for element in root.iter("node")]


def find(nodes, text=None, desc=None, rid=None, editable=None, checkable=None, package=PACKAGE):
    """The first visible node matching every given pattern (case-insensitive regex search)."""
    for node in nodes:
        if package and node.pkg != package:
            continue
        if node.area == 0:
            continue
        if text and not re.search(text, node.text, re.I):
            continue
        if desc and not re.search(desc, node.desc, re.I):
            continue
        if rid and not re.search(rid, node.rid, re.I):
            continue
        if editable is not None and node.editable != editable:
            continue
        if checkable is not None and node.checkable != checkable:
            continue
        return node
    return None


def find_any(nodes, texts=(), descs=(), package=PACKAGE):
    for pattern in texts:
        node = find(nodes, text=pattern, package=package)
        if node:
            return node
    for pattern in descs:
        node = find(nodes, desc=pattern, package=package)
        if node:
            return node
    return None


def private_boxes(nodes):
    return [node.bounds for node in nodes if any(p.search(node.text) or p.search(node.desc) for p in PRIVATE)]


def sanitize_xml(xml_text):
    def scrub(match):
        value = match.group(2)
        for pattern in PRIVATE:
            value = pattern.sub("[redacted]", value)
        return f'{match.group(1)}="{value}"'
    return re.sub(r'(text|content-desc)="([^"]*)"', scrub, xml_text)


class Device:
    def __init__(self):
        self.width, self.height = 1080, 2200

    def adb(self, *args, timeout=60, check=False, binary=False):
        result = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {args[0]} failed")
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def shell(self, command, timeout=60):
        return self.adb("shell", command, timeout=timeout)

    def dump(self):
        for _ in range(6):
            out = self.shell("uiautomator dump /sdcard/ref_window.xml", timeout=45)
            if "dumped" in out.lower():
                xml_text = self.adb("exec-out", "cat", "/sdcard/ref_window.xml")
                try:
                    return parse(xml_text), xml_text
                except ET.ParseError:
                    pass
            time.sleep(1.5)
        return [], "<hierarchy/>"

    def settle(self, timeout=8.0):
        """Waits until two consecutive hierarchies agree (animations done), then returns the last one."""
        deadline = time.time() + timeout
        previous, nodes, xml_text = None, [], "<hierarchy/>"
        while time.time() < deadline:
            nodes, xml_text = self.dump()
            signature = [(n.text, n.desc, n.bounds) for n in nodes]
            if signature and signature == previous:
                break
            previous = signature
            time.sleep(0.8)
        return nodes, xml_text

    def wait(self, predicate, timeout=20.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            nodes, _ = self.dump()
            found = predicate(nodes)
            if found:
                return found
            time.sleep(1.0)
        return None

    def tap(self, node=None, x=None, y=None):
        if node is not None:
            x, y = node.center
        self.shell(f"input tap {int(x)} {int(y)}")
        time.sleep(1.2)

    def long_press(self, node):
        x, y = node.center
        self.shell(f"input swipe {x} {y} {x} {y} 900")
        time.sleep(1.2)

    def back(self):
        self.shell("input keyevent 4")
        time.sleep(1.0)

    def swipe_up(self):
        self.shell(f"input swipe {self.width // 2} {int(self.height * .75)} {self.width // 2} {int(self.height * .30)} 400")
        time.sleep(1.0)

    def type_text(self, node, value):
        """Types into a field. The value is never printed; spaces use input's %s escape."""
        self.tap(node)
        self.shell("input text " + shlex.quote(value.replace(" ", "%s")))
        time.sleep(0.8)

    def screencap(self, path):
        data = self.adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        with open(path, "wb") as handle:
            handle.write(data)
        return len(data)

    def top_activity(self):
        out = self.shell("dumpsys activity activities")
        match = re.search(r"(topResumedActivity|mResumedActivity)[^\n]*?\s([\w.]+/[\w.$]+)", out)
        return match.group(2) if match else "unknown"

    def restart(self):
        self.shell(f"am force-stop {PACKAGE}")
        time.sleep(1.0)
        self.shell(f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1")
        time.sleep(4.0)


class Recorder:
    """Bounded screenrecord segments; stopped with SIGINT so each MP4 is finalized, then pulled."""

    def __init__(self, device, directory):
        self.device, self.directory, self.process, self.index, self.name = device, directory, None, 0, None
        os.makedirs(directory, exist_ok=True)

    def start(self, label):
        self.stop()
        self.index += 1
        self.name = f"{self.index:02d}-{label}.mp4"
        self.process = subprocess.Popen(["adb", "shell", "screenrecord", "--bit-rate", "4000000", "--time-limit", "170",
                                         f"/sdcard/{self.name}"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(1.0)

    def stop(self):
        if self.process is None:
            return
        self.device.shell("pkill -INT screenrecord || kill -2 $(pidof screenrecord)")
        try:
            self.process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            self.process.kill()
        time.sleep(1.5)
        self.device.adb("pull", f"/sdcard/{self.name}", os.path.join(self.directory, self.name), timeout=120)
        self.device.shell(f"rm -f /sdcard/{self.name}")
        self.process = None


class Session:
    def __init__(self, out, version):
        self.out, self.version = out, version
        self.device = Device()
        self.recorder = Recorder(self.device, os.path.join(out, "video"))
        self.entries = []
        self.step = 0
        os.makedirs(os.path.join(out, "screens"), exist_ok=True)

    # ---- capture and bookkeeping ----

    def capture(self, screen, theme):
        nodes, xml_text = self.device.settle()
        self.step += 1
        prefix = f"{theme}-{screen}"
        png = os.path.join(self.out, "screens", prefix + ".png")
        self.device.screencap(png)
        boxes = private_boxes(nodes)
        status = "captured"
        if boxes and not redact(png, boxes):
            os.remove(png)
            status = "missing"
        with open(os.path.join(self.out, "screens", prefix + ".xml"), "w") as handle:
            handle.write(sanitize_xml(xml_text))
        meta = {"screen": screen, "theme": theme, "source": "official-reference", "package": PACKAGE,
                "version": self.version, "activity": self.device.top_activity(), "step": self.step,
                "redacted_regions": len(boxes)}
        with open(os.path.join(self.out, "screens", prefix + ".json"), "w") as handle:
            json.dump(meta, handle, indent=2)
        self.entries.append({"screen": screen, "theme": theme, "status": status,
                             "reason": "" if status == "captured" else "private data on screen and redaction unavailable",
                             "png": prefix + ".png" if status == "captured" else None, "xml": prefix + ".xml", "meta": prefix + ".json"})
        print(f"[{theme}] {screen}: {status}")

    def missing(self, screen, theme, reason):
        self.entries.append({"screen": screen, "theme": theme, "status": "missing", "reason": reason,
                             "png": None, "xml": None, "meta": None})
        print(f"[{theme}] {screen}: missing ({reason})")

    def attempt(self, screen, theme, action):
        """Runs one optional screen; any failure is recorded as missing and the app is reset."""
        try:
            reason = action()
            if reason:
                self.missing(screen, theme, reason)
        except Exception as error:  # noqa: BLE001 - each screen is best effort and must not stop the run
            self.missing(screen, theme, f"driver error: {type(error).__name__}")
            self.device.restart()

    # ---- navigation helpers ----

    def dismiss_system_dialogs(self, nodes):
        for package in ("com.android.permissioncontroller", "com.google.android.permissioncontroller", "android"):
            button = find_any(nodes, texts=(r"^Don.t allow$", r"^Deny$", r"^Close app$", r"^OK$"), package=package)
            if button:
                self.device.tap(button)
                return True
        return False

    def to_login(self):
        self.device.restart()
        def ready(nodes):
            if self.dismiss_system_dialogs(nodes):
                return None
            agree = find_any(nodes, texts=(r"^Agree$", r"^Agree and continue$", r"^Accept$"))
            if agree and not find_any(nodes, texts=LOGIN_MARKERS):
                return agree
            return find_any(nodes, texts=LOGIN_MARKERS)
        found = self.device.wait(ready, timeout=40)
        return found

    def to_chat(self):
        self.device.restart()
        def ready(nodes):
            if self.dismiss_system_dialogs(nodes):
                return None
            notice = find_any(nodes, texts=(r"^Got it$", r"^Later$", r"^Not now$", r"^Close$", r"^Skip$"))
            if notice and not find_any(nodes, texts=CHAT_MARKERS, descs=CHAT_DESCRIPTIONS):
                self.device.tap(notice)
                return None
            return find_any(nodes, texts=CHAT_MARKERS, descs=CHAT_DESCRIPTIONS)
        return self.device.wait(ready, timeout=40)

    def tap_text(self, *patterns, descs=(), timeout=10):
        node = self.device.wait(lambda nodes: find_any(nodes, texts=patterns, descs=descs), timeout=timeout)
        if node:
            self.device.tap(node)
        return node

    # ---- signed-out screens ----

    def signed_out(self, theme):
        self.recorder.start(f"{theme}-signed-out")
        first = self.to_login()
        if first is None:
            self.missing("login", theme, "the sign-in screen did not appear")
            return False
        nodes, _ = self.device.dump()
        agree = find_any(nodes, texts=(r"^Agree$", r"^Agree and continue$", r"^Accept$"))
        if agree and not find_any(nodes, texts=LOGIN_MARKERS):
            self.capture("intro", theme)
            self.device.tap(agree)
            if not self.device.wait(lambda n: find_any(n, texts=LOGIN_MARKERS), timeout=20):
                self.missing("login", theme, "the sign-in screen did not follow the intro")
                return False
        self.capture("login", theme)

        def password():
            if not self.tap_text(r"Log in with password", r"Continue with Password", r"^Password$", timeout=5):
                return "no password sign-in option"
            self.capture("login-password", theme)
            self.to_login()
            return None
        self.attempt("login-password", theme, password)

        def forgot():
            self.tap_text(r"Log in with password", r"Continue with Password", timeout=3)
            if not self.tap_text(r"^Forgot password", timeout=5):
                return "no forgot-password link"
            self.capture("forgot-password", theme)
            self.to_login()
            return None
        self.attempt("forgot-password", theme, forgot)

        def google():
            if not self.tap_text(r"Continue with Google", r"Log in with Google", timeout=5):
                return "no Google sign-in button"
            time.sleep(4)
            self.capture("login-google", theme)
            self.to_login()
            return None
        self.attempt("login-google", theme, google)
        self.recorder.stop()
        return True

    # ---- signed-in screens ----

    def sign_in(self, email, password):
        """Returns None when signed in, otherwise the reason. Recording is off while credentials are typed."""
        self.recorder.stop()
        if not self.to_login():
            return "the sign-in screen did not appear"
        self.tap_text(r"Log in with password", r"Continue with Password", timeout=4)
        nodes, _ = self.device.settle()
        fields = [n for n in nodes if n.pkg == PACKAGE and n.editable and n.area > 0]
        if len(fields) < 2:
            return "no email and password fields"
        self.device.type_text(fields[0], email)
        self.device.type_text(fields[1], password)
        self.device.back()  # hide the keyboard
        nodes, _ = self.device.settle()
        box = find(nodes, checkable=True)
        if box and not box.checked:
            self.device.tap(box)
        elif box is None:
            consent = find(nodes, text=r"^I confirm")
            if consent:
                self.device.tap(x=consent.bounds[0] + 24, y=consent.center[1])
        if not self.tap_text(r"^Log in$", r"^Login$", timeout=5):
            return "no Log in button"
        def outcome(nodes):
            if find_any(nodes, texts=CHAT_MARKERS, descs=CHAT_DESCRIPTIONS):
                return "chat"
            if any(CHALLENGE.search(n.text) for n in nodes):
                return "challenge"
            if find(nodes, text=r"When were you born"):
                return "birthday"
            return None
        result = self.device.wait(outcome, timeout=45)
        if result == "chat":
            return None
        if result == "challenge":
            return "blocked by a verification challenge (not automated)"
        if result == "birthday":
            return "the account must confirm a date of birth first"
        return "sign-in did not reach the chat screen"

    def chat_screens(self, theme, first):
        self.recorder.start(f"{theme}-chat")
        if not self.to_chat():
            self.missing("chat-empty", theme, "the chat screen did not appear")
            self.recorder.stop()
            return
        self.tap_text(descs=(r"^New chat$",), timeout=3)
        self.capture("chat-empty", theme)

        def toggle(label, screen):
            def run():
                if not self.tap_text(f"^{label}$", timeout=5):
                    return f"no {label} button"
                self.capture(screen, theme)
                self.tap_text(f"^{label}$", timeout=5)
                return None
            return run
        self.attempt("think-on", theme, toggle("Think", "think-on"))
        self.attempt("search-on", theme, toggle("Search", "search-on"))

        def attach():
            if not self.tap_text(descs=(r"Attach", r"^Add", r"Upload", r"^More$"), timeout=5):
                return "no attach button"
            self.capture("attach-sheet", theme)
            self.device.back()
            return None
        self.attempt("attach-sheet", theme, attach)

        def model():
            if not self.tap_text(r"^(Instant|Expert)", timeout=4):
                return "no model picker"
            self.capture("model-picker", theme)
            self.device.back()
            return None
        self.attempt("model-picker", theme, model)

        if first:
            def populated():
                field = self.device.wait(lambda n: find(n, editable=True), timeout=8)
                if not field:
                    return "no message field"
                self.device.type_text(field, "Hello! Reply with one short sentence.")
                if not self.tap_text(descs=(r"^Send",), timeout=5):
                    return "no send button"
                if not self.device.wait(lambda n: find_any(n, descs=(r"^Copy$", r"Regenerate"), texts=(r"^Copy$",)), timeout=90):
                    return "no reply within 90 seconds"
                self.capture("chat-populated", theme)
                return None
            self.attempt("chat-populated", theme, populated)

            def thinking():
                self.tap_text(f"^Think$", timeout=5)
                field = self.device.wait(lambda n: find(n, editable=True), timeout=8)
                if not field:
                    return "no message field"
                self.device.type_text(field, "What is 17 times 23? Answer briefly.")
                if not self.tap_text(descs=(r"^Send",), timeout=5):
                    return "no send button"
                row = self.device.wait(lambda n: find(n, text=r"^Thought for"), timeout=120)
                self.tap_text(f"^Think$", timeout=5)
                if not row:
                    return "no thinking row within 120 seconds"
                self.capture("thinking", theme)
                self.device.tap(row)
                self.capture("thinking-expanded", theme)
                return None
            self.attempt("thinking", theme, thinking)

            def menu():
                nodes, _ = self.device.dump()
                texts = [n for n in nodes if n.pkg == PACKAGE and len(n.text) > 20 and not n.editable]
                if not texts:
                    return "no reply text to press"
                self.device.long_press(texts[-1])
                self.capture("message-menu", theme)
                share = find(self.device.dump()[0], text=r"^Share$")
                if share:
                    self.device.tap(share)
                    self.capture("share", theme)
                    image = self.tap_text(r"Create Image", r"Share Image", timeout=4)
                    if image:
                        self.capture("share-image", theme)
                else:
                    self.missing("share", theme, "no Share action in the message menu")
                self.to_chat()
                return None
            self.attempt("message-menu", theme, menu)
        self.recorder.stop()

        # Account details appear from here on: no video.
        def drawer():
            self.to_chat()
            if not self.tap_text(descs=(r"^Open sidebar$", r"menu"), timeout=5):
                return "no sidebar button"
            self.capture("drawer", theme)
            nodes, _ = self.device.dump()
            rows = [n for n in nodes if n.pkg == PACKAGE and n.text and n.bounds[1] > 300 and n.bounds[3] < self.device.height - 300
                    and not re.search(r"^(Today|Yesterday|7 Days|30 Days|Pinned|New chat)$", n.text)]
            if rows:
                self.device.long_press(rows[0])
                self.capture("drawer-item-menu", theme)
                self.device.back()
            else:
                self.missing("drawer-item-menu", theme, "no chat row in the drawer")
            return None
        self.attempt("drawer", theme, drawer)

        def settings():
            self.to_chat()
            if not self.tap_text(descs=(r"^Open sidebar$",), timeout=5):
                return "no sidebar button"
            nodes, _ = self.device.settle()
            bottom = [n for n in nodes if n.pkg == PACKAGE and n.bounds[1] > self.device.height * .8 and (n.text or n.desc) and n.clickable]
            target = find_any(nodes, texts=(r"^Settings$",), descs=(r"Settings", r"Profile")) or (bottom[-1] if bottom else None)
            if not target:
                return "no settings entry in the drawer"
            self.device.tap(target)
            if not self.device.wait(lambda n: find_any(n, texts=(r"^Settings$", r"^Appearance$", r"^Log out$")), timeout=10):
                return "settings did not open"
            self.capture("settings", theme)
            self.device.swipe_up()
            self.capture("settings-more", theme)
            self.device.back()
            for label, screen in (("Appearance", "settings-appearance"), ("Language", "settings-language"),
                                  ("Font size", "settings-font-size"), ("Account settings", "profile"),
                                  ("Data controls", "data-controls"), ("Personalization", "personalization")):
                self.attempt(screen, theme, lambda label=label, screen=screen: self.settings_item(label, screen, theme))
            return None
        self.attempt("settings", theme, settings)

    def settings_item(self, label, screen, theme):
        self.to_chat()
        self.tap_text(descs=(r"^Open sidebar$",), timeout=5)
        nodes, _ = self.device.settle()
        target = find_any(nodes, texts=(r"^Settings$",), descs=(r"Settings", r"Profile"))
        if target is None:
            bottom = [n for n in nodes if n.pkg == PACKAGE and n.bounds[1] > self.device.height * .8 and n.clickable]
            target = bottom[-1] if bottom else None
        if target is None:
            return "no settings entry"
        self.device.tap(target)
        for _ in range(3):
            node = self.device.wait(lambda n: find(n, text=f"^{label}$"), timeout=4)
            if node:
                self.device.tap(node)
                self.capture(screen, theme)
                return None
            self.device.swipe_up()
        return f"no {label} row"


def redact(png_path, boxes):
    """Blacks out private regions; returns False when Pillow is unavailable (the caller then drops the image)."""
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return False
    with Image.open(png_path) as image:
        canvas = image.convert("RGB")
    draw = ImageDraw.Draw(canvas)
    for x1, y1, x2, y2 in boxes:
        draw.rectangle([x1, y1, x2, y2], fill=(0, 0, 0))
    canvas.save(png_path, "PNG")
    return True


def main(argv):
    parser = argparse.ArgumentParser(description="Capture the official DeepSeek app's screens.")
    parser.add_argument("--out", required=True)
    parser.add_argument("--version", default="unknown")
    args = parser.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)
    session = Session(args.out, args.version)
    email, password = os.environ.get("DS_REF_EMAIL", ""), os.environ.get("DS_REF_PASSWORD", "")
    signed_in_reason = "no reference account configured (DEEPSEEK_REFERENCE_EMAIL / DEEPSEEK_REFERENCE_PASSWORD)"
    for theme in ("light", "dark"):
        session.device.shell(f"cmd uimode night {'yes' if theme == 'dark' else 'no'}")
        time.sleep(2)
        session.signed_out(theme)
    if email and password:
        session.device.shell("cmd uimode night no")
        reason = session.sign_in(email, password)
        if reason is None:
            signed_in_reason = ""
            for theme in ("light", "dark"):
                session.device.shell(f"cmd uimode night {'yes' if theme == 'dark' else 'no'}")
                time.sleep(2)
                session.chat_screens(theme, first=theme == "light")
        else:
            signed_in_reason = reason
    session.recorder.stop()
    session.device.shell("cmd uimode night no")
    protected = ["chat-empty", "think-on", "search-on", "attach-sheet", "chat-populated", "message-menu", "share",
                 "drawer", "settings", "settings-appearance", "profile", "data-controls"]
    if signed_in_reason:
        for theme in ("light", "dark"):
            for screen in protected:
                session.missing(screen, theme, signed_in_reason)
    manifest = {"package": PACKAGE, "version": args.version, "source": "official-reference",
                "signed_in": not signed_in_reason, "signed_in_reason": signed_in_reason,
                "viewport": {"width": 1080, "height": 2200, "density": 440, "font_scale": 1.0},
                "entries": session.entries}
    with open(os.path.join(args.out, "manifest.json"), "w") as handle:
        json.dump(manifest, handle, indent=2)
    captured = sum(1 for e in session.entries if e["status"] == "captured")
    print(f"Captured {captured} screens; manifest at {args.out}/manifest.json")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
