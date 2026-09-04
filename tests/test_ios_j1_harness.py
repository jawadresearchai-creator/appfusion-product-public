from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class IosJ1HarnessTests(unittest.TestCase):
    def test_ui_gate_is_explicit_not_a_recursive_xcode_build_phase(self):
        workflow = (ROOT / ".github/workflows/product-construction-ci.yml").read_text()
        project = (ROOT / "iosApp/AppFusion.xcodeproj/project.pbxproj").read_text()
        self.assertIn("run: bash scripts/ios_j1_ui_ci.sh", workflow)
        self.assertIn("iosApp/*|iosProbe/*|scripts/ios_j1_ui_ci.sh", workflow)
        self.assertNotIn("PBXShellScriptBuildPhase", project)
        self.assertIn("name: Preserve iOS Journey J1 evidence even on failure\n        if: always()", workflow)

    def test_installed_named_test_must_pass_before_packaging(self):
        script = (ROOT / "scripts/ios_j1_ui_ci.sh").read_text()
        self.assertIn("set -euo pipefail", script)
        self.assertIn("clean test 2>&1 | tee", script)
        self.assertIn("testEncryptedDocumentSurvivesTerminationSearchAndReopen]' passed", script)
        self.assertLess(script.index("grep -F \"Test Case"), script.index("ditto -c -k"))
        self.assertNotIn("rm -rf", script)
        self.assertNotIn("APPFUSION_SKIP_NESTED_J1", script)

    def test_scheme_has_an_enabled_ui_test_target(self):
        scheme = ET.parse(ROOT / "iosApp/AppFusion.xcodeproj/xcshareddata/xcschemes/AppFusion.xcscheme")
        tests = scheme.findall("./TestAction/Testables/TestableReference")
        self.assertEqual(len(tests), 1)
        self.assertEqual(tests[0].get("skipped"), "NO")
        self.assertEqual(tests[0].find("BuildableReference").get("BlueprintName"), "AppFusionUITests")

    def test_keyboard_is_dismissed_before_save_and_search(self):
        ui = (ROOT / "iosApp/AppFusion/AppDelegate.swift").read_text()
        test = (ROOT / "iosApp/AppFusionUITests/JourneyJ1UITests.swift").read_text()
        self.assertIn("view.keyboardLayoutGuide.topAnchor", ui)
        self.assertIn('done.accessibilityIdentifier = "dismiss-keyboard"', ui)
        self.assertEqual(test.count("dismissKeyboard(in: app)"), 2)

    def test_mutable_shared_vault_operations_use_one_serial_queue(self):
        ui = (ROOT / "iosApp/AppFusion/AppDelegate.swift").read_text()
        self.assertNotIn("DispatchQueue.global", ui)
        self.assertIn('DispatchQueue(label: "com.appfusion.product.vault"', ui)
        self.assertEqual(ui.count("vaultQueue.async { [weak self] in"), 4)
        self.assertIn("vaultQueue.async { runtime?.closeVault() }", ui)
