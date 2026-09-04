package com.appfusion.product.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContractsTest {
    @Test
    fun entityReferenceUsesStableOpaqueKey() {
        val ref = EntityRef(EntityDomain.DOCUMENT, "01JABCXYZ")
        assertEquals("DOCUMENT:01JABCXYZ", ref.stableKey)
        assertFailsWith<IllegalArgumentException> {
            EntityRef(EntityDomain.DOCUMENT, " ")
        }
    }

    @Test
    fun scheduleTransportKeyDoesNotOwnDomainDueSemantics() {
        val request = ScheduleRequest(
            eventId = "expiry-2030-01-01",
            owner = EntityRef(EntityDomain.DOCUMENT, "doc-1"),
            triggerAtEpochMillis = 1_893_456_000_000L,
            timeZoneId = "Asia/Karachi",
            timeZoneSemantics = TimeZoneSemantics.WALL_CLOCK_IN_ZONE,
            missedTriggerPolicy = MissedTriggerPolicy.FIRE_ONCE_WHEN_AVAILABLE,
        )
        assertEquals("DOCUMENT:doc-1|expiry-2030-01-01", request.stableTransportKey)
    }

    @Test
    fun eventLogIsAppendOnlyAndRejectsDuplicateEventIds() {
        val log = InMemoryAppendOnlyActivityEventLog()
        val first = ActivityEvent(
            eventId = "event-1",
            occurredAtEpochMillis = 100L,
            subject = EntityRef(EntityDomain.ACTIVITY, "activity-1"),
            kind = "COMPLETED",
        )
        val second = first.copy(eventId = "event-2", occurredAtEpochMillis = 200L)

        assertEquals(1L, log.append(first).sequence)
        assertEquals(2L, log.append(second).sequence)
        assertEquals(listOf("event-2"), log.readAfter(1L).map { it.event.eventId })
        assertFailsWith<IllegalArgumentException> { log.append(first) }
    }

    @Test
    fun secureAndBackupContractsFailClosedOnInvalidMetadata() {
        assertFailsWith<IllegalArgumentException> {
            SecureBlobMetadata(blobId = "", envelopeVersion = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            EncryptedPayload(byteArrayOf(), byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            BackupRecord(
                ref = EntityRef(EntityDomain.DOCUMENT, "doc-1"),
                schemaVersion = 0,
                payload = byteArrayOf(1),
            )
        }
        assertTrue(RestoreReport(accepted = 1, rejected = 0).accepted == 1)
    }
}
