package com.appfusion.product.shared.vault

import com.appfusion.product.shared.BackupRecord
import com.appfusion.product.shared.EntityDomain
import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.SecureBlobMetadata
import com.appfusion.product.shared.SecureBlobStore
import com.appfusion.product.shared.security.SecureBlobService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DOCUMENT_BACKUP_RESTORE_SCHEMA_VERSION = 1
private const val MAX_RESTORE_BLOB_ID_BYTES = 1024
private const val MAX_RESTORE_CONTENT_TYPE_BYTES = 4096

enum class DocumentBackupRestoreDisposition {
    RESTORED,
    ALREADY_PRESENT,
    REJECTED,
}

enum class DocumentBackupRestoreIssueKind {
    WRONG_DOMAIN,
    UNSUPPORTED_SCHEMA,
    INVALID_BACKUP,
    IDENTITY_MISMATCH,
    UNSAFE_BLOB_REFERENCE,
    UNSUPPORTED_BLOB_METADATA,
    LEGACY_LIFECYCLE,
    AUTHENTICATION_FAILED,
    INVALID_DOCUMENT_PAYLOAD,
    DOCUMENT_CONFLICT,
    BLOB_CONFLICT,
}

data class DocumentBackupRestoreResult(
    val disposition: DocumentBackupRestoreDisposition,
    val metadata: VaultDocumentMetadata? = null,
    val issue: DocumentBackupRestoreIssueKind? = null,
) {
    init {
        require((disposition == DocumentBackupRestoreDisposition.REJECTED) == (issue != null)) {
            "Rejected restore results must contain exactly one stable issue category"
        }
        if (disposition != DocumentBackupRestoreDisposition.REJECTED) {
            require(metadata != null) { "Successful restore results must include metadata" }
        }
    }
}

class DocumentVaultBackupRestoreCoordinator(
    private val metadataStore: DocumentMetadataStore,
    private val blobStore: SecureBlobStore,
    private val secureBlobService: SecureBlobService,
    private val searchProjection: DocumentSearchProjection,
) {
    private val restoreMutex = Mutex()

    suspend fun restore(record: BackupRecord): DocumentBackupRestoreResult = restoreMutex.withLock {
        restoreLocked(record)
    }

    private suspend fun restoreLocked(record: BackupRecord): DocumentBackupRestoreResult {
        if (record.ref.domain != EntityDomain.DOCUMENT) {
            return rejected(DocumentBackupRestoreIssueKind.WRONG_DOMAIN)
        }
        if (record.schemaVersion != DOCUMENT_BACKUP_RESTORE_SCHEMA_VERSION) {
            return rejected(DocumentBackupRestoreIssueKind.UNSUPPORTED_SCHEMA)
        }

        val decoded = try {
            DocumentBackupCodec.decode(record.payload)
        } catch (_: Throwable) {
            return rejected(DocumentBackupRestoreIssueKind.INVALID_BACKUP)
        }
        val metadata = decoded.metadata

        if (record.ref != metadata.ref) {
            return rejected(DocumentBackupRestoreIssueKind.IDENTITY_MISMATCH, metadata)
        }
        if (!isSafeRestoreBlobReference(metadata.blobId)) {
            return rejected(DocumentBackupRestoreIssueKind.UNSAFE_BLOB_REFERENCE, metadata)
        }
        if (metadata.contentType.encodeToByteArray().size > MAX_RESTORE_CONTENT_TYPE_BYTES) {
            return rejected(DocumentBackupRestoreIssueKind.UNSUPPORTED_BLOB_METADATA, metadata)
        }
        if (metadata.lifecycle == DocumentLifecycle.LEGACY_MIGRATION_REQUIRED) {
            return rejected(DocumentBackupRestoreIssueKind.LEGACY_LIFECYCLE, metadata)
        }

        val plaintext = try {
            secureBlobService.unprotect(
                decoded.securePayload.ciphertext,
                restoreSecureBlobContext(metadata.blobId, metadata.contentType),
            )
        } catch (_: Throwable) {
            return rejected(DocumentBackupRestoreIssueKind.AUTHENTICATION_FAILED, metadata)
        }
        val payloadValid = plaintext.isNotEmpty()
        plaintext.fill(0)
        if (!payloadValid) {
            return rejected(DocumentBackupRestoreIssueKind.INVALID_DOCUMENT_PAYLOAD, metadata)
        }

        val existingMetadata = metadataStore.find(metadata.id)
        if (existingMetadata != null) {
            if (existingMetadata == metadata) {
                val existingBlob = try {
                    blobStore.read(metadata.blobId)
                } catch (_: Throwable) {
                    null
                }
                if (existingBlob != null && existingBlob.matches(decoded.secureMetadata, decoded.securePayload)) {
                    searchProjection.publish(metadata)
                    return DocumentBackupRestoreResult(
                        disposition = DocumentBackupRestoreDisposition.ALREADY_PRESENT,
                        metadata = metadata,
                    )
                }
            }
            return rejected(DocumentBackupRestoreIssueKind.DOCUMENT_CONFLICT, metadata)
        }

        val preExistingBlob = try {
            blobStore.read(metadata.blobId)
        } catch (_: Throwable) {
            return rejected(DocumentBackupRestoreIssueKind.BLOB_CONFLICT, metadata)
        }
        if (preExistingBlob != null) {
            return rejected(DocumentBackupRestoreIssueKind.BLOB_CONFLICT, metadata)
        }

        blobStore.writeAtomic(decoded.secureMetadata, decoded.securePayload)
        try {
            metadataStore.put(metadata)
        } catch (failure: Throwable) {
            val cleaned = runCatching { blobStore.delete(metadata.blobId) }.getOrDefault(false)
            if (!cleaned) {
                val stagedBlobStillPresent = runCatching { blobStore.read(metadata.blobId) != null }
                    .getOrDefault(true)
                if (stagedBlobStillPresent) {
                    throw IllegalStateException(
                        "Document restore metadata commit failed and staged encrypted blob cleanup did not complete",
                        failure,
                    )
                }
            }
            throw failure
        }

        searchProjection.publish(metadata)
        return DocumentBackupRestoreResult(
            disposition = DocumentBackupRestoreDisposition.RESTORED,
            metadata = metadata,
        )
    }

    private fun rejected(
        issue: DocumentBackupRestoreIssueKind,
        metadata: VaultDocumentMetadata? = null,
    ): DocumentBackupRestoreResult = DocumentBackupRestoreResult(
        disposition = DocumentBackupRestoreDisposition.REJECTED,
        metadata = metadata,
        issue = issue,
    )
}

private fun Pair<SecureBlobMetadata, EncryptedPayload>.matches(
    expectedMetadata: SecureBlobMetadata,
    expectedPayload: EncryptedPayload,
): Boolean = first == expectedMetadata &&
    second.ciphertext.contentEquals(expectedPayload.ciphertext) &&
    second.integrityTag.contentEquals(expectedPayload.integrityTag)

private fun isSafeRestoreBlobReference(blobId: String): Boolean {
    if (blobId.isBlank() || blobId == "." || blobId == "..") return false
    if (blobId.encodeToByteArray().size > MAX_RESTORE_BLOB_ID_BYTES) return false
    return blobId.none { it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }
}

private fun restoreSecureBlobContext(blobId: String, contentType: String): ByteArray {
    val blobIdBytes = blobId.encodeToByteArray()
    val contentTypeBytes = contentType.encodeToByteArray()
    val secondLengthOffset = 4 + blobIdBytes.size
    return ByteArray(8 + blobIdBytes.size + contentTypeBytes.size).also { output ->
        writeRestoreInt32(output, 0, blobIdBytes.size)
        blobIdBytes.copyInto(output, destinationOffset = 4)
        writeRestoreInt32(output, secondLengthOffset, contentTypeBytes.size)
        contentTypeBytes.copyInto(output, destinationOffset = secondLengthOffset + 4)
    }
}

private fun writeRestoreInt32(output: ByteArray, offset: Int, value: Int) {
    require(value >= 0)
    output[offset] = (value ushr 24).toByte()
    output[offset + 1] = (value ushr 16).toByte()
    output[offset + 2] = (value ushr 8).toByte()
    output[offset + 3] = value.toByte()
}
