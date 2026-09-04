package com.appfusion.product.shared.vault

import com.appfusion.product.shared.BackupRecord
import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.EntityDomain
import com.appfusion.product.shared.EntityRef
import com.appfusion.product.shared.InMemoryAppendOnlyActivityEventLog
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.SecureBlobMetadata
import com.appfusion.product.shared.SecureBlobStore
import com.appfusion.product.shared.security.DeviceKeyWrapper
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.security.unwrapWithRawAesKey
import com.appfusion.product.shared.security.wrapWithRawAesKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RestoreContractKeyWrapper : DeviceKeyWrapper {
    override val keyId: String = "restore-contract-kek-v1"
    private val rawKey = ByteArray(32) { (it + 101).toByte() }

    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKey, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKey, wrappedKey)
}

private open class RestoreContractMetadataStore : DocumentMetadataStore {
    private val records = mutableMapOf<String, VaultDocumentMetadata>()

    override suspend fun find(id: String): VaultDocumentMetadata? = records[id]
    override suspend fun put(metadata: VaultDocumentMetadata) {
        records[metadata.id] = metadata
    }
    override suspend fun listActive(): List<VaultDocumentMetadata> = records.values
        .filter { it.lifecycle == DocumentLifecycle.ACTIVE }
        .sortedBy { it.id }
    override suspend fun delete(id: String): Boolean = records.remove(id) != null
}

private class FailableRestoreContractMetadataStore : RestoreContractMetadataStore() {
    var failNextPut = false

    override suspend fun put(metadata: VaultDocumentMetadata) {
        if (failNextPut) {
            failNextPut = false
            error("intentional restore metadata failure")
        }
        super.put(metadata)
    }
}

private class RestoreContractBlobStore : SecureBlobStore {
    private val entries = mutableMapOf<String, Pair<SecureBlobMetadata, EncryptedPayload>>()

    override fun writeAtomic(metadata: SecureBlobMetadata, payload: EncryptedPayload) {
        entries[metadata.blobId] = metadata.copy() to EncryptedPayload(
            payload.ciphertext.copyOf(),
            payload.integrityTag.copyOf(),
        )
    }

    override fun read(blobId: String): Pair<SecureBlobMetadata, EncryptedPayload>? =
        entries[blobId]?.let { (metadata, payload) ->
            metadata.copy() to EncryptedPayload(payload.ciphertext.copyOf(), payload.integrityTag.copyOf())
        }

    override fun delete(blobId: String): Boolean = entries.remove(blobId) != null
    fun contains(blobId: String): Boolean = blobId in entries
}

class DocumentVaultBackupRestoreCoordinatorContractTest {
    private val allowAll = DocumentAccessPolicy { true }

    @Test
    fun restoresActiveAndArchivedBackupsAndRepeatsIdempotently() = runTest {
        val service = SecureBlobService(RestoreContractKeyWrapper())
        val sourceMetadata = RestoreContractMetadataStore()
        val sourceBlobs = RestoreContractBlobStore()
        val sourceSearch = DocumentSearchProjection("restore-source", allowAll)
        val source = DocumentVaultRepository(
            metadataStore = sourceMetadata,
            blobStore = sourceBlobs,
            secureBlobService = service,
            eventLog = InMemoryAppendOnlyActivityEventLog(),
            searchProjection = sourceSearch,
        )
        val activePlaintext = "active encrypted backup".encodeToByteArray()
        val archivedPlaintext = "archived encrypted backup".encodeToByteArray()
        source.create("active", "Passport", "identity", "application/pdf", activePlaintext, 100)
        source.create("archived", "Old passport", "archive", "application/pdf", archivedPlaintext, 200)
        source.archive("archived", 300)
        val activeBackup = assertNotNull(source.exportBackupRecord("active", allowAll))
        val archivedBackup = assertNotNull(source.exportBackupRecord("archived", allowAll))

        val targetMetadata = RestoreContractMetadataStore()
        val targetBlobs = RestoreContractBlobStore()
        val targetSearch = DocumentSearchProjection("restore-target", allowAll)
        val coordinator = DocumentVaultBackupRestoreCoordinator(
            metadataStore = targetMetadata,
            blobStore = targetBlobs,
            secureBlobService = service,
            searchProjection = targetSearch,
        )

        assertEquals(DocumentBackupRestoreDisposition.RESTORED, coordinator.restore(activeBackup).disposition)
        assertEquals(DocumentBackupRestoreDisposition.RESTORED, coordinator.restore(archivedBackup).disposition)
        assertEquals(DocumentBackupRestoreDisposition.ALREADY_PRESENT, coordinator.restore(activeBackup).disposition)
        assertEquals(listOf("active"), targetSearch.search(SearchQuery("passport")).map { it.ref.id })
        assertTrue(targetSearch.search(SearchQuery("old passport")).isEmpty())

        val targetRepository = DocumentVaultRepository(
            metadataStore = targetMetadata,
            blobStore = targetBlobs,
            secureBlobService = service,
            eventLog = InMemoryAppendOnlyActivityEventLog(),
            searchProjection = targetSearch,
        )
        assertContentEquals(activePlaintext, assertNotNull(targetRepository.read("active", allowAll)).plaintext)
        assertContentEquals(archivedPlaintext, assertNotNull(targetRepository.read("archived", allowAll)).plaintext)
        assertEquals(DocumentLifecycle.ARCHIVED, assertNotNull(targetMetadata.find("archived")).lifecycle)
    }

    @Test
    fun rejectsTamperIdentityAndAuthenticatedContextMismatchBeforeWrites() = runTest {
        val service = SecureBlobService(RestoreContractKeyWrapper())
        val backup = createBackup(service, "document-a", "application/pdf", "secret payload")
        val targetMetadata = RestoreContractMetadataStore()
        val targetBlobs = RestoreContractBlobStore()
        val targetSearch = DocumentSearchProjection("restore-reject", allowAll)
        val coordinator = DocumentVaultBackupRestoreCoordinator(targetMetadata, targetBlobs, service, targetSearch)

        val wrongRef = backup.copy(ref = EntityRef(EntityDomain.DOCUMENT, "different-document"))
        assertEquals(DocumentBackupRestoreIssueKind.IDENTITY_MISMATCH, coordinator.restore(wrongRef).issue)

        val tampered = backup.payload.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        assertEquals(
            DocumentBackupRestoreIssueKind.INVALID_BACKUP,
            coordinator.restore(backup.copy(payload = tampered)).issue,
        )

        val decoded = DocumentBackupCodec.decode(backup.payload)
        val changedContentType = "application/octet-stream"
        val mismatchedPayload = DocumentBackupCodec.encode(
            decoded.metadata.copy(contentType = changedContentType),
            decoded.secureMetadata.copy(contentType = changedContentType),
            decoded.securePayload,
        )
        assertEquals(
            DocumentBackupRestoreIssueKind.AUTHENTICATION_FAILED,
            coordinator.restore(backup.copy(payload = mismatchedPayload)).issue,
        )

        assertNull(targetMetadata.find("document-a"))
        assertFalse(targetBlobs.contains(decoded.metadata.blobId))
        assertTrue(targetSearch.search(SearchQuery("secret")).isEmpty())
    }

    @Test
    fun conflictsAreDeterministicAndPreserveExistingEvidence() = runTest {
        val service = SecureBlobService(RestoreContractKeyWrapper())
        val first = createBackup(service, "same-id", "application/pdf", "first payload", title = "First")
        val conflicting = createBackup(service, "same-id", "application/pdf", "second payload", title = "Second")
        val targetMetadata = RestoreContractMetadataStore()
        val targetBlobs = RestoreContractBlobStore()
        val targetSearch = DocumentSearchProjection("restore-conflict", allowAll)
        val coordinator = DocumentVaultBackupRestoreCoordinator(targetMetadata, targetBlobs, service, targetSearch)

        assertEquals(DocumentBackupRestoreDisposition.RESTORED, coordinator.restore(first).disposition)
        val firstDecoded = DocumentBackupCodec.decode(first.payload)
        val beforeConflict = assertNotNull(targetBlobs.read(firstDecoded.metadata.blobId))
        assertEquals(DocumentBackupRestoreIssueKind.DOCUMENT_CONFLICT, coordinator.restore(conflicting).issue)
        val afterConflict = assertNotNull(targetBlobs.read(firstDecoded.metadata.blobId))
        assertContentEquals(beforeConflict.second.ciphertext, afterConflict.second.ciphertext)

        val orphanBackup = createBackup(service, "orphan-id", "application/pdf", "orphan evidence")
        val orphanDecoded = DocumentBackupCodec.decode(orphanBackup.payload)
        targetBlobs.writeAtomic(orphanDecoded.secureMetadata, orphanDecoded.securePayload)
        assertEquals(DocumentBackupRestoreIssueKind.BLOB_CONFLICT, coordinator.restore(orphanBackup).issue)
        assertTrue(targetBlobs.contains(orphanDecoded.metadata.blobId))
        assertNull(targetMetadata.find("orphan-id"))
    }

    @Test
    fun metadataCommitFailureRemovesStagedEncryptedBlob() = runTest {
        val service = SecureBlobService(RestoreContractKeyWrapper())
        val backup = createBackup(service, "rollback-id", "application/pdf", "rollback payload")
        val decoded = DocumentBackupCodec.decode(backup.payload)
        val targetMetadata = FailableRestoreContractMetadataStore().also { it.failNextPut = true }
        val targetBlobs = RestoreContractBlobStore()
        val targetSearch = DocumentSearchProjection("restore-rollback", allowAll)
        val coordinator = DocumentVaultBackupRestoreCoordinator(targetMetadata, targetBlobs, service, targetSearch)

        var failed = false
        try {
            coordinator.restore(backup)
        } catch (_: Throwable) {
            failed = true
        }
        assertTrue(failed)
        assertNull(targetMetadata.find("rollback-id"))
        assertFalse(targetBlobs.contains(decoded.metadata.blobId))
        assertTrue(targetSearch.search(SearchQuery("rollback")).isEmpty())
    }

    private suspend fun createBackup(
        service: SecureBlobService,
        id: String,
        contentType: String,
        plaintext: String,
        title: String = "Backup document",
    ): BackupRecord {
        val metadata = RestoreContractMetadataStore()
        val blobs = RestoreContractBlobStore()
        val search = DocumentSearchProjection("source-$id-${title.length}", allowAll)
        val repository = DocumentVaultRepository(
            metadataStore = metadata,
            blobStore = blobs,
            secureBlobService = service,
            eventLog = InMemoryAppendOnlyActivityEventLog(),
            searchProjection = search,
        )
        repository.create(id, title, "backup", contentType, plaintext.encodeToByteArray(), 100)
        return assertNotNull(repository.exportBackupRecord(id, allowAll))
    }
}
