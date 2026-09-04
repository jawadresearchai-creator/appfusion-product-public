package com.appfusion.product.shared

enum class EntityDomain {
    DOCUMENT,
    ACTIVITY,
    THING,
    PLACE,
    PERSON,
    COLLECTION,
}

data class EntityRef(
    val domain: EntityDomain,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "Entity ID must not be blank" }
        require(id.length <= 256) { "Entity ID is too long" }
        require(id.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "Entity ID contains an unsafe control character"
        }
    }

    val stableKey: String
        get() = "${domain.name}:$id"
}

enum class TimeZoneSemantics {
    FIXED_INSTANT,
    WALL_CLOCK_IN_ZONE,
}

enum class MissedTriggerPolicy {
    FIRE_ONCE_WHEN_AVAILABLE,
    SKIP,
    RESCHEDULE_FROM_NOW,
}

data class ScheduleRequest(
    val eventId: String,
    val owner: EntityRef,
    val triggerAtEpochMillis: Long,
    val timeZoneId: String,
    val timeZoneSemantics: TimeZoneSemantics,
    val missedTriggerPolicy: MissedTriggerPolicy,
) {
    init {
        require(eventId.isNotBlank()) { "Event ID must not be blank" }
        require(timeZoneId.isNotBlank()) { "Time zone ID must not be blank" }
    }

    val stableTransportKey: String
        get() = "${owner.stableKey}|$eventId"
}

data class SecureBlobMetadata(
    val blobId: String,
    val envelopeVersion: Int,
    val contentType: String? = null,
) {
    init {
        require(blobId.isNotBlank()) { "Blob ID must not be blank" }
        require(envelopeVersion > 0) { "Envelope version must be positive" }
    }
}

data class EncryptedPayload(
    val ciphertext: ByteArray,
    val integrityTag: ByteArray,
) {
    init {
        require(ciphertext.isNotEmpty()) { "Ciphertext must not be empty" }
        require(integrityTag.isNotEmpty()) { "Integrity tag must not be empty" }
    }
}

interface SecureBlobStore {
    fun writeAtomic(metadata: SecureBlobMetadata, payload: EncryptedPayload)
    fun read(blobId: String): Pair<SecureBlobMetadata, EncryptedPayload>?
    fun delete(blobId: String): Boolean
}

data class BackupRecord(
    val ref: EntityRef,
    val schemaVersion: Int,
    val payload: ByteArray,
) {
    init {
        require(schemaVersion > 0) { "Backup schema version must be positive" }
    }
}

data class RestoreReport(
    val accepted: Int,
    val rejected: Int,
) {
    init {
        require(accepted >= 0 && rejected >= 0) { "Restore counts must be non-negative" }
    }
}

interface BackupAdapter {
    val domain: EntityDomain
    fun exportRecords(): List<BackupRecord>
    fun restoreRecords(records: List<BackupRecord>): RestoreReport
}

data class ActivityEvent(
    val eventId: String,
    val occurredAtEpochMillis: Long,
    val subject: EntityRef,
    val kind: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(eventId.isNotBlank()) { "Event ID must not be blank" }
        require(kind.isNotBlank()) { "Event kind must not be blank" }
    }
}

data class SequencedActivityEvent(
    val sequence: Long,
    val event: ActivityEvent,
)

interface ActivityEventLog {
    fun append(event: ActivityEvent): SequencedActivityEvent
    fun readAfter(sequenceExclusive: Long): List<SequencedActivityEvent>
}

class InMemoryAppendOnlyActivityEventLog : ActivityEventLog {
    private val events = mutableListOf<SequencedActivityEvent>()
    private val eventIds = mutableSetOf<String>()

    override fun append(event: ActivityEvent): SequencedActivityEvent {
        require(eventIds.add(event.eventId)) { "Event ID already exists" }
        val sequenced = SequencedActivityEvent(
            sequence = (events.lastOrNull()?.sequence ?: 0L) + 1L,
            event = event,
        )
        events += sequenced
        return sequenced
    }

    override fun readAfter(sequenceExclusive: Long): List<SequencedActivityEvent> {
        require(sequenceExclusive >= 0L) { "Sequence must be non-negative" }
        return events.filter { it.sequence > sequenceExclusive }
    }
}
