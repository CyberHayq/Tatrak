"""Inspect and prepare only explicitly selected Android test devices over ADB."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("serial")
parser.add_argument("action", choices=["dump", "tap-text", "tap-id", "type", "back", "screenshot"])
parser.add_argument("value", nargs="?")
args = parser.parse_args()


def adb(*command):
    return subprocess.run(["adb", "-s", args.serial, *command], check=True,
                          capture_output=True, timeout=25).stdout


if args.action == "type":
    adb("shell", "input", "text", shlex.quote(args.value.replace(" ", "%s")))
elif args.action == "back":
    adb("shell", "input", "keyevent", "4")
elif args.action == "screenshot":
    Path(args.value).write_bytes(adb("exec-out", "screencap", "-p"))
else:
    adb("shell", "uiautomator", "dump", "/sdcard/tatrak-qa-window.xml")
    raw = adb("exec-out", "cat", "/sdcard/tatrak-qa-window.xml")
    root = ET.fromstring(raw)
    if args.action == "dump":
        print(json.dumps([dict(n.attrib) for n in root.iter("node")
                          if n.get("text") or n.get("content-desc") or n.get("class") == "android.widget.EditText"],
                         ensure_ascii=False))
    else:
        attribute = "text" if args.action == "tap-text" else "resource-id"
        matches = [n for n in root.iter("node") if n.get(attribute) == args.value]
        if len(matches) != 1:
            raise RuntimeError(f"Expected exactly one node matching {attribute}={args.value!r}; found {len(matches)}")
        bounds = list(map(int, re.findall(r"\d+", matches[0].get("bounds", ""))))
        if len(bounds) != 4:
            raise RuntimeError("No valid screen bounds on the selected node")
        left, top, right, bottom = bounds
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
