"""Run J1 through an installed Android UI, on CI or an optional local host."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


PACKAGE = "com.appfusion.product"
TITLE = "J1EncryptedNote42"
BODY = "PrivateBodyAlpha42"


class AndroidJourney:
    def __init__(self, adb: str, apk: Path, evidence: Path) -> None:
        self.adb = adb
        self.apk = apk.resolve()
        self.evidence = evidence.resolve()
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.xml_path = self.evidence / "window.xml"
        if self.command("shell", "getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError("J1 resets test app data and may run only on an Android emulator")
        size = self.command("shell", "wm", "size")
        matches = re.findall(r"(\d+)x(\d+)", size)
        if not matches:
            raise RuntimeError(f"Unable to determine emulator display size: {size}")
        self.width, self.height = map(int, matches[-1])

    def command(self, *args: str, check: bool = True) -> str:
        result = subprocess.run(
            [self.adb, *args], capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=60,
        )
        with (self.evidence / "execution.log").open("a", encoding="utf-8") as log:
            log.write(f"adb {' '.join(args)}\n{result.stdout}{result.stderr}\n")
        if check and result.returncode:
            raise RuntimeError(f"ADB command failed: {' '.join(args)}: {result.stderr}")
        return result.stdout

    def dump_ui(self) -> None:
        for _ in range(5):
            try:
                self.command("shell", "uiautomator", "dump", "/sdcard/appfusion-window.xml")
                self.command("pull", "/sdcard/appfusion-window.xml", str(self.xml_path))
                ET.parse(self.xml_path)
                return
            except (RuntimeError, ET.ParseError):
                time.sleep(1)
        raise RuntimeError("Unable to capture the Android UI hierarchy")

    def center(self, mode: str, needle: str, *, require_enabled: bool = True) -> tuple[int, int] | None:
        for node in ET.parse(self.xml_path).getroot().iter("node"):
            value = node.get("resource-id", "") if mode == "id" else node.get("text", "")
            matches = value == needle if mode == "id" else needle in value
            if not matches or (require_enabled and node.get("enabled") == "false"):
                continue
            points = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(points) == 4 and points[2] > points[0] and points[3] > points[1]:
                return ((points[0] + points[2]) // 2, (points[1] + points[3]) // 2)
        return None

    def find(self, mode: str, needle: str, *, scroll: bool = False) -> tuple[int, int]:
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            self.dump_ui()
            if center := self.center(mode, needle):
                return center
            if scroll and self.center(mode, needle, require_enabled=False) is None:
                x = str(self.width // 2)
                self.command("shell", "input", "swipe", x, str(int(self.height * .87)),
                             x, str(int(self.height * .28)), "300")
            time.sleep(1)
        raise RuntimeError(f"Timed out waiting for UI {mode} {needle!r}")

    def tap(self, resource: str, *, scroll: bool = False) -> None:
        x, y = self.find("id", f"{PACKAGE}:id/{resource}", scroll=scroll)
        self.command("shell", "input", "tap", str(x), str(y))

    def start(self) -> None:
        self.command("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        self.find("text", "AppFusion")
        time.sleep(2)

    def capture(self) -> None:
        self.dump_ui()
        with (self.evidence / "j1-opened-document.png").open("wb") as screenshot:
            subprocess.run([self.adb, "exec-out", "screencap", "-p"],
                           stdout=screenshot, check=True, timeout=30)
        (self.evidence / "logcat.txt").write_text(
            self.command("logcat", "-d"), encoding="utf-8",
        )

    def run(self) -> None:
        if not self.apk.is_file():
            raise FileNotFoundError(self.apk)
        self.command("uninstall", PACKAGE, check=False)
        self.command("install", "-t", str(self.apk))
        self.start()
        self.tap("document_title")
        self.command("shell", "input", "text", TITLE)
        self.tap("document_body")
        self.command("shell", "input", "text", BODY)
        self.command("shell", "input", "keyevent", "111")
        self.tap("save_document", scroll=True)
        self.find("id", f"{PACKAGE}:id/search_result_item", scroll=True)

        self.command("shell", "am", "force-stop", PACKAGE)
        if self.command("shell", "pidof", PACKAGE, check=False).strip():
            raise RuntimeError("The application process survived force-stop")
        self.start()
        self.tap("search_query", scroll=True)
        self.command("shell", "input", "text", TITLE)
        self.command("shell", "input", "keyevent", "111")
        self.tap("search_documents", scroll=True)
        self.tap("search_result_item", scroll=True)
        self.find("text", BODY)
        self.capture()


def main() -> None:
    parser = argparse.ArgumentParser()
    sdk = os.environ.get("ANDROID_HOME", "")
    parser.add_argument("--adb", default=os.environ.get("ADB") or str(Path(sdk) / "platform-tools" / "adb"))
    parser.add_argument("--apk", type=Path, default=Path("androidApp/build/outputs/apk/debug/androidApp-debug.apk"))
    parser.add_argument("--evidence-dir", type=Path, default=Path(os.environ.get("APPFUSION_J1_EVIDENCE_DIR", "build/android-j1-evidence")))
    args = parser.parse_args()
    journey = AndroidJourney(args.adb, args.apk, args.evidence_dir)
    result = {"journey": "J1_ANDROID_UI", "status": "FAIL", "title": TITLE,
              "tested_at": datetime.now(timezone.utc).isoformat(),
              "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
              "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "device_fingerprint": journey.command("shell", "getprop", "ro.build.fingerprint").strip(),
              "operations": ["create", "force-stop", "relaunch", "search", "decrypt", "reopen"]}
    try:
        journey.run()
        result["status"] = "PASS"
        result["screenshot_sha256"] = hashlib.sha256(
            (journey.evidence / "j1-opened-document.png").read_bytes(),
        ).hexdigest()
        print("APPFUSION_ANDROID_J1=PASS")
    except Exception as error:
        result["error"] = str(error)
        try:
            journey.capture()
        except Exception as capture_error:
            result["capture_error"] = str(capture_error)
        raise
    finally:
        (journey.evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
