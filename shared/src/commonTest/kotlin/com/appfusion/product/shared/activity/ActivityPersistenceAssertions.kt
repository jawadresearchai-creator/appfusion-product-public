package com.appfusion.product.shared.activity

import androidx.room3.migration.Migration
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.appfusion.product.shared.EntityDomain
import com.appfusion.product.shared.EntityRef
import com.appfusion.product.shared.MissedTriggerPolicy
import com.appfusion.product.shared.ScheduleRequest
import com.appfusion.product.shared.TimeZoneSemantics
import com.appfusion.product.shared.persistence.ActivityDomainDatabase
import com.appfusion.product.shared.persistence.CompletionOutcome
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

suspend fun assertActivityJourneySurvivesRestart(open: () -> ActivityDomainDatabase) {
    val created = instant("2026-09-04T12:00:00Z")
    val late = instant("2026-09-06T12:00:00Z")
    val earlier = instant("2026-09-05T12:00:00Z")
    var db = open()
    val first = ActivityCadenceRepository(db.records())
    first.create("exercise", "Exercise", CadenceRule(1, 540, "Asia/Karachi", true), created)
    assertFailsWith<Exception> { first.create("exercise", "Replacement", CadenceRule(7, 600, "UTC"), created) }
    assertEquals(CompletionOutcome.APPLIED, first.complete("exercise", "completion-2", late))
    assertEquals(CompletionOutcome.APPLIED, first.complete("exercise", "completion-1", earlier))
    assertEquals(CompletionOutcome.ALREADY_APPLIED, first.complete("exercise", "completion-1", late + 1000))
    assertFailsWith<IllegalArgumentException> { first.complete("exercise", "invalid-old", created - 1) }
    assertFailsWith<IllegalArgumentException> { first.complete("exercise", "invalid-future", Long.MAX_VALUE) }
    assertEquals(2, first.history("exercise").size)
    val before = first.snapshot(late, "Asia/Karachi").single()
    assertEquals(late, before.lastCompletedAtEpochMillis)
    assertEquals(2L, before.completedCount)
    db.close()

    db = open()
    try {
        val restored = ActivityCadenceRepository(db.records())
        assertEquals(before, restored.snapshot(late, "Asia/Karachi").single())
        assertEquals(listOf("completion-1", "completion-2"), restored.history("exercise").map { it.eventId })
        val transport = RecordingReminderTransport()
        val document = ScheduleRequest("expiry", EntityRef(EntityDomain.DOCUMENT, "document"), late, "UTC",
            TimeZoneSemantics.FIXED_INSTANT, MissedTriggerPolicy.FIRE_ONCE_WHEN_AVAILABLE)
        transport.replaceDomainRequests(EntityDomain.DOCUMENT, listOf(document))
        val coordinator = ActivityReminderCoordinator(restored, transport)
        val startup = coordinator.reconcile(ReconciliationReason.STARTUP, late, "Asia/Karachi")
        val boot = coordinator.reconcile(ReconciliationReason.BOOT, late, "Asia/Karachi")
        assertEquals(startup.requests, boot.requests)
        assertEquals(2, transport.pending.size)
        val changed = coordinator.reconcile(ReconciliationReason.TIME_ZONE_CHANGE, late, "America/New_York")
        assertEquals(startup.requests.single().stableTransportKey, changed.requests.single().stableTransportKey)
        assertNotEquals(startup.requests.single().triggerAtEpochMillis, changed.requests.single().triggerAtEpochMillis)
        val overdue = coordinator.reconcile(ReconciliationReason.CLOCK_CHANGE, instant("2026-09-20T12:00:00Z"), "America/New_York")
        assertEquals(1, overdue.overdueActivities)
        assertEquals(changed.requests, overdue.requests)
        assertEquals(2, transport.pending.size)
        assertEquals(document, transport.pending[document.stableTransportKey])
        val rolledBackClock = coordinator.reconcile(ReconciliationReason.CLOCK_CHANGE, created, "America/New_York")
        assertEquals(changed.requests, rolledBackClock.requests)
        assertEquals(0, rolledBackClock.overdueActivities)

        val occurrence = changed.requests.single().eventId
        assertEquals(CompletionOutcome.APPLIED, restored.completeFromReminder("exercise", "notification-action", occurrence, late + 1000L))
        assertEquals(CompletionOutcome.ALREADY_APPLIED, restored.completeFromReminder("exercise", "notification-action", occurrence, late + 2000L))
        assertEquals(CompletionOutcome.STALE_REMINDER, restored.completeFromReminder("exercise", "stale-action", occurrence, late + 3000L))
        assertEquals(3L, restored.snapshot(late, "UTC").single().completedCount)
        assertEquals(3, restored.history("exercise").size)
        restored.setReminderEnabled("exercise", false)
        assertTrue(coordinator.reconcile(ReconciliationReason.ACTIVITY_CHANGE, late, "UTC").requests.isEmpty())
        assertEquals(listOf(document), transport.pending.values.toList())

        val failing = ActivityReminderCoordinator(restored, object : ReminderTransport {
            override suspend fun replaceDomainRequests(domain: EntityDomain, requests: List<ScheduleRequest>) { error("transport unavailable") }
        })
        assertFailsWith<IllegalStateException> { failing.reconcile(ReconciliationReason.STARTUP, late, "UTC") }
    } finally {
        db.close()
    }
}

suspend fun assertConcurrentActivityCompletions(open: () -> ActivityDomainDatabase) {
    val db = open()
    try {
        val repository = ActivityCadenceRepository(db.records())
        val created = instant("2026-09-04T12:00:00Z")
        repository.create("one", "One", CadenceRule(1, 540, "UTC"), created)
        repository.create("two", "Two", CadenceRule(1, 540, "UTC"), created)
        coroutineScope {
            repeat(12) { index -> launch { repository.complete("one", "unique-$index", created + index) } }
            repeat(12) { launch { repository.complete("one", "repeated", created + 100) } }
        }
        assertEquals(13L, repository.snapshot(created, "UTC").first { it.ref.id == "one" }.completedCount)
        assertEquals(13, repository.history("one").size)
        assertFailsWith<IllegalArgumentException> { repository.complete("two", "repeated", created + 100) }
        assertEquals(0L, repository.snapshot(created, "UTC").first { it.ref.id == "two" }.completedCount)
    } finally {
        db.close()
    }
}

fun seedActivityV1(path: String) {
    val connection = BundledSQLiteDriver().open(path)
    try {
        connection.execSQL("CREATE TABLE activity_records (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, completedCount INTEGER NOT NULL)")
        connection.execSQL("INSERT INTO activity_records VALUES ('legacy', 'Legacy activity', 7)")
        connection.execSQL("PRAGMA user_version = 1")
    } finally { connection.close() }
}

suspend fun assertLegacyActivityMigration(open: () -> ActivityDomainDatabase) {
    val db = open()
    try {
        val repository = ActivityCadenceRepository(db.records())
        val legacy = repository.snapshot(instant("2026-09-04T12:00:00Z"), "UTC").single()
        assertEquals("Legacy activity", legacy.title)
        assertEquals(7L, legacy.completedCount)
        assertEquals(ActivityDueState.NOT_CONFIGURED, legacy.dueState)
        assertNull(legacy.lastCompletedAtEpochMillis)
        assertNull(legacy.request)
        assertTrue(repository.history("legacy").isEmpty())
    } finally { db.close() }
}

val FailingActivityMigration1To2 = Migration(1, 2) { connection ->
    connection.execSQL("CREATE TABLE migration_should_rollback (id INTEGER NOT NULL)")
    error("intentional activity migration rollback fixture")
}

suspend fun assertActivityMigrationRollback(path: String, openFailing: () -> ActivityDomainDatabase) {
    val db = openFailing()
    try {
        assertFailsWith<Exception> { db.records().find("legacy") }
    } finally { db.close() }
    val connection = BundledSQLiteDriver().open(path)
    try {
        val version = connection.prepare("PRAGMA user_version")
        try { assertTrue(version.step()); assertEquals(1L, version.getLong(0)) } finally { version.close() }
        val row = connection.prepare("SELECT completedCount FROM activity_records WHERE id = 'legacy'")
        try { assertTrue(row.step()); assertEquals(7L, row.getLong(0)) } finally { row.close() }
        val table = connection.prepare("SELECT COUNT(*) FROM sqlite_master WHERE name = 'migration_should_rollback'")
        try { assertTrue(table.step()); assertEquals(0L, table.getLong(0)) } finally { table.close() }
    } finally { connection.close() }
}

suspend fun assertCompletionTransactionRollback(path: String, open: () -> ActivityDomainDatabase) {
    val created = instant("2026-09-04T12:00:00Z")
    var db = open()
    ActivityCadenceRepository(db.records()).create("one", "One", CadenceRule(1, 540, "UTC"), created)
    db.close()
    val connection = BundledSQLiteDriver().open(path)
    try {
        connection.execSQL("CREATE TRIGGER refuse_count_update BEFORE UPDATE ON activity_records BEGIN SELECT RAISE(ABORT, 'intentional update failure'); END")
    } finally { connection.close() }
    db = open()
    try {
        val repository = ActivityCadenceRepository(db.records())
        assertFailsWith<Exception> { repository.complete("one", "must-rollback", created + 1) }
        assertEquals(0L, assertNotNull(db.records().find("one")).completedCount)
        assertNull(assertNotNull(db.records().findCadence("one")).lastCompletedAtEpochMillis)
        assertTrue(repository.history("one").isEmpty(), "History insertion must roll back with the count update")
    } finally { db.close() }
}

private class RecordingReminderTransport : ReminderTransport {
    val pending = mutableMapOf<String, ScheduleRequest>()
    override suspend fun replaceDomainRequests(domain: EntityDomain, requests: List<ScheduleRequest>) {
        require(requests.all { it.owner.domain == domain })
        require(requests.map { it.stableTransportKey }.distinct().size == requests.size)
        pending.keys.filter { pending[it]?.owner?.domain == domain }.toList().forEach { pending.remove(it) }
        requests.forEach { pending[it.stableTransportKey] = it }
    }
}
