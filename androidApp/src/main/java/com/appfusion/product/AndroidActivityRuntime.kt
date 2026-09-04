package com.appfusion.product

import android.content.Context
import android.content.pm.ApplicationInfo
import com.appfusion.product.shared.activity.ActivityCadenceRepository
import com.appfusion.product.shared.activity.ActivityReminderCoordinator
import com.appfusion.product.shared.activity.CadenceRule
import com.appfusion.product.shared.activity.ReconciliationReason
import com.appfusion.product.shared.persistence.activityDatabaseBuilder
import com.appfusion.product.shared.persistence.buildActivityDatabase
import java.time.ZoneId
import java.util.UUID

/** Uses the accepted activity database; never opens or replaces the document database. */
class AndroidActivityRuntime(context: Context) {
    private val app = context.applicationContext
    private val database = buildActivityDatabase(activityDatabaseBuilder(app, "appfusion-activities.db"))
    private val repository = ActivityCadenceRepository(database.records())
    private val transport = AndroidReminderTransport(app)
    private val coordinator = ActivityReminderCoordinator(repository, transport)

    suspend fun create(title: String, days: Int, minuteOfDay: Int, zone: String, followDevice: Boolean) {
        repository.create(
            "activity-${UUID.randomUUID()}",
            title,
            CadenceRule(days, minuteOfDay, zone, followDevice),
            System.currentTimeMillis(),
        )
        reconcile(ReconciliationReason.ACTIVITY_CHANGE)
    }

    suspend fun complete(id: String, eventId: String) = repository
        .complete(id, eventId, System.currentTimeMillis())
        .also { reconcile(ReconciliationReason.ACTIVITY_CHANGE) }

    suspend fun completeFromReminder(id: String, eventId: String, scheduleEventId: String) = repository
        .completeFromReminder(id, eventId, scheduleEventId, System.currentTimeMillis())
        .also { reconcile(ReconciliationReason.ACTIVITY_CHANGE) }

    suspend fun snapshot() = repository.snapshot(System.currentTimeMillis(), ZoneId.systemDefault().id)

    suspend fun history(id: String) = repository.history(id)

    suspend fun reconcile(reason: ReconciliationReason) = coordinator
        .reconcile(reason, System.currentTimeMillis(), ZoneId.systemDefault().id)
        .also { transport.recordReconciliation(reason) }

    fun reminderStatus(): AndroidReminderStatus = transport.status()

    fun notificationPermissionGranted(): Boolean = transport.permissionGranted()

    fun debugToolsAvailable(): Boolean =
        (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    suspend fun debugDeliverFirstReminder(): NativeReminderDeliveryOutcome {
        check(debugToolsAvailable()) { "Immediate reminder probe is debug-only" }
        val request = snapshot().firstNotNullOfOrNull { it.request }
            ?: error("Create an enabled activity before testing reminder delivery")
        return transport.debugDelivery(request)
    }
}
