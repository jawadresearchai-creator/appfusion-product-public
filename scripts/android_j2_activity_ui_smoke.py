"""Installed Android J2 journey: activity persistence plus native reminder transport acceptance."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

from android_j1_ui_smoke import AndroidJourney, PACKAGE

TITLE = "J2Activity42"


class ActivityJourney(AndroidJourney):
    def center(self, mode: str, needle: str, *, require_enabled: bool = True) -> tuple[int, int] | None:
        center = super().center(mode, needle, require_enabled=require_enabled)
        # UIAutomator can report a one-pixel sliver behind system navigation as visible.
        if center is not None and not (32 <= center[1] <= self.height - 64):
            return None
        return center

    def tap_text(self, text: str) -> None:
        x, y = self.find("text", text)
        self.command("shell", "input", "tap", str(x), str(y))

    def scroll_to_top(self) -> None:
        # The inherited finder scrolls toward later content. Reminder status lives above
        # the activity list, so explicitly restore the viewport before asserting it.
        x = str(self.width // 2)
        for _ in range(3):
            self.command(
                "shell", "input", "swipe", x, str(int(self.height * .30)),
                x, str(int(self.height * .82)), "250",
            )
            time.sleep(.25)

    def reminder_preferences(self) -> str:
        return self.command(
            "shell", "run-as", PACKAGE, "cat", "shared_prefs/appfusion-reminders.xml"
        )

    def capture(self) -> None:
        super().capture()
        (self.evidence / "j1-opened-document.png").replace(self.evidence / "j2-android-reminder.png")


def run_activity_foundation(journey: ActivityJourney) -> None:
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
        journey.command("shell", "input", "swipe", str(journey.width // 2), "180",
                        str(journey.width // 2), str(journey.height - 100), "300")
        journey.tap("activity_title")
        journey.command("shell", "input", "text", TITLE)
        journey.command("shell", "input", "keyevent", "111")
        journey.tap("activity_follow_zone", scroll=True)
        journey.tap("activity_save", scroll=True)
        journey.find("text", "1 activity record(s)", scroll=True)
        journey.find("text", "Completed: 0", scroll=True)
        journey.scroll_to_top()
        journey.find("text", "scheduled: 1")
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
    finally:
        journey.command("shell", "cmd", "alarm", "set-timezone", original_zone)


def run_native_reminder_acceptance(journey: ActivityJourney) -> dict:
    # Fresh install means Android 13+ notification permission starts denied.
    journey.tap("activity_enable_notifications", scroll=True)
    journey.tap_text("Allow")
    journey.find("text", "Notification permission granted", scroll=True)
    journey.find("text", "Notifications: permission granted", scroll=True)

    alarm_state = journey.command("shell", "dumpsys", "alarm")
    if PACKAGE not in alarm_state or "REMINDER_DELIVER" not in alarm_state:
        raise RuntimeError("Activity reminder was not registered with Android AlarmManager")

    # Kill the background process without force-stopping the package. A system timezone
    # broadcast must reconstruct the one-domain schedule from the durable shared database.
    current_zone = journey.command("shell", "getprop", "persist.sys.timezone").strip()
    target_zone = "Pacific/Auckland" if current_zone != "Pacific/Auckland" else "Asia/Tokyo"
    journey.command("shell", "input", "keyevent", "3")
    time.sleep(1)
    journey.command("shell", "am", "kill", PACKAGE)
    journey.command("shell", "cmd", "alarm", "set-timezone", target_zone)
    deadline = time.monotonic() + 30
    reconciliation_prefs = ""
    while time.monotonic() < deadline:
        reconciliation_prefs = journey.reminder_preferences()
        if "TIME_ZONE_CHANGE" in reconciliation_prefs:
            break
        time.sleep(1)
    if "TIME_ZONE_CHANGE" not in reconciliation_prefs:
        raise RuntimeError("Timezone reconciliation receipt was not persisted after process death")
    reconciled_alarm_state = journey.command("shell", "dumpsys", "alarm")
    if PACKAGE not in reconciled_alarm_state or "REMINDER_DELIVER" not in reconciled_alarm_state:
        raise RuntimeError("Reminder alarm was not present after timezone reconciliation")

    journey.start()
    journey.tap("open_activities", scroll=True)
    journey.find("text", "1 activity record(s)", scroll=True)
    journey.find("text", "Completed: 1", scroll=True)

    journey.tap("activity_test_notification", scroll=True)
    journey.find("text", "native notification", scroll=True)
    journey.find("text", "delivery posts: 1", scroll=True)
    notification_state = journey.command("shell", "dumpsys", "notification", "--noredact")
    if PACKAGE not in notification_state or "Activity reminder" not in notification_state:
        raise RuntimeError("NotificationManager did not retain the posted AppFusion reminder")

    # Replaying the same outstanding occurrence must not post a second native notification.
    journey.tap("activity_test_notification", scroll=True)
    journey.find("text", "deduplicated", scroll=True)
    journey.find("text", "delivery posts: 1", scroll=True)
    dedup_prefs = journey.reminder_preferences()
    if 'name="delivery_post_count" value="1"' not in dedup_prefs:
        raise RuntimeError("Persistent native reminder deduplication count is not one")

    journey.capture()
    journey.command("shell", "cmd", "alarm", "set-timezone", current_zone)
    return {
        "alarm_registration_verified": True,
        "timezone_reconciliation_verified": True,
        "native_notification_post_verified": True,
        "persistent_delivery_dedup_verified": True,
        "notification_state_excerpt": "Activity reminder present for com.appfusion.product",
    }


def receipt(source: str, apk_digest: str, fingerprint: str) -> dict:
    return {
        "journey": "J2_ANDROID_UI",
        "status": "FAIL",
        "android_j2_criterion": "IN_PROGRESS",
        "native_notification_delivery_accepted": False,
        "j2_fully_accepted": False,
        "source_commit": source,
        "apk_sha256": apk_digest,
        "device_fingerprint": fingerprint,
        "tested_at": datetime.now(timezone.utc).isoformat(),
        "operations": [
            "invalid-input", "create", "complete", "history", "force-stop", "timezone-change",
            "relaunch", "permission", "alarm-registration", "process-kill", "timezone-reconcile",
            "native-notification-post", "persistent-delivery-dedup",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default=os.environ.get("ADB") or str(Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb"))
    parser.add_argument("--apk", type=Path, default=Path("androidApp/build/outputs/apk/debug/androidApp-debug.apk"))
    parser.add_argument("--evidence-dir", type=Path, default=Path(os.environ.get("APPFUSION_J2_EVIDENCE_DIR", "build/android-j2-activity-evidence")))
    args = parser.parse_args()
    journey = ActivityJourney(args.adb, args.apk, args.evidence_dir)
    result = receipt(
        subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        journey.command("shell", "getprop", "ro.build.fingerprint").strip(),
    )
    try:
        run_activity_foundation(journey)
        native = run_native_reminder_acceptance(journey)
        result.update(native)
        result["status"] = "PASS"
        result["android_j2_criterion"] = "PASS"
        result["native_notification_delivery_accepted"] = True
        result["screenshot_sha256"] = hashlib.sha256(
            (journey.evidence / "j2-android-reminder.png").read_bytes()
        ).hexdigest()
        print("APPFUSION_ANDROID_J2=PASS")
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
