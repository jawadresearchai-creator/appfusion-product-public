from pathlib import Path
import tempfile
import unittest

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


if __name__ == "__main__":
    unittest.main()
