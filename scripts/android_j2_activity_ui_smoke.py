"""Installed Android activity UI slice; NOT native notification or full J2 acceptance."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess

from android_j1_ui_smoke import AndroidJourney, PACKAGE

TITLE = "J2Activity42"


class ActivityJourney(AndroidJourney):
    def center(self, mode: str, needle: str, *, require_enabled: bool = True) -> tuple[int, int] | None:
        center = super().center(mode, needle, require_enabled=require_enabled)
        # UIAutomator can report a one-pixel sliver behind system navigation as
        # visible. Never tap there: scroll the target into the usable viewport.
        if center is not None and not (32 <= center[1] <= self.height - 64):
            return None
        return center

    def capture(self) -> None:
        super().capture()
        (self.evidence / "j1-opened-document.png").replace(self.evidence / "j2-activity-history.png")


def run_activity_slice(journey: AndroidJourney) -> None:
    # AndroidJourney rejects physical devices before this destructive emulator-only setup.
    original_zone = journey.command("shell", "getprop", "persist.sys.timezone").strip()
    if not original_zone:
        raise RuntimeError("Cannot restore unknown emulator time zone")
    try:
        journey.command("shell", "cmd", "alarm", "set-timezone", "UTC")
        journey.command("uninstall", PACKAGE, check=False)
        journey.command("install", "-t", str(journey.apk))
        journey.start()
        journey.tap("open_activities", scroll=True)
        journey.find("text", "0 activity record(s)")
        journey.tap("activity_save", scroll=True)
        journey.find("text", "Enter a title")
        # Return to top without depending on emulator pixel density.
        journey.command("shell", "input", "swipe", str(journey.width // 2), "180",
                        str(journey.width // 2), str(journey.height - 100), "300")
        journey.tap("activity_title")
        journey.command("shell", "input", "text", TITLE)
        journey.command("shell", "input", "keyevent", "111")
        journey.tap("activity_follow_zone", scroll=True)
        journey.tap("activity_save", scroll=True)
        journey.find("text", "1 activity record(s)", scroll=True)
        journey.find("text", "Completed: 0", scroll=True)
        journey.tap("activity_complete", scroll=True)
        journey.find("text", "Completed: 1", scroll=True)
        journey.tap("activity_history", scroll=True)
        journey.find("text", TITLE + " history")
        journey.find("text", "UTC")
        journey.command("shell", "input", "keyevent", "4")

        journey.command("shell", "am", "force-stop", PACKAGE)
        if journey.command("shell", "pidof", PACKAGE, check=False).strip():
            raise RuntimeError("App process survived force-stop")
        journey.command("shell", "cmd", "alarm", "set-timezone", "Asia/Karachi")
        if journey.command("shell", "getprop", "persist.sys.timezone").strip() != "Asia/Karachi":
            raise RuntimeError("Emulator timezone change did not take effect")
        journey.start()
        journey.tap("open_activities", scroll=True)
        journey.find("text", "1 activity record(s)", scroll=True)
        journey.find("text", "Completed: 1", scroll=True)
        journey.find("text", "09:00 Asia/Karachi", scroll=True)
        journey.tap("activity_history", scroll=True)
        journey.find("text", TITLE + " history")
        journey.find("text", "Asia/Karachi")
        journey.command("shell", "input", "keyevent", "4")
        journey.capture()
    finally:
        journey.command("shell", "cmd", "alarm", "set-timezone", original_zone)


def receipt(source: str, apk_digest: str, fingerprint: str) -> dict:
    return {
        "journey": "J2_ANDROID_ACTIVITY_UI_SLICE",
        "status": "FAIL",
        "native_notification_delivery_accepted": False,
        "j2_fully_accepted": False,
        "source_commit": source,
        "apk_sha256": apk_digest,
        "device_fingerprint": fingerprint,
        "tested_at": datetime.now(timezone.utc).isoformat(),
        "operations": ["invalid-input", "create", "complete", "history", "force-stop",
                       "timezone-change", "relaunch", "reopen-history"],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default=os.environ.get("ADB") or str(Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb"))
    parser.add_argument("--apk", type=Path, default=Path("androidApp/build/outputs/apk/debug/androidApp-debug.apk"))
    parser.add_argument("--evidence-dir", type=Path, default=Path(os.environ.get("APPFUSION_J2_EVIDENCE_DIR", "build/android-j2-activity-evidence")))
    args = parser.parse_args()
    journey = ActivityJourney(args.adb, args.apk, args.evidence_dir)
    result = receipt(subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                     hashlib.sha256(args.apk.read_bytes()).hexdigest(),
                     journey.command("shell", "getprop", "ro.build.fingerprint").strip())
    try:
        run_activity_slice(journey)
        result["status"] = "PASS"
        result["screenshot_sha256"] = hashlib.sha256((journey.evidence / "j2-activity-history.png").read_bytes()).hexdigest()
        print("APPFUSION_ANDROID_J2_ACTIVITY_UI_SLICE=PASS")
    except Exception as error:
        result["error"] = str(error)
        try:
            journey.capture()
        except Exception as capture_error:
            result["capture_error"] = str(capture_error)
        raise
    finally:
        result["infrastructure_recoveries"] = journey.infrastructure_recoveries
        (journey.evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
