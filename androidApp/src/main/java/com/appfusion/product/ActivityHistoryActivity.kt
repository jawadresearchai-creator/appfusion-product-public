package com.appfusion.product

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.appfusion.product.shared.activity.ActivityCadenceSnapshot
import com.appfusion.product.shared.activity.ReconciliationReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Installed J2 Android surface backed by the accepted shared cadence repository and one native reminder transport. */
class ActivityHistoryActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = (application as FusionApplication).activityRuntime
    private lateinit var activityTitleInput: EditText
    private lateinit var days: EditText
    private lateinit var time: EditText
    private lateinit var zone: EditText
    private lateinit var follow: CheckBox
    private lateinit var status: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var enableNotifications: Button
    private lateinit var debugDelivery: Button
    private lateinit var items: LinearLayout
    private lateinit var page: LinearLayout
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 247, 245))
        }
        page.addView(label("Activities & reminders", 26f))
        page.addView(label("One shared cadence plan drives the local notification transport.", 14f))
        notificationStatus = label("Checking notification permission and schedule…", 14f).apply {
            id = R.id.activity_notification_status
        }
        page.addView(notificationStatus)
        enableNotifications = button("Enable notifications", R.id.activity_enable_notifications) {
            requestNotificationPermission()
        }
        page.addView(enableNotifications)
        debugDelivery = button("Test reminder delivery now", R.id.activity_test_notification) {
            testReminderDelivery()
        }.apply {
            visibility = if (runtime.debugToolsAvailable()) View.VISIBLE else View.GONE
        }
        page.addView(debugDelivery)

        activityTitleInput = input("Activity title", R.id.activity_title)
        days = input("Every 1–3650 calendar days", R.id.activity_days, "1").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        time = input("Reminder time (HH:mm)", R.id.activity_time, "09:00")
        zone = input("Time zone", R.id.activity_zone, ZoneId.systemDefault().id)
        follow = CheckBox(this).apply {
            id = R.id.activity_follow_zone
            text = "Follow device time zone"
            setOnCheckedChangeListener { _, checked -> zone.isEnabled = !checked && !busy }
        }
        listOf(activityTitleInput, days, time, zone, follow).forEach(page::addView)
        page.addView(button("Create activity", R.id.activity_save) { create() })
        status = label("Loading activities…", 14f).apply { id = R.id.activity_status }
        page.addView(status)
        items = LinearLayout(this).apply { id = R.id.activity_list; orientation = LinearLayout.VERTICAL }
        page.addView(items)
        setContentView(ScrollView(this).apply {
            addView(page)
            setOnApplyWindowInsetsListener { _, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    top = bars.top; bottom = bars.bottom
                } else {
                    top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom
                }
                page.setPadding(dp(22), dp(18) + top, dp(22), dp(18) + bottom)
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!busy) perform {
            withContext(Dispatchers.IO) { runtime.reconcile(ReconciliationReason.STARTUP) }
            render(withContext(Dispatchers.IO) { runtime.snapshot() })
            updateReminderStatus()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        perform {
            withContext(Dispatchers.IO) { runtime.reconcile(ReconciliationReason.ACTIVITY_CHANGE) }
            updateReminderStatus()
            status.text = if (runtime.notificationPermissionGranted()) {
                "Notification permission granted."
            } else {
                "Notification permission is required before reminders can be displayed."
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !runtime.notificationPermissionGranted()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        perform {
            withContext(Dispatchers.IO) { runtime.reconcile(ReconciliationReason.ACTIVITY_CHANGE) }
            updateReminderStatus()
            status.text = "Notification permission is already granted."
        }
    }

    private fun testReminderDelivery() {
        perform {
            val outcome = withContext(Dispatchers.IO) { runtime.debugDeliverFirstReminder() }
            updateReminderStatus()
            status.text = when (outcome) {
                NativeReminderDeliveryOutcome.POSTED -> "Reminder delivery probe posted a native notification."
                NativeReminderDeliveryOutcome.ALREADY_POSTED -> "Reminder delivery probe was deduplicated."
                NativeReminderDeliveryOutcome.PERMISSION_DENIED -> "Notification permission is required before delivery."
            }
        }
    }

    private fun create() {
        val activityTitle = activityTitleInput.text.toString().trim()
        val interval = days.text.toString().toIntOrNull()
        val parsedTime = runCatching { LocalTime.parse(time.text.toString(), DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
        if (interval == null || interval !in 1..3650 || parsedTime == null ||
            !Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(time.text.toString()) ||
            activityTitle.isBlank() || activityTitle.length > 200) {
            status.text = "Enter a title, 1–3650 days, and a valid HH:mm time."
            return
        }
        val followDevice = follow.isChecked
        val selectedZone = if (followDevice) ZoneId.systemDefault().id else zone.text.toString().trim()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(activityTitleInput.windowToken, 0)
        perform {
            withContext(Dispatchers.IO) {
                runtime.create(activityTitle, interval, parsedTime.hour * 60 + parsedTime.minute, selectedZone, followDevice)
            }
            activityTitleInput.text.clear()
            render(withContext(Dispatchers.IO) { runtime.snapshot() })
            updateReminderStatus()
        }
    }

    private fun render(records: List<ActivityCadenceSnapshot>) {
        items.removeAllViews()
        status.text = "${records.size} activity record(s) · Device zone: ${ZoneId.systemDefault().id}"
        records.forEach { item ->
            items.addView(label(item.title, 20f))
            items.addView(label("Completed: ${item.completedCount}", 16f))
            items.addView(label("Last completed: ${item.lastCompletedAtEpochMillis?.let(::formatInstant) ?: "Never"}", 14f))
            items.addView(label("Next due: ${item.dueAtEpochMillis?.let { formatInstant(it, item.timeZoneId) } ?: "Not configured"}", 14f))
            items.addView(label("Cadence state: ${item.dueState}", 14f))
            if (item.dueAtEpochMillis != null) {
                val eventId = "manual-${UUID.randomUUID()}"
                items.addView(button("Record completion", R.id.activity_complete) {
                    perform {
                        withContext(Dispatchers.IO) { runtime.complete(item.ref.id, eventId) }
                        render(withContext(Dispatchers.IO) { runtime.snapshot() })
                        updateReminderStatus()
                    }
                })
            }
            items.addView(button("Completion history", R.id.activity_history) {
                perform {
                    val history = withContext(Dispatchers.IO) { runtime.history(item.ref.id) }
                    AlertDialog.Builder(this@ActivityHistoryActivity).setTitle("${item.title} history")
                        .setMessage(if (history.isEmpty()) "No recorded completions." else
                            history.joinToString("\n") { formatInstant(it.occurredAtEpochMillis) })
                        .setPositiveButton("Done", null).show()
                }
            })
        }
    }

    private fun updateReminderStatus() {
        val reminder = runtime.reminderStatus()
        notificationStatus.text = buildString {
            append("Notifications: ")
            append(if (reminder.permissionGranted) "permission granted" else "permission required")
            append(" · scheduled: ${reminder.scheduledCount}")
            append(" · last reconciliation: ${reminder.lastReconciliation ?: "none"}")
            append(" · delivery posts: ${reminder.deliveryPostCount}")
            if (reminder.lastDeliveryKey != null) append(" · last delivery recorded")
            if (reminder.lastActionOutcome != null) append(" · action: ${reminder.lastActionOutcome}")
        }
        enableNotifications.isEnabled = !busy && !reminder.permissionGranted
    }

    private fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        setControlsEnabled(page, false)
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status.text = failure.message ?: "Activity operation failed." }
            finally {
                busy = false
                setControlsEnabled(page, true)
                zone.isEnabled = !follow.isChecked
                enableNotifications.isEnabled = !runtime.notificationPermissionGranted()
            }
        }
    }

    private fun setControlsEnabled(view: View, enabled: Boolean) {
        if (view is Button || view is EditText) view.isEnabled = enabled
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) setControlsEnabled(view.getChildAt(i), enabled)
    }

    private fun formatInstant(epoch: Long, zoneId: String? = null): String =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm VV")
            .withZone(ZoneId.of(zoneId ?: ZoneId.systemDefault().id)).format(Instant.ofEpochMilli(epoch))

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(21, 60, 53)); setPadding(0, dp(8), 0, dp(8))
    }
    private fun input(hintText: String, resourceId: Int, initial: String = "") = EditText(this).apply {
        id = resourceId; hint = hintText; setSingleLine(true); setText(initial)
        setBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }
    private fun button(value: String, resourceId: Int, action: () -> Unit) = Button(this).apply {
        id = resourceId; text = value; isAllCaps = false; setOnClickListener { if (!busy) action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 4202
    }
}
