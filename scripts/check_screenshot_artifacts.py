#!/usr/bin/env python3
"""Validate CI capture completeness and safe metadata; never print artifact contents."""
import pathlib
import struct
import sys

SCREENS = ("empty-chat", "populated-chat", "drawer", "settings", "login-logged-out")
FORBIDDEN = (b"screenshot-canary-not-a-real-token", b"Authorization", b"Bearer ", b"ds_session_id=", b"Cookie:")


def validate(directory):
    for theme in ("light", "dark"):
        for screen in SCREENS:
            prefix = f"{theme}-{screen}"
            png = (directory / f"{prefix}.png").read_bytes()
            if png[:8] != b"\x89PNG\r\n\x1a\n" or len(png) < 24:
                raise ValueError("A required capture is not a PNG")
            width, height = struct.unpack(">II", png[16:24])
            if not (1000 <= width <= 1100 and height > 1000):
                raise ValueError("Unexpected reference viewport dimensions")
            metadata = (directory / f"{prefix}.txt").read_bytes()
            expected = f"screen={screen}\ntheme={theme}\nsource=deterministic-fake-data\n".encode()
            if metadata != expected:
                raise ValueError("Screenshot metadata is not on the allowlist")
    for path in directory.iterdir():
        if path.is_file() and any(secret in path.read_bytes() for secret in FORBIDDEN):
            raise ValueError("Credential or authentication header marker found in capture output")


if __name__ == "__main__":
    try:
        validate(pathlib.Path(sys.argv[1]))
    except (OSError, ValueError, IndexError):
        print("Screenshot validation failed: missing/invalid capture or unsafe metadata.", file=sys.stderr)
        sys.exit(1)
    print("Ten light/dark captures present; metadata is allowlisted and contains no session/header markers.")
