package com.appfusion.product

import android.app.Activity
import android.app.AlertDialog
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

/** J2 activity UI foundation. Native notification transport is deliberately not claimed here. */
class ActivityHistoryActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = (application as FusionApplication).activityRuntime
    private lateinit var activityTitleInput: EditText
    private lateinit var days: EditText
    private lateinit var time: EditText
    private lateinit var zone: EditText
    private lateinit var follow: CheckBox
    private lateinit var status: TextView
    private lateinit var items: LinearLayout
    private lateinit var page: LinearLayout
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 247, 245))
        }
        page.addView(label("Activities & cadence", 26f))
        page.addView(label("Cadence preview only. Device notifications are not connected yet.", 14f))
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
        if (!busy) perform { render(withContext(Dispatchers.IO) { runtime.snapshot() }) }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun create() {
        val activityTitle = activityTitleInput.text.toString().trim()
        val interval = days.text.toString().toIntOrNull()
        val parsedTime = runCatching { LocalTime.parse(time.text.toString(), DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
        // Strict text validation avoids SMART parsing accepting 24:00 as midnight.
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
                // One rendered action has one durable idempotency key, even if a callback is repeated.
                val eventId = "manual-${UUID.randomUUID()}"
                items.addView(button("Record completion", R.id.activity_complete) {
                    perform {
                        withContext(Dispatchers.IO) { runtime.complete(item.ref.id, eventId) }
                        render(withContext(Dispatchers.IO) { runtime.snapshot() })
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

    private fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        setControlsEnabled(page, false)
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status.text = failure.message ?: "Activity operation failed." }
            finally { busy = false; setControlsEnabled(page, true); zone.isEnabled = !follow.isChecked }
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
}
