from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AndroidJ2NotificationHarnessTests(unittest.TestCase):
    def test_single_native_transport_is_non_exported_and_uses_no_exact_alarm_privilege(self):
        manifest = (ROOT / "androidApp/src/main/AndroidManifest.xml").read_text()
        transport = (ROOT / "androidApp/src/main/java/com/appfusion/product/AndroidReminderTransport.kt").read_text()
        self.assertIn('android.permission.POST_NOTIFICATIONS', manifest)
        self.assertIn('android.permission.RECEIVE_BOOT_COMPLETED', manifest)
        self.assertNotIn('SCHEDULE_EXACT_ALARM', manifest)
        self.assertNotIn('USE_EXACT_ALARM', manifest)
        self.assertEqual(manifest.count('ReminderAlarmReceiver'), 1)
        self.assertEqual(manifest.count('ReminderActionReceiver'), 1)
        self.assertEqual(manifest.count('ReminderReconciliationReceiver'), 1)
        self.assertGreaterEqual(manifest.count('android:exported="false"'), 4)
        self.assertIn('class AndroidReminderTransport', transport)
        self.assertIn('alarms.setAndAllowWhileIdle(', transport)
        self.assertNotIn('setExact', transport)

    def test_delivery_and_action_ids_are_persistently_idempotent(self):
        transport = (ROOT / "androidApp/src/main/java/com/appfusion/product/AndroidReminderTransport.kt").read_text()
        self.assertIn('if (preferences.getBoolean(deliveredKey, false)) return NativeReminderDeliveryOutcome.ALREADY_POSTED', transport)
        self.assertIn('putBoolean(deliveredKey, true)', transport)
        self.assertIn('DELIVERY_POST_COUNT', transport)
        self.assertIn('notification-action-${ReminderNativeContract.stableToken(stableKey).take(32)}', transport)
        self.assertIn('runtime.completeFromReminder(activityId, actionEventId, scheduleEventId)', transport)

    def test_boot_clock_and_timezone_reconciliation_are_wired_to_shared_reason_codes(self):
        manifest = (ROOT / "androidApp/src/main/AndroidManifest.xml").read_text()
        transport = (ROOT / "androidApp/src/main/java/com/appfusion/product/AndroidReminderTransport.kt").read_text()
        for action in ('BOOT_COMPLETED', 'TIMEZONE_CHANGED', 'TIME_SET', 'DATE_CHANGED'):
            self.assertIn(action, manifest)
        for reason in ('ReconciliationReason.BOOT', 'ReconciliationReason.TIME_ZONE_CHANGE', 'ReconciliationReason.CLOCK_CHANGE'):
            self.assertIn(reason, transport)

    def test_installed_driver_requires_alarm_delivery_dedup_and_process_death_reconciliation(self):
        script = (ROOT / "scripts/android_j2_activity_ui_smoke.py").read_text()
        self.assertIn('dumpsys", "alarm', script)
        self.assertIn('dumpsys", "notification", "--noredact', script)
        self.assertIn('"am", "kill", PACKAGE', script)
        self.assertIn('TIME_ZONE_CHANGE', script)
        self.assertIn('delivery_post_count', script)
        self.assertIn('result["native_notification_delivery_accepted"] = True', script)
        self.assertIn('result["android_j2_criterion"] = "PASS"', script)
        self.assertIn('"j2_fully_accepted": False', script)
        self.assertNotIn('result["j2_fully_accepted"] = True', script)


if __name__ == "__main__":
    unittest.main()
