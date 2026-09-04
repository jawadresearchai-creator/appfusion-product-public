from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.android_j1_ui_smoke import AndroidJourney


class AndroidJ1HarnessTest(unittest.TestCase):
    def test_resource_lookup_does_not_tap_the_query_instead_of_the_result(self):
        with tempfile.TemporaryDirectory() as directory:
            hierarchy = Path(directory) / "window.xml"
            hierarchy.write_text(
                '<hierarchy>'
                '<node resource-id="com.appfusion.product:id/search_query" '
                'text="J1EncryptedNote42" enabled="true" bounds="[0,0][100,40]" />'
                '<node resource-id="com.appfusion.product:id/search_result_item" '
                'text="J1EncryptedNote42" enabled="true" bounds="[20,80][180,140]" />'
                '</hierarchy>', encoding="utf-8",
            )
            journey = object.__new__(AndroidJourney)
            journey.xml_path = hierarchy
            self.assertEqual(
                (100, 110),
                journey.center("id", "com.appfusion.product:id/search_result_item"),
            )

    def test_disabled_and_zero_size_nodes_cannot_pass_the_visible_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            hierarchy = Path(directory) / "window.xml"
            hierarchy.write_text(
                '<hierarchy>'
                '<node text="PrivateBodyAlpha42" enabled="false" bounds="[0,0][100,40]" />'
                '<node text="PrivateBodyAlpha42" enabled="true" bounds="[0,0][0,0]" />'
                '</hierarchy>', encoding="utf-8",
            )
            journey = object.__new__(AndroidJourney)
            journey.xml_path = hierarchy
            self.assertIsNone(journey.center("text", "PrivateBodyAlpha42"))

    def test_only_observed_launcher_anr_is_recoverable_once(self):
        for title in ("Pixel Launcher isn't responding", "AppFusion isn't responding", "Other app isn't responding"):
            with self.subTest(title=title), tempfile.TemporaryDirectory() as directory:
                journey = object.__new__(AndroidJourney)
                journey.evidence = Path(directory)
                journey.xml_path = journey.evidence / "window.xml"
                journey.infrastructure_recoveries = []
                calls = []
                journey.command = lambda *args: calls.append(args)
                journey.xml_path.write_text(
                    '<hierarchy><node package="android" resource-id="android:id/alertTitle" '
                    f'text="{title}" />'
                    '<node resource-id="android:id/aerr_close" enabled="true" bounds="[0,0][100,40]" />'
                    '</hierarchy>', encoding="utf-8",
                )
                with patch("scripts.android_j1_ui_smoke.time.sleep"):
                    recovered = journey.dismiss_known_launcher_anr()
                    self.assertEqual(recovered, title.startswith("Pixel Launcher"))
                    self.assertFalse(journey.dismiss_known_launcher_anr())
                self.assertEqual(len(calls), 1 if recovered else 0)
                self.assertEqual((journey.evidence / "launcher-anr.xml").exists(), recovered)

    def test_unverified_package_cannot_trigger_launcher_recovery(self):
        with tempfile.TemporaryDirectory() as directory:
            journey = object.__new__(AndroidJourney)
            journey.xml_path = Path(directory) / "window.xml"
            journey.infrastructure_recoveries = []
            journey.xml_path.write_text(
                '<hierarchy><node package="com.appfusion.product" resource-id="android:id/alertTitle" '
                'text="Pixel Launcher isn\'t responding" /></hierarchy>', encoding="utf-8",
            )
            self.assertFalse(journey.dismiss_known_launcher_anr())


if __name__ == "__main__":
    unittest.main()
