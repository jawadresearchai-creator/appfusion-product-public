package com.appfusion.product.shared.activity

import com.appfusion.product.shared.persistence.ActivityCadenceEntity
import com.appfusion.product.shared.persistence.ActivityRecordEntity
import com.appfusion.product.shared.persistence.ActivityStoredState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

internal fun instant(text: String): Long = Instant.parse(text).toEpochMilliseconds()

class CadenceRuleTest {
    @Test
    fun dailyCadenceUsesCalendarDaysAcrossSpringOffsetChange() {
        val rule = CadenceRule(1, 9 * 60, "America/New_York")
        assertEquals(instant("2026-03-08T13:00:00Z"), rule.dueAt(instant("2026-03-07T14:00:00Z"), "Asia/Karachi"))
    }

    @Test
    fun nonexistentSpringWallTimeMovesForwardByTheGap() {
        val rule = CadenceRule(1, 2 * 60 + 30, "America/New_York")
        assertEquals(instant("2026-03-08T07:30:00Z"), rule.dueAt(instant("2026-03-07T12:00:00Z"), "UTC"))
    }

    @Test
    fun repeatedFallWallTimeUsesEarlierOccurrence() {
        val rule = CadenceRule(1, 90, "America/New_York")
        assertEquals(instant("2026-11-01T05:30:00Z"), rule.dueAt(instant("2026-10-31T12:00:00Z"), "UTC"))
    }

    @Test
    fun leapDayAndMonthBoundaryAreCalendarAware() {
        val rule = CadenceRule(1, 9 * 60, "UTC")
        assertEquals(instant("2024-02-29T09:00:00Z"), rule.dueAt(instant("2024-02-28T12:00:00Z"), "UTC"))
        assertEquals(instant("2026-02-01T09:00:00Z"), rule.dueAt(instant("2026-01-31T12:00:00Z"), "UTC"))
    }

    @Test
    fun zonePolicyIsExplicitAndReconciliationKeepsOccurrenceIdentity() {
        val created = instant("2026-09-04T12:00:00Z")
        val fixed = ActivityStoredState(ActivityRecordEntity("activity", "Exercise", 0),
            ActivityCadenceEntity("activity", 1, 540, "Asia/Karachi", false, created, null, true))
        val fixedHere = fixed.toCadenceSnapshot(created, "Asia/Karachi")
        val fixedAway = fixed.toCadenceSnapshot(created, "America/New_York")
        assertEquals(fixedHere.request, fixedAway.request)
        val follows = fixed.copy(cadence = fixed.cadence!!.copy(followDeviceTimeZone = true))
        val here = follows.toCadenceSnapshot(created, "Asia/Karachi")
        val away = follows.toCadenceSnapshot(created, "America/New_York")
        assertEquals(instant("2026-09-05T04:00:00Z"), here.dueAtEpochMillis)
        assertEquals(instant("2026-09-05T13:00:00Z"), away.dueAtEpochMillis)
        assertEquals(here.request!!.stableTransportKey, away.request!!.stableTransportKey)
    }

    @Test
    fun dueStateChangesWithoutAdvancingOrDuplicatingTheReminder() {
        val created = instant("2026-09-04T12:00:00Z")
        val state = ActivityStoredState(ActivityRecordEntity("activity", "Exercise", 0),
            ActivityCadenceEntity("activity", 1, 540, "UTC", false, created, null, true))
        val due = instant("2026-09-05T09:00:00Z")
        assertEquals(ActivityDueState.UPCOMING, state.toCadenceSnapshot(due - 1L, "UTC").dueState)
        assertEquals(ActivityDueState.DUE, state.toCadenceSnapshot(due, "UTC").dueState)
        assertEquals(ActivityDueState.OVERDUE, state.toCadenceSnapshot(due + 1L, "UTC").dueState)
        assertEquals(state.toCadenceSnapshot(due - 1L, "UTC").request, state.toCadenceSnapshot(due + 99_999_999L, "UTC").request)
        assertEquals(ActivityDueState.REMINDERS_OFF, state.copy(cadence = state.cadence!!.copy(enabled = false)).toCadenceSnapshot(due, "UTC").dueState)
    }

    @Test
    fun invalidRulesFailBeforePersistenceOrScheduling() {
        assertFailsWith<IllegalArgumentException> { CadenceRule(0, 0, "UTC") }
        assertFailsWith<IllegalArgumentException> { CadenceRule(3651, 0, "UTC") }
        assertFailsWith<IllegalArgumentException> { CadenceRule(1, 1440, "UTC") }
        assertFailsWith<IllegalArgumentException> { CadenceRule(1, -1, "UTC") }
        assertFailsWith<IllegalArgumentException> { CadenceRule(1, 540, "Unknown/Zone") }
    }
}
