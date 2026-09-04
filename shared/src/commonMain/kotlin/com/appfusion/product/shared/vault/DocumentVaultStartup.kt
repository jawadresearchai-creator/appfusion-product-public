package com.appfusion.product.shared.vault

import com.appfusion.product.shared.persistence.DocumentRecordDao
import com.appfusion.product.shared.persistence.DocumentRecordEntity
import com.appfusion.product.shared.security.SecureBlobCodec
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.storage.BlobRecoveryReport
import com.appfusion.product.shared.storage.RecoverableSecureBlobStore

enum class DocumentVaultStartupIssueKind {
    INVALID_BLOB_REFERENCE,
    DUPLICATE_BLOB_REFERENCE,
    MISSING_BLOB,
    INVALID_BLOB_RECORD,
    BLOB_METADATA_MISMATCH,
    ENVELOPE_METADATA_MISMATCH,
    INTEGRITY_MISMATCH,
    AUTHENTICATION_FAILED,
}

data class DocumentVaultStartupIssue(
    val documentId: String,
    val blobId: String,
    val kind: DocumentVaultStartupIssueKind,
)

data class DocumentVaultStartupReport(
    val recovery: BlobRecoveryReport,
    val metadataRecords: Int,
    val referencedDocuments: Int,
    val recoverableReferences: Int,
    val verifiedActiveDocuments: Int,
    val verifiedArchivedDocuments: Int,
    val issues: List<DocumentVaultStartupIssue>,
) {
    val isClean: Boolean
        get() = issues.isEmpty() && recovery.invalidBlobs == 0
}

interface DocumentStartupMetadataSource {
    suspend fun listAll(): List<VaultDocumentMetadata>
}

class RoomDocumentStartupMetadataSource(
    private val records: DocumentRecordDao,
) : DocumentStartupMetadataSource {
    override suspend fun listAll(): List<VaultDocumentMetadata> =
        records.listAll().map(DocumentRecordEntity::toStartupMetadata)
}

class DocumentVaultStartupCoordinator(
    private val metadataSource: DocumentStartupMetadataSource,
    private val blobStore: RecoverableSecureBlobStore,
    private val secureBlobService: SecureBlobService,
    private val searchProjection: DocumentSearchProjection,
) {
    suspend fun start(): DocumentVaultStartupReport {
        val metadataRecords = metadataSource.listAll().sortedBy { it.id }

        // A startup projection must never preserve a row that has not been re-verified.
        metadataRecords.forEach { searchProjection.remove(it.id) }

        val referencedDocuments = metadataRecords.filter {
            it.lifecycle == DocumentLifecycle.ACTIVE || it.lifecycle == DocumentLifecycle.ARCHIVED
        }
        val issues = mutableListOf<DocumentVaultStartupIssue>()
        val safeDocuments = referencedDocuments.filter { metadata ->
            if (isSafeBlobReference(metadata.blobId)) {
                true
            } else {
                issues += metadata.issue(DocumentVaultStartupIssueKind.INVALID_BLOB_REFERENCE)
                false
            }
        }
        val duplicateBlobIds = safeDocuments
            .groupBy { it.blobId }
            .filterValues { it.size > 1 }
            .keys
        safeDocuments
            .filter { it.blobId in duplicateBlobIds }
            .forEach { issues += it.issue(DocumentVaultStartupIssueKind.DUPLICATE_BLOB_REFERENCE) }

        // Preserve every valid active/archive reference during orphan recovery, including duplicates.
        val referencedBlobIds = safeDocuments.mapTo(mutableSetOf()) { it.blobId }
        val recovery = blobStore.recover(referencedBlobIds)

        var verifiedActive = 0
        var verifiedArchived = 0
        safeDocuments.forEach { metadata ->
            if (metadata.blobId in duplicateBlobIds) return@forEach
            val stored = try {
                blobStore.read(metadata.blobId)
            } catch (_: Throwable) {
                issues += metadata.issue(DocumentVaultStartupIssueKind.INVALID_BLOB_RECORD)
                return@forEach
            }
            if (stored == null) {
                issues += metadata.issue(DocumentVaultStartupIssueKind.MISSING_BLOB)
                return@forEach
            }
            if (stored.first.blobId != metadata.blobId || stored.first.contentType != metadata.contentType) {
                issues += metadata.issue(DocumentVaultStartupIssueKind.BLOB_METADATA_MISMATCH)
                return@forEach
            }

            val envelope = try {
                SecureBlobCodec.decode(stored.second.ciphertext)
            } catch (_: Throwable) {
                issues += metadata.issue(DocumentVaultStartupIssueKind.INVALID_BLOB_RECORD)
                return@forEach
            }
            if (envelope.version != stored.first.envelopeVersion) {
                issues += metadata.issue(DocumentVaultStartupIssueKind.ENVELOPE_METADATA_MISMATCH)
                return@forEach
            }
            if (envelope.ciphertext.size < AUTH_TAG_BYTES ||
                !stored.second.integrityTag.contentEquals(
                    envelope.ciphertext.copyOfRange(envelope.ciphertext.size - AUTH_TAG_BYTES, envelope.ciphertext.size),
                )
            ) {
                issues += metadata.issue(DocumentVaultStartupIssueKind.INTEGRITY_MISMATCH)
                return@forEach
            }

            val plaintext = try {
                secureBlobService.unprotect(
                    stored.second.ciphertext,
                    startupSecureBlobContext(metadata.blobId, metadata.contentType),
                )
            } catch (_: Throwable) {
                issues += metadata.issue(DocumentVaultStartupIssueKind.AUTHENTICATION_FAILED)
                return@forEach
            }
            plaintext.fill(0)

            when (metadata.lifecycle) {
                DocumentLifecycle.ACTIVE -> {
                    searchProjection.publish(metadata)
                    verifiedActive += 1
                }
                DocumentLifecycle.ARCHIVED -> verifiedArchived += 1
                DocumentLifecycle.LEGACY_MIGRATION_REQUIRED -> Unit
            }
        }

        return DocumentVaultStartupReport(
            recovery = recovery,
            metadataRecords = metadataRecords.size,
            referencedDocuments = referencedDocuments.size,
            recoverableReferences = referencedBlobIds.size,
            verifiedActiveDocuments = verifiedActive,
            verifiedArchivedDocuments = verifiedArchived,
            issues = issues.sortedWith(
                compareBy<DocumentVaultStartupIssue> { it.documentId }
                    .thenBy { it.blobId }
                    .thenBy { it.kind.name },
            ),
        )
    }
}

private fun DocumentRecordEntity.toStartupMetadata(): VaultDocumentMetadata = VaultDocumentMetadata(
    id = id,
    title = title,
    label = label,
    blobId = blobId,
    contentType = contentType,
    revision = revision,
    lifecycle = DocumentLifecycle.valueOf(lifecycle),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun VaultDocumentMetadata.issue(kind: DocumentVaultStartupIssueKind): DocumentVaultStartupIssue =
    DocumentVaultStartupIssue(documentId = id, blobId = blobId, kind = kind)

private fun isSafeBlobReference(blobId: String): Boolean {
    if (blobId.isBlank() || blobId == "." || blobId == "..") return false
    if (blobId.encodeToByteArray().size > MAX_STARTUP_BLOB_ID_BYTES) return false
    return blobId.none { it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }
}

private fun startupSecureBlobContext(blobId: String, contentType: String): ByteArray {
    val blobIdBytes = blobId.encodeToByteArray()
    val contentTypeBytes = contentType.encodeToByteArray()
    val secondLengthOffset = 4 + blobIdBytes.size
    return ByteArray(8 + blobIdBytes.size + contentTypeBytes.size).also { output ->
        writeInt32(output, 0, blobIdBytes.size)
        blobIdBytes.copyInto(output, destinationOffset = 4)
        writeInt32(output, secondLengthOffset, contentTypeBytes.size)
        contentTypeBytes.copyInto(output, destinationOffset = secondLengthOffset + 4)
    }
}

private fun writeInt32(output: ByteArray, offset: Int, value: Int) {
    require(value >= 0)
    output[offset] = (value ushr 24).toByte()
    output[offset + 1] = (value ushr 16).toByte()
    output[offset + 2] = (value ushr 8).toByte()
    output[offset + 3] = value.toByte()
}

private const val AUTH_TAG_BYTES = 16
private const val MAX_STARTUP_BLOB_ID_BYTES = 1024
