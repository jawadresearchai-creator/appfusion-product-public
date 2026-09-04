package com.appfusion.product.shared.activity

import com.appfusion.product.shared.EntityDomain
import com.appfusion.product.shared.EntityRef
import com.appfusion.product.shared.MissedTriggerPolicy
import com.appfusion.product.shared.ScheduleRequest
import com.appfusion.product.shared.TimeZoneSemantics
import com.appfusion.product.shared.persistence.ActivityCadenceEntity
import com.appfusion.product.shared.persistence.ActivityCompletionEntity
import com.appfusion.product.shared.persistence.ActivityRecordDao
import com.appfusion.product.shared.persistence.ActivityRecordEntity
import com.appfusion.product.shared.persistence.ActivityStoredState
import com.appfusion.product.shared.persistence.CompletionOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

private const val MAX_ACTIVITY_TIME = 253_402_300_799_999L // End of civil year 9999.

data class CadenceRule(
    val everyDays: Int,
    val minuteOfDay: Int,
    val timeZoneId: String,
    val followDeviceTimeZone: Boolean = false,
) {
    init {
        require(everyDays in 1..3650) { "Cadence must be 1 to 3650 calendar days" }
        require(minuteOfDay in 0..1439) { "Reminder time must be a valid minute of day" }
        TimeZone.of(timeZoneId)
    }

    fun resolvedZone(deviceTimeZoneId: String): TimeZone =
        TimeZone.of(if (followDeviceTimeZone) deviceTimeZoneId else timeZoneId)

    /** Gap moves forward by the gap; overlap chooses the earlier instant. Never add 24-hour durations. */
    fun dueAt(anchorEpochMillis: Long, deviceTimeZoneId: String): Long {
        val zone = resolvedZone(deviceTimeZoneId)
        val date = Instant.fromEpochMilliseconds(anchorEpochMillis).toLocalDateTime(zone).date
            .plus(everyDays, DateTimeUnit.DAY)
        return date.atTime(minuteOfDay / 60, minuteOfDay % 60).toInstant(zone).toEpochMilliseconds()
    }
}

enum class ActivityDueState { NOT_CONFIGURED, REMINDERS_OFF, UPCOMING, DUE, OVERDUE }

data class ActivityCadenceSnapshot(
    val ref: EntityRef,
    val title: String,
    val completedCount: Long,
    val lastCompletedAtEpochMillis: Long?,
    val dueAtEpochMillis: Long?,
    val timeZoneId: String?,
    val dueState: ActivityDueState,
    val request: ScheduleRequest?,
)

fun ActivityStoredState.toCadenceSnapshot(nowEpochMillis: Long, deviceTimeZoneId: String): ActivityCadenceSnapshot {
    val owner = EntityRef(EntityDomain.ACTIVITY, record.id)
    val configuration = cadence
    if (configuration == null) return ActivityCadenceSnapshot(
        owner, record.title, record.completedCount, null, null, null, ActivityDueState.NOT_CONFIGURED, null,
    )
    val rule = CadenceRule(configuration.everyDays, configuration.minuteOfDay, configuration.timeZoneId, configuration.followDeviceTimeZone)
    val zone = rule.resolvedZone(deviceTimeZoneId).id
    val due = rule.dueAt(configuration.lastCompletedAtEpochMillis ?: configuration.createdAtEpochMillis, deviceTimeZoneId)
    val state = when {
        !configuration.enabled -> ActivityDueState.REMINDERS_OFF
        nowEpochMillis < due -> ActivityDueState.UPCOMING
        nowEpochMillis == due -> ActivityDueState.DUE
        else -> ActivityDueState.OVERDUE
    }
    return ActivityCadenceSnapshot(
        owner, record.title, record.completedCount, configuration.lastCompletedAtEpochMillis, due, zone, state,
        if (configuration.enabled) ScheduleRequest(
            eventId = scheduleEventId,
            owner = owner,
            triggerAtEpochMillis = due,
            timeZoneId = zone,
            timeZoneSemantics = TimeZoneSemantics.WALL_CLOCK_IN_ZONE,
            missedTriggerPolicy = MissedTriggerPolicy.FIRE_ONCE_WHEN_AVAILABLE,
        ) else null,
    )
}

class ActivityCadenceRepository(private val records: ActivityRecordDao) {
    suspend fun create(id: String, title: String, rule: CadenceRule, createdAtEpochMillis: Long) {
        EntityRef(EntityDomain.ACTIVITY, id)
        require(title.isNotBlank() && title.length <= 200) { "Activity title must contain 1 to 200 characters" }
        require(createdAtEpochMillis in 0L..MAX_ACTIVITY_TIME) { "Invalid creation time" }
        // Validate calendar range before writing anything.
        rule.dueAt(createdAtEpochMillis, rule.timeZoneId)
        records.create(
            ActivityRecordEntity(id, title.trim(), 0L),
            ActivityCadenceEntity(id, rule.everyDays, rule.minuteOfDay, rule.timeZoneId,
                rule.followDeviceTimeZone, createdAtEpochMillis, null, true),
        )
    }

    suspend fun complete(id: String, eventId: String, occurredAtEpochMillis: Long): CompletionOutcome =
        completeEvent(id, eventId, occurredAtEpochMillis, null)

    suspend fun completeFromReminder(id: String, eventId: String, scheduleEventId: String, occurredAtEpochMillis: Long): CompletionOutcome =
        completeEvent(id, eventId, occurredAtEpochMillis, scheduleEventId)

    private suspend fun completeEvent(id: String, eventId: String, occurredAtEpochMillis: Long, scheduleEventId: String?): CompletionOutcome {
        EntityRef(EntityDomain.ACTIVITY, id)
        require(eventId.isNotBlank() && eventId.length <= 256) { "A bounded completion event ID is required" }
        require(occurredAtEpochMillis in 0L..MAX_ACTIVITY_TIME) { "Invalid completion time" }
        if (scheduleEventId != null) require(scheduleEventId.isNotBlank()) { "Reminder occurrence ID is required" }
        return records.complete(ActivityCompletionEntity(eventId, id, occurredAtEpochMillis), scheduleEventId)
    }

    suspend fun setReminderEnabled(id: String, enabled: Boolean) = records.setReminderEnabled(id, enabled)

    suspend fun history(id: String): List<ActivityCompletionEntity> = records.history(id)

    suspend fun snapshot(nowEpochMillis: Long, deviceTimeZoneId: String): List<ActivityCadenceSnapshot> {
        require(nowEpochMillis in 0L..MAX_ACTIVITY_TIME) { "Invalid clock time" }
        TimeZone.of(deviceTimeZoneId)
        return records.snapshot().map { it.toCadenceSnapshot(nowEpochMillis, deviceTimeZoneId) }
    }
}

enum class ReconciliationReason { STARTUP, BOOT, CLOCK_CHANGE, TIME_ZONE_CHANGE, ACTIVITY_CHANGE }

/** One native transport owns scheduling; domain replacement must leave other domains' requests intact. */
interface ReminderTransport {
    suspend fun replaceDomainRequests(domain: EntityDomain, requests: List<ScheduleRequest>)
}

data class ReminderReconciliation(
    val reason: ReconciliationReason,
    val requests: List<ScheduleRequest>,
    val overdueActivities: Int,
)

class ActivityReminderCoordinator(
    private val activities: ActivityCadenceRepository,
    private val transport: ReminderTransport,
) {
    private val reconciliation = Mutex()

    suspend fun reconcile(reason: ReconciliationReason, nowEpochMillis: Long, deviceTimeZoneId: String): ReminderReconciliation =
        reconciliation.withLock {
            val snapshot = activities.snapshot(nowEpochMillis, deviceTimeZoneId)
            val requests = snapshot.mapNotNull { it.request }.sortedBy { it.stableTransportKey }
            // Propagate permission/transport failures. A planned request is not evidence of notification delivery.
            transport.replaceDomainRequests(EntityDomain.ACTIVITY, requests)
            ReminderReconciliation(reason, requests, snapshot.count { it.dueState == ActivityDueState.OVERDUE })
        }
}
