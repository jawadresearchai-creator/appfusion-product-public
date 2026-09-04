package com.appfusion.product

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.appfusion.product.shared.EntityDomain
import com.appfusion.product.shared.ScheduleRequest
import com.appfusion.product.shared.activity.ReminderTransport
import com.appfusion.product.shared.activity.ReconciliationReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

private object ReminderNativeContract {
    const val CHANNEL_ID = "appfusion-activity-reminders"
    const val CHANNEL_NAME = "Activity reminders"
    const val PREFS = "appfusion-reminders"
    const val ACTION_DELIVER = "com.appfusion.product.REMINDER_DELIVER"
    const val ACTION_COMPLETE = "com.appfusion.product.REMINDER_COMPLETE"
    const val EXTRA_ACTIVITY_ID = "activity_id"
    const val EXTRA_SCHEDULE_EVENT_ID = "schedule_event_id"
    const val EXTRA_STABLE_KEY = "stable_key"
    const val ACTIVE_KEYS = "active_keys"
    const val LAST_RECONCILIATION = "last_reconciliation"
    const val LAST_RECONCILIATION_AT = "last_reconciliation_at"
    const val LAST_DELIVERY_KEY = "last_delivery_key"
    const val LAST_DELIVERY_AT = "last_delivery_at"
    const val DELIVERY_POST_COUNT = "delivery_post_count"
    const val LAST_ACTION_OUTCOME = "last_action_outcome"
    const val NOTIFICATION_TAG = "appfusion-activity-reminder"
    const val DELIVERED_PREFIX = "delivered:"

    fun stableToken(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun requestCode(value: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        var result = 0
        repeat(4) { index -> result = (result shl 8) or (digest[index].toInt() and 0xff) }
        return result and 0x7fffffff
    }

    fun reminderUri(stableKey: String, suffix: String = "deliver"): Uri = Uri.Builder()
        .scheme("appfusion")
        .authority("activity-reminder")
        .appendPath(suffix)
        .appendPath(stableKey)
        .build()
}

enum class NativeReminderDeliveryOutcome { POSTED, ALREADY_POSTED, PERMISSION_DENIED }

data class AndroidReminderStatus(
    val permissionGranted: Boolean,
    val scheduledCount: Int,
    val lastReconciliation: String?,
    val lastDeliveryKey: String?,
    val deliveryPostCount: Int,
    val lastActionOutcome: String?,
)

/** The only Android scheduling transport. Domain cadence remains in the accepted shared repository. */
class AndroidReminderTransport(private val context: Context) : ReminderTransport {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val preferences get() = app.getSharedPreferences(ReminderNativeContract.PREFS, Context.MODE_PRIVATE)

    override suspend fun replaceDomainRequests(domain: EntityDomain, requests: List<ScheduleRequest>) {
        require(domain == EntityDomain.ACTIVITY) { "Android activity transport cannot replace another domain" }
        ensureNotificationChannel(app)
        val requested = requests.associateBy { it.stableTransportKey }
        val previous = preferences.getStringSet(ReminderNativeContract.ACTIVE_KEYS, emptySet()).orEmpty().toSet()
        (previous - requested.keys).forEach(::cancel)
        requests.forEach(::schedule)
        check(preferences.edit().putStringSet(ReminderNativeContract.ACTIVE_KEYS, requested.keys).commit()) {
            "Could not persist reminder transport projection"
        }
    }

    fun permissionGranted(): Boolean = notificationPermissionGranted(app)

    fun recordReconciliation(reason: ReconciliationReason) {
        check(preferences.edit()
            .putString(ReminderNativeContract.LAST_RECONCILIATION, reason.name)
            .putLong(ReminderNativeContract.LAST_RECONCILIATION_AT, System.currentTimeMillis())
            .commit()) { "Could not persist reminder reconciliation receipt" }
    }

    fun status(): AndroidReminderStatus = AndroidReminderStatus(
        permissionGranted = permissionGranted(),
        scheduledCount = preferences.getStringSet(ReminderNativeContract.ACTIVE_KEYS, emptySet()).orEmpty().size,
        lastReconciliation = preferences.getString(ReminderNativeContract.LAST_RECONCILIATION, null),
        lastDeliveryKey = preferences.getString(ReminderNativeContract.LAST_DELIVERY_KEY, null),
        deliveryPostCount = preferences.getInt(ReminderNativeContract.DELIVERY_POST_COUNT, 0),
        lastActionOutcome = preferences.getString(ReminderNativeContract.LAST_ACTION_OUTCOME, null),
    )

    fun debugDelivery(request: ScheduleRequest): NativeReminderDeliveryOutcome {
        check((app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "Immediate delivery probe is debug-only"
        }
        return ReminderNativeDelivery.post(
            app,
            request.stableTransportKey,
            request.owner.id,
            request.eventId,
        )
    }

    private fun schedule(request: ScheduleRequest) {
        val triggerAt = maxOf(request.triggerAtEpochMillis, System.currentTimeMillis() + 1_000L)
        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            deliveryPendingIntent(app, request, PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    private fun cancel(stableKey: String) {
        val pending = PendingIntent.getBroadcast(
            app,
            ReminderNativeContract.requestCode(stableKey),
            Intent(app, ReminderAlarmReceiver::class.java)
                .setAction(ReminderNativeContract.ACTION_DELIVER)
                .setData(ReminderNativeContract.reminderUri(stableKey)),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pending != null) {
            alarms.cancel(pending)
            pending.cancel()
        }
    }
}

private object ReminderNativeDelivery {
    fun post(
        context: Context,
        stableKey: String,
        activityId: String,
        scheduleEventId: String,
    ): NativeReminderDeliveryOutcome {
        if (!notificationPermissionGranted(context)) return NativeReminderDeliveryOutcome.PERMISSION_DENIED
        val preferences = context.getSharedPreferences(ReminderNativeContract.PREFS, Context.MODE_PRIVATE)
        val deliveredKey = ReminderNativeContract.DELIVERED_PREFIX + ReminderNativeContract.stableToken(stableKey)
        if (preferences.getBoolean(deliveredKey, false)) return NativeReminderDeliveryOutcome.ALREADY_POSTED

        ensureNotificationChannel(context)
        val completeIntent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(ReminderNativeContract.ACTION_COMPLETE)
            .setData(ReminderNativeContract.reminderUri(stableKey, "complete"))
            .putExtra(ReminderNativeContract.EXTRA_ACTIVITY_ID, activityId)
            .putExtra(ReminderNativeContract.EXTRA_SCHEDULE_EVENT_ID, scheduleEventId)
            .putExtra(ReminderNativeContract.EXTRA_STABLE_KEY, stableKey)
        val completePending = PendingIntent.getBroadcast(
            context,
            ReminderNativeContract.requestCode("complete:$stableKey"),
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentPending = PendingIntent.getActivity(
            context,
            ReminderNativeContract.requestCode("open:$stableKey"),
            Intent(context, ActivityHistoryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, ReminderNativeContract.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Activity reminder")
            .setContentText("An AppFusion activity is due.")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(contentPending)
            .addAction(Notification.Action.Builder(null, "Complete", completePending).build())
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(
            ReminderNativeContract.NOTIFICATION_TAG,
            ReminderNativeContract.requestCode(stableKey),
            notification,
        )
        val posted = preferences.getInt(ReminderNativeContract.DELIVERY_POST_COUNT, 0) + 1
        check(preferences.edit()
            .putBoolean(deliveredKey, true)
            .putString(ReminderNativeContract.LAST_DELIVERY_KEY, stableKey)
            .putLong(ReminderNativeContract.LAST_DELIVERY_AT, System.currentTimeMillis())
            .putInt(ReminderNativeContract.DELIVERY_POST_COUNT, posted)
            .commit()) { "Could not persist reminder delivery receipt" }
        return NativeReminderDeliveryOutcome.POSTED
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderNativeContract.ACTION_DELIVER) return
        val stableKey = intent.getStringExtra(ReminderNativeContract.EXTRA_STABLE_KEY) ?: return
        val activityId = intent.getStringExtra(ReminderNativeContract.EXTRA_ACTIVITY_ID) ?: return
        val scheduleEventId = intent.getStringExtra(ReminderNativeContract.EXTRA_SCHEDULE_EVENT_ID) ?: return
        ReminderNativeDelivery.post(context.applicationContext, stableKey, activityId, scheduleEventId)
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderNativeContract.ACTION_COMPLETE) return
        val activityId = intent.getStringExtra(ReminderNativeContract.EXTRA_ACTIVITY_ID) ?: return
        val scheduleEventId = intent.getStringExtra(ReminderNativeContract.EXTRA_SCHEDULE_EVENT_ID) ?: return
        val stableKey = intent.getStringExtra(ReminderNativeContract.EXTRA_STABLE_KEY) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val runtime = (context.applicationContext as FusionApplication).activityRuntime
                val actionEventId = "notification-action-${ReminderNativeContract.stableToken(stableKey).take(32)}"
                val outcome = runtime.completeFromReminder(activityId, actionEventId, scheduleEventId)
                context.getSharedPreferences(ReminderNativeContract.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(ReminderNativeContract.LAST_ACTION_OUTCOME, outcome.name).commit()
            } catch (failure: Exception) {
                context.getSharedPreferences(ReminderNativeContract.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(ReminderNativeContract.LAST_ACTION_OUTCOME, "ERROR:${failure::class.simpleName}").commit()
            } finally {
                pending.finish()
            }
        }
    }
}

class ReminderReconciliationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ReconciliationReason.BOOT
            Intent.ACTION_TIMEZONE_CHANGED -> ReconciliationReason.TIME_ZONE_CHANGE
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED -> ReconciliationReason.CLOCK_CHANGE
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                (context.applicationContext as FusionApplication).activityRuntime.reconcile(reason)
            } finally {
                pending.finish()
            }
        }
    }
}

private fun deliveryPendingIntent(context: Context, request: ScheduleRequest, mode: Int): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        ReminderNativeContract.requestCode(request.stableTransportKey),
        Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ReminderNativeContract.ACTION_DELIVER)
            .setData(ReminderNativeContract.reminderUri(request.stableTransportKey))
            .putExtra(ReminderNativeContract.EXTRA_ACTIVITY_ID, request.owner.id)
            .putExtra(ReminderNativeContract.EXTRA_SCHEDULE_EVENT_ID, request.eventId)
            .putExtra(ReminderNativeContract.EXTRA_STABLE_KEY, request.stableTransportKey),
        mode or PendingIntent.FLAG_IMMUTABLE,
    )

private fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < 26) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(
        ReminderNativeContract.CHANNEL_ID,
        ReminderNativeContract.CHANNEL_NAME,
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = "Local activity reminders"
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    })
}

private fun notificationPermissionGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
