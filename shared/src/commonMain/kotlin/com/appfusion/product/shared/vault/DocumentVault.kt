package com.appfusion.product.shared.vault

import com.appfusion.product.shared.ActivityEvent
import com.appfusion.product.shared.ActivityEventLog
import com.appfusion.product.shared.BackupRecord
import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.EntityDomain
import com.appfusion.product.shared.EntityRef
import com.appfusion.product.shared.SearchProvider
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.SearchResult
import com.appfusion.product.shared.SecureBlobMetadata
import com.appfusion.product.shared.SecureBlobStore
import com.appfusion.product.shared.persistence.DocumentRecordDao
import com.appfusion.product.shared.persistence.DocumentRecordEntity
import com.appfusion.product.shared.security.SecureBlobCodec
import com.appfusion.product.shared.security.SecureBlobService

private const val DOCUMENT_BACKUP_SCHEMA_VERSION = 1
private const val MAX_STRING_BYTES = 64 * 1024
private const val MAX_BACKUP_BLOB_BYTES = 128 * 1024 * 1024
private val BACKUP_MAGIC = byteArrayOf(0x41, 0x46, 0x44, 0x42) // AFDB

enum class DocumentLifecycle {
    ACTIVE,
    ARCHIVED,
    LEGACY_MIGRATION_REQUIRED,
}

data class VaultDocumentMetadata(
    val id: String,
    val title: String,
    val label: String,
    val blobId: String,
    val contentType: String,
    val revision: Long,
    val lifecycle: DocumentLifecycle,
    val updatedAtEpochMillis: Long,
) {
    init {
        EntityRef(EntityDomain.DOCUMENT, id)
        require(title.isNotBlank()) { "Document title must not be blank" }
        require(blobId.isNotBlank()) { "Blob ID must not be blank" }
        require(contentType.isNotBlank()) { "Content type must not be blank" }
        require(revision > 0L) { "Document revision must be positive" }
        require(updatedAtEpochMillis >= 0L) { "Update time must be non-negative" }
    }

    val ref: EntityRef get() = EntityRef(EntityDomain.DOCUMENT, id)
}

data class VaultDocument(
    val metadata: VaultDocumentMetadata,
    val plaintext: ByteArray,
)

interface DocumentMetadataStore {
    suspend fun find(id: String): VaultDocumentMetadata?
    suspend fun put(metadata: VaultDocumentMetadata)
    suspend fun listActive(): List<VaultDocumentMetadata>
    suspend fun delete(id: String): Boolean
}

class RoomDocumentMetadataStore(
    private val records: DocumentRecordDao,
) : DocumentMetadataStore {
    override suspend fun find(id: String): VaultDocumentMetadata? = records.find(id)?.toMetadata()

    override suspend fun put(metadata: VaultDocumentMetadata) {
        records.put(metadata.toEntity())
    }

    override suspend fun listActive(): List<VaultDocumentMetadata> =
        records.listActive().map { it.toMetadata() }

    override suspend fun delete(id: String): Boolean = records.delete(id) > 0
}

fun interface DocumentAccessPolicy {
    fun canRead(ref: EntityRef): Boolean
}

class DocumentSearchProjection(
    override val providerId: String,
    private val accessPolicy: DocumentAccessPolicy,
) : SearchProvider {
    private val documents = mutableMapOf<String, VaultDocumentMetadata>()

    init {
        require(providerId.isNotBlank()) { "Search provider ID must not be blank" }
    }

    fun publish(metadata: VaultDocumentMetadata) {
        if (metadata.lifecycle == DocumentLifecycle.ACTIVE) {
            documents[metadata.id] = metadata
        } else {
            documents.remove(metadata.id)
        }
    }

    fun remove(id: String) {
        documents.remove(id)
    }

    override fun search(query: SearchQuery): List<SearchResult> {
        val normalizedQuery = query.text.trim().lowercase()
        if (normalizedQuery.isEmpty()) return emptyList()
        return documents.values
            .asSequence()
            .filter { accessPolicy.canRead(it.ref) }
            .mapNotNull { metadata ->
                val title = metadata.title.lowercase()
                val label = metadata.label.lowercase()
                val score = when {
                    title == normalizedQuery -> 1.0
                    title.startsWith(normalizedQuery) -> 0.9
                    normalizedQuery in title -> 0.8
                    normalizedQuery in label -> 0.6
                    else -> return@mapNotNull null
                }
                SearchResult(
                    ref = metadata.ref,
                    title = metadata.title,
                    snippet = metadata.label.ifBlank { null },
                    score = score,
                    action = "OPEN_DOCUMENT",
                )
            }
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.ref.id })
            .take(query.limit)
            .toList()
    }
}

class DocumentVaultRepository(
    private val metadataStore: DocumentMetadataStore,
    private val blobStore: SecureBlobStore,
    private val secureBlobService: SecureBlobService,
    private val eventLog: ActivityEventLog,
    private val searchProjection: DocumentSearchProjection,
) {
    suspend fun rebuildSearchProjection() {
        metadataStore.listActive().forEach(searchProjection::publish)
    }

    suspend fun create(
        id: String,
        title: String,
        label: String,
        contentType: String,
        plaintext: ByteArray,
        occurredAtEpochMillis: Long,
    ): VaultDocumentMetadata {
        require(metadataStore.find(id) == null) { "Document already exists" }
        return commitNewPayload(
            previous = null,
            id = id,
            title = title,
            label = label,
            contentType = contentType,
            plaintext = plaintext,
            occurredAtEpochMillis = occurredAtEpochMillis,
            eventKind = "DOCUMENT_CREATED",
        )
    }

    suspend fun update(
        id: String,
        title: String,
        label: String,
        contentType: String,
        plaintext: ByteArray,
        occurredAtEpochMillis: Long,
    ): VaultDocumentMetadata {
        val previous = requireNotNull(metadataStore.find(id)) { "Document does not exist" }
        require(previous.lifecycle == DocumentLifecycle.ACTIVE) { "Archived document cannot be updated" }
        return commitNewPayload(
            previous = previous,
            id = id,
            title = title,
            label = label,
            contentType = contentType,
            plaintext = plaintext,
            occurredAtEpochMillis = occurredAtEpochMillis,
            eventKind = "DOCUMENT_UPDATED",
        )
    }

    suspend fun read(id: String, accessPolicy: DocumentAccessPolicy): VaultDocument? {
        val metadata = metadataStore.find(id) ?: return null
        if (!accessPolicy.canRead(metadata.ref)) return null
        require(metadata.lifecycle != DocumentLifecycle.LEGACY_MIGRATION_REQUIRED) {
            "Legacy document requires encrypted-payload migration"
        }
        val stored = blobStore.read(metadata.blobId)
            ?: error("Encrypted payload is missing for document")
        require(stored.first.blobId == metadata.blobId) { "SecureBlob metadata mismatch" }
        require(stored.first.contentType == metadata.contentType) { "SecureBlob content type mismatch" }
        val envelope = SecureBlobCodec.decode(stored.second.ciphertext)
        require(envelope.version == stored.first.envelopeVersion) { "SecureBlob envelope version mismatch" }
        require(stored.second.integrityTag.contentEquals(envelope.authTag())) {
            "SecureBlob integrity tag mismatch"
        }
        return VaultDocument(
            metadata,
            secureBlobService.unprotect(
                stored.second.ciphertext,
                secureBlobContext(metadata.blobId, metadata.contentType),
            ),
        )
    }

    suspend fun archive(id: String, occurredAtEpochMillis: Long): VaultDocumentMetadata {
        val previous = requireNotNull(metadataStore.find(id)) { "Document does not exist" }
        if (previous.lifecycle == DocumentLifecycle.ARCHIVED) return previous
        val archived = previous.copy(
            revision = previous.revision + 1L,
            lifecycle = DocumentLifecycle.ARCHIVED,
            updatedAtEpochMillis = occurredAtEpochMillis,
        )
        metadataStore.put(archived)
        searchProjection.remove(id)
        emit(archived, "DOCUMENT_ARCHIVED", occurredAtEpochMillis)
        return archived
    }

    suspend fun exportBackupRecord(id: String, accessPolicy: DocumentAccessPolicy): BackupRecord? {
        val metadata = metadataStore.find(id) ?: return null
        if (!accessPolicy.canRead(metadata.ref)) return null
        val stored = blobStore.read(metadata.blobId)
            ?: error("Encrypted payload is missing for document")
        return BackupRecord(
            ref = metadata.ref,
            schemaVersion = DOCUMENT_BACKUP_SCHEMA_VERSION,
            payload = DocumentBackupCodec.encode(metadata, stored.first, stored.second),
        )
    }

    private suspend fun commitNewPayload(
        previous: VaultDocumentMetadata?,
        id: String,
        title: String,
        label: String,
        contentType: String,
        plaintext: ByteArray,
        occurredAtEpochMillis: Long,
        eventKind: String,
    ): VaultDocumentMetadata {
        require(plaintext.isNotEmpty()) { "Document payload must not be empty" }
        val revision = (previous?.revision ?: 0L) + 1L
        val blobId = "$id:revision:$revision"
        val encoded = secureBlobService.protect(
            plaintext,
            context = secureBlobContext(blobId, contentType),
        )
        val envelope = SecureBlobCodec.decode(encoded)
        val secureMetadata = SecureBlobMetadata(blobId, envelope.version, contentType)
        val securePayload = EncryptedPayload(encoded, envelope.authTag())
        val metadata = VaultDocumentMetadata(
            id = id,
            title = title,
            label = label,
            blobId = blobId,
            contentType = contentType,
            revision = revision,
            lifecycle = DocumentLifecycle.ACTIVE,
            updatedAtEpochMillis = occurredAtEpochMillis,
        )

        blobStore.writeAtomic(secureMetadata, securePayload)
        try {
            metadataStore.put(metadata)
        } catch (failure: Throwable) {
            blobStore.delete(blobId)
            throw failure
        }

        previous?.blobId?.let { oldBlobId ->
            runCatching { blobStore.delete(oldBlobId) }
        }
        searchProjection.publish(metadata)
        emit(metadata, eventKind, occurredAtEpochMillis)
        return metadata
    }

    private fun emit(metadata: VaultDocumentMetadata, kind: String, occurredAtEpochMillis: Long) {
        eventLog.append(
            ActivityEvent(
                eventId = "${metadata.id}:${metadata.revision}:$kind",
                occurredAtEpochMillis = occurredAtEpochMillis,
                subject = metadata.ref,
                kind = kind,
                attributes = mapOf("revision" to metadata.revision.toString()),
            ),
        )
    }
}

data class DecodedDocumentBackup(
    val metadata: VaultDocumentMetadata,
    val secureMetadata: SecureBlobMetadata,
    val securePayload: EncryptedPayload,
)

object DocumentBackupCodec {
    fun encode(
        metadata: VaultDocumentMetadata,
        secureMetadata: SecureBlobMetadata,
        securePayload: EncryptedPayload,
    ): ByteArray {
        require(metadata.blobId == secureMetadata.blobId) { "Backup blob IDs do not match" }
        require(metadata.contentType == secureMetadata.contentType) { "Backup content types do not match" }
        val envelope = SecureBlobCodec.decode(securePayload.ciphertext)
        require(envelope.version == secureMetadata.envelopeVersion) { "Backup envelope version does not match" }
        require(securePayload.integrityTag.contentEquals(envelope.authTag())) { "Backup integrity tag does not match" }
        return BackupWriter().apply {
            bytes(BACKUP_MAGIC)
            u8(DOCUMENT_BACKUP_SCHEMA_VERSION)
            string(metadata.id)
            string(metadata.title)
            string(metadata.label)
            string(metadata.blobId)
            string(metadata.contentType)
            i64(metadata.revision)
            string(metadata.lifecycle.name)
            i64(metadata.updatedAtEpochMillis)
            i32(secureMetadata.envelopeVersion)
            byteArray(securePayload.ciphertext)
            byteArray(securePayload.integrityTag)
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): DecodedDocumentBackup {
        val reader = BackupReader(bytes)
        require(reader.bytes(BACKUP_MAGIC.size).contentEquals(BACKUP_MAGIC)) { "Invalid document backup magic" }
        require(reader.u8() == DOCUMENT_BACKUP_SCHEMA_VERSION) { "Unsupported document backup version" }
        val metadata = VaultDocumentMetadata(
            id = reader.string(),
            title = reader.string(),
            label = reader.string(),
            blobId = reader.string(),
            contentType = reader.string(),
            revision = reader.i64(),
            lifecycle = DocumentLifecycle.valueOf(reader.string()),
            updatedAtEpochMillis = reader.i64(),
        )
        val secureMetadata = SecureBlobMetadata(
            blobId = metadata.blobId,
            envelopeVersion = reader.i32(),
            contentType = metadata.contentType,
        )
        val securePayload = EncryptedPayload(reader.byteArray(), reader.byteArray())
        require(reader.remaining == 0) { "Trailing document backup bytes" }
        val envelope = SecureBlobCodec.decode(securePayload.ciphertext)
        require(envelope.version == secureMetadata.envelopeVersion) { "Document backup envelope version mismatch" }
        require(securePayload.integrityTag.contentEquals(envelope.authTag())) {
            "Document backup integrity tag mismatch"
        }
        return DecodedDocumentBackup(metadata, secureMetadata, securePayload)
    }
}

private fun VaultDocumentMetadata.toEntity(): DocumentRecordEntity = DocumentRecordEntity(
    id = id,
    title = title,
    label = label,
    blobId = blobId,
    contentType = contentType,
    revision = revision,
    lifecycle = lifecycle.name,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun DocumentRecordEntity.toMetadata(): VaultDocumentMetadata = VaultDocumentMetadata(
    id = id,
    title = title,
    label = label,
    blobId = blobId,
    contentType = contentType,
    revision = revision,
    lifecycle = DocumentLifecycle.valueOf(lifecycle),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun com.appfusion.product.shared.security.SecureBlobEnvelope.authTag(): ByteArray {
    require(ciphertext.size >= 16) { "SecureBlob ciphertext does not contain an authentication tag" }
    return ciphertext.copyOfRange(ciphertext.size - 16, ciphertext.size)
}

private fun secureBlobContext(blobId: String, contentType: String): ByteArray {
    val blobIdBytes = blobId.encodeToByteArray()
    val contentTypeBytes = contentType.encodeToByteArray()
    return BackupWriter().apply {
        byteArray(blobIdBytes)
        byteArray(contentTypeBytes)
    }.toByteArray()
}

private class BackupWriter {
    private val output = ArrayList<Byte>()
    fun bytes(value: ByteArray) = value.forEach(output::add)
    fun u8(value: Int) { require(value in 0..0xff); output += value.toByte() }
    fun i32(value: Int) {
        require(value >= 0)
        for (shift in 24 downTo 0 step 8) output += (value ushr shift).toByte()
    }
    fun i64(value: Long) {
        require(value >= 0L)
        for (shift in 56 downTo 0 step 8) output += (value ushr shift).toByte()
    }
    fun string(value: String) {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= MAX_STRING_BYTES) { "Document backup string is too large" }
        i32(encoded.size)
        bytes(encoded)
    }
    fun byteArray(value: ByteArray) {
        require(value.size <= MAX_BACKUP_BLOB_BYTES) { "Document backup blob is too large" }
        i32(value.size)
        bytes(value)
    }
    fun toByteArray(): ByteArray = ByteArray(output.size) { output[it] }
}

private class BackupReader(private val input: ByteArray) {
    private var position = 0
    val remaining: Int get() = input.size - position
    fun u8(): Int = bytes(1)[0].toInt() and 0xff
    fun i32(): Int {
        val value = (u8().toLong() shl 24) or (u8().toLong() shl 16) or
            (u8().toLong() shl 8) or u8().toLong()
        require(value <= Int.MAX_VALUE) { "Invalid document backup length" }
        return value.toInt()
    }
    fun i64(): Long {
        require(remaining >= 8) { "Truncated document backup" }
        require((input[position].toInt() and 0x80) == 0) { "Invalid negative document backup integer" }
        var value = 0L
        repeat(8) { value = (value shl 8) or u8().toLong() }
        return value
    }
    fun string(): String {
        val length = i32()
        require(length <= MAX_STRING_BYTES) { "Document backup string is too large" }
        val encoded = bytes(length)
        val value = encoded.decodeToString(throwOnInvalidSequence = true)
        require(value.encodeToByteArray().contentEquals(encoded)) { "Non-canonical document backup string" }
        return value
    }
    fun byteArray(): ByteArray {
        val length = i32()
        require(length <= MAX_BACKUP_BLOB_BYTES) { "Document backup blob is too large" }
        return bytes(length)
    }
    fun bytes(length: Int): ByteArray {
        require(length >= 0 && length <= remaining) { "Truncated document backup" }
        return input.copyOfRange(position, position + length).also { position += length }
    }
}
