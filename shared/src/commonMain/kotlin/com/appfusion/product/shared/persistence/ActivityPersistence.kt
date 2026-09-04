package com.appfusion.product.shared.persistence

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

/** Cadence is activity-owned metadata, separate from document storage and OS transport. */
@Entity(tableName = "activity_cadence")
data class ActivityCadenceEntity(
    @PrimaryKey val activityId: String,
    val everyDays: Int,
    val minuteOfDay: Int,
    val timeZoneId: String,
    val followDeviceTimeZone: Boolean,
    val createdAtEpochMillis: Long,
    val lastCompletedAtEpochMillis: Long?,
    val enabled: Boolean,
)

@Entity(tableName = "activity_completions")
data class ActivityCompletionEntity(
    @PrimaryKey val eventId: String,
    val activityId: String,
    val occurredAtEpochMillis: Long,
)

data class ActivityStoredState(
    val record: ActivityRecordEntity,
    val cadence: ActivityCadenceEntity?,
) {
    // Does not change merely because the OS clock or current time zone changes.
    val scheduleEventId: String
        get() = "cadence-${record.completedCount}-${cadence?.lastCompletedAtEpochMillis ?: cadence?.createdAtEpochMillis ?: 0L}"
}

enum class CompletionOutcome { APPLIED, ALREADY_APPLIED, STALE_REMINDER }

@Dao
abstract class ActivityRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(record: ActivityRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRecord(record: ActivityRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putCadence(cadence: ActivityCadenceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCompletion(completion: ActivityCompletionEntity)

    @Query("SELECT * FROM activity_records WHERE id = :id LIMIT 1")
    abstract suspend fun find(id: String): ActivityRecordEntity?

    @Query("SELECT * FROM activity_cadence WHERE activityId = :id LIMIT 1")
    abstract suspend fun findCadence(id: String): ActivityCadenceEntity?

    @Query("SELECT * FROM activity_completions WHERE eventId = :eventId LIMIT 1")
    abstract suspend fun findCompletion(eventId: String): ActivityCompletionEntity?

    @Query("SELECT * FROM activity_completions WHERE activityId = :id ORDER BY occurredAtEpochMillis, eventId")
    abstract suspend fun history(id: String): List<ActivityCompletionEntity>

    @Query("SELECT * FROM activity_records ORDER BY id")
    abstract suspend fun listRecords(): List<ActivityRecordEntity>

    @Query("SELECT * FROM activity_cadence ORDER BY activityId")
    abstract suspend fun listCadences(): List<ActivityCadenceEntity>

    @Query("UPDATE activity_records SET completedCount = completedCount + 1 WHERE id = :id")
    abstract suspend fun incrementCompletionCount(id: String): Int

    @Transaction
    open suspend fun create(record: ActivityRecordEntity, cadence: ActivityCadenceEntity) {
        require(record.id == cadence.activityId)
        insertRecord(record)
        putCadence(cadence)
    }

    @Transaction
    open suspend fun snapshot(): List<ActivityStoredState> {
        val cadences = listCadences().associateBy { it.activityId }
        return listRecords().map { ActivityStoredState(it, cadences[it.id]) }
    }

    @Transaction
    open suspend fun complete(
        event: ActivityCompletionEntity,
        expectedScheduleEventId: String?,
    ): CompletionOutcome {
        val existing = findCompletion(event.eventId)
        if (existing != null) {
            require(existing.activityId == event.activityId) { "Completion ID belongs to another activity" }
            // Notification delivery retries use the original persisted event time (first-write wins).
            return CompletionOutcome.ALREADY_APPLIED
        }
        val record = requireNotNull(find(event.activityId)) { "Activity is missing" }
        val cadence = requireNotNull(findCadence(event.activityId)) { "Activity cadence is not configured" }
        if (expectedScheduleEventId != null &&
            (!cadence.enabled || ActivityStoredState(record, cadence).scheduleEventId != expectedScheduleEventId)
        ) return CompletionOutcome.STALE_REMINDER
        require(event.occurredAtEpochMillis >= cadence.createdAtEpochMillis) { "Completion predates activity creation" }
        require(record.completedCount < Long.MAX_VALUE) { "Completion count overflow" }
        insertCompletion(event)
        check(incrementCompletionCount(record.id) == 1) { "Activity count update failed" }
        putCadence(cadence.copy(
            lastCompletedAtEpochMillis = maxOf(cadence.lastCompletedAtEpochMillis ?: Long.MIN_VALUE, event.occurredAtEpochMillis),
        ))
        return CompletionOutcome.APPLIED
    }

    @Transaction
    open suspend fun setReminderEnabled(id: String, enabled: Boolean) {
        val cadence = requireNotNull(findCadence(id)) { "Activity cadence is not configured" }
        putCadence(cadence.copy(enabled = enabled))
    }
}

/** Existing activity records/counts survive; migration must not invent history or enable reminders. */
val ActivityMigration1To2 = Migration(1, 2) { connection ->
    connection.execSQL("""
        CREATE TABLE IF NOT EXISTS activity_cadence (
            activityId TEXT NOT NULL PRIMARY KEY,
            everyDays INTEGER NOT NULL,
            minuteOfDay INTEGER NOT NULL,
            timeZoneId TEXT NOT NULL,
            followDeviceTimeZone INTEGER NOT NULL,
            createdAtEpochMillis INTEGER NOT NULL,
            lastCompletedAtEpochMillis INTEGER,
            enabled INTEGER NOT NULL
        )
    """.trimIndent())
    connection.execSQL("""
        CREATE TABLE IF NOT EXISTS activity_completions (
            eventId TEXT NOT NULL PRIMARY KEY,
            activityId TEXT NOT NULL,
            occurredAtEpochMillis INTEGER NOT NULL
        )
    """.trimIndent())
}
