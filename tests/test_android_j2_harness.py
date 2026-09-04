import sys
from pathlib import Path
import unittest
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from android_j2_activity_ui_smoke import ActivityJourney, receipt


class AndroidJ2HarnessTests(unittest.TestCase):
    def test_navigation_edge_sliver_is_scrolled_not_tapped(self):
        with tempfile.TemporaryDirectory() as directory:
            journey = object.__new__(ActivityJourney)
            journey.height = 800
            journey.xml_path = Path(directory) / "window.xml"
            for bounds, expected in (("[22,798][458,800]", None), ("[22,600][458,652]", (240, 626))):
                journey.xml_path.write_text(
                    '<hierarchy><node resource-id="history" enabled="true" '
                    f'bounds="{bounds}" /></hierarchy>', encoding="utf-8",
                )
                self.assertEqual(journey.center("id", "history"), expected)
                self.assertEqual(journey.center("id", "history", require_enabled=False), expected)

    def test_receipt_starts_fail_closed_and_never_claims_full_j2(self):
        result = receipt("commit", "digest", "emulator")
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["android_j2_criterion"], "IN_PROGRESS")
        self.assertFalse(result["native_notification_delivery_accepted"])
        self.assertFalse(result["j2_fully_accepted"])
        self.assertIn("timezone-change", result["operations"])
        self.assertIn("force-stop", result["operations"])

    def test_existing_guarded_workflow_runs_j1_and_j2_android_journeys(self):
        workflow = (ROOT / ".github/workflows/product-construction-ci.yml").read_text()
        self.assertIn("run: python3 scripts/android_j1_ui_smoke.py", workflow)
        self.assertIn("run: python3 scripts/android_j2_activity_ui_smoke.py", workflow)
        self.assertIn("scripts/android_j2_activity_ui_smoke.py)", workflow)

    def test_activity_is_internal_and_connected_only_to_the_single_native_transport(self):
        manifest = (ROOT / "androidApp/src/main/AndroidManifest.xml").read_text()
        self.assertIn('android:name=".ActivityHistoryActivity" android:exported="false"', manifest)
        ui = (ROOT / "androidApp/src/main/java/com/appfusion/product/ActivityHistoryActivity.kt").read_text()
        runtime = (ROOT / "androidApp/src/main/java/com/appfusion/product/AndroidActivityRuntime.kt").read_text()
        self.assertIn("One shared cadence plan drives the local notification transport.", ui)
        self.assertIn("ActivityReminderCoordinator(repository, transport)", runtime)
        self.assertIn("catch (cancelled: CancellationException) { throw cancelled }", ui)


if __name__ == "__main__":
    unittest.main()
