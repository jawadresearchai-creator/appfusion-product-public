package com.appfusion.product.shared.vault

import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.SecureBlobMetadata
import com.appfusion.product.shared.security.DeviceKeyWrapper
import com.appfusion.product.shared.security.SecureBlobCodec
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.security.unwrapWithRawAesKey
import com.appfusion.product.shared.security.wrapWithRawAesKey
import com.appfusion.product.shared.storage.BlobRecoveryReport
import com.appfusion.product.shared.storage.RecoverableSecureBlobStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class StartupContractKeyWrapper : DeviceKeyWrapper {
    override val keyId: String = "startup-contract-kek-v1"
    private val rawKey = ByteArray(32) { (it + 71).toByte() }

    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKey, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKey, wrappedKey)
}

private class FakeStartupMetadataSource(
    private val records: List<VaultDocumentMetadata>,
) : DocumentStartupMetadataSource {
    override suspend fun listAll(): List<VaultDocumentMetadata> = records
}

private class FakeRecoverableBlobStore : RecoverableSecureBlobStore {
    private val entries = mutableMapOf<String, Pair<SecureBlobMetadata, EncryptedPayload>>()
    var lastRecoveryReferences: Set<String> = emptySet()
        private set

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

    override fun recover(referencedBlobIds: Set<String>): BlobRecoveryReport {
        lastRecoveryReferences = referencedBlobIds.toSet()
        val orphans = entries.keys.filter { it !in referencedBlobIds }
        orphans.forEach { entries.remove(it) }
        return BlobRecoveryReport(
            interruptedWritesRemoved = 0,
            orphanBlobsRemoved = orphans.size,
            invalidBlobs = 0,
        )
    }
}

class DocumentVaultStartupCoordinatorContractTest {
    @Test
    fun startupFailsClosedAndPublishesOnlyVerifiedActiveMetadata() = runTest {
        val service = SecureBlobService(StartupContractKeyWrapper())
        val blobStore = FakeRecoverableBlobStore()
        val good = metadata("good", "Passport", DocumentLifecycle.ACTIVE)
        val archived = metadata("archived", "Old passport", DocumentLifecycle.ARCHIVED)
        val missing = metadata("missing", "Missing passport", DocumentLifecycle.ACTIVE)
        val wrongContext = metadata("wrong-context", "Wrong context", DocumentLifecycle.ACTIVE)
        val unsafe = metadata(
            id = "unsafe",
            title = "Unsafe reference",
            lifecycle = DocumentLifecycle.ACTIVE,
            blobId = "../escape",
        )
        val legacy = VaultDocumentMetadata(
            id = "legacy",
            title = "Legacy passport",
            label = "legacy",
            blobId = "legacy:legacy",
            contentType = "application/pdf",
            revision = 1,
            lifecycle = DocumentLifecycle.LEGACY_MIGRATION_REQUIRED,
            updatedAtEpochMillis = 5,
        )
        val orphan = metadata("orphan", "Orphan", DocumentLifecycle.ACTIVE)

        blobStore.writeAtomicEntry(good, service, "verified active".encodeToByteArray())
        blobStore.writeAtomicEntry(archived, service, "verified archived".encodeToByteArray())
        blobStore.writeAtomicEntry(
            metadata = wrongContext,
            service = service,
            plaintext = "wrong associated data".encodeToByteArray(),
            protectedContentType = "image/png",
        )
        blobStore.writeAtomicEntry(orphan, service, "unreferenced".encodeToByteArray())

        val allowAll = DocumentAccessPolicy { true }
        val search = DocumentSearchProjection("startup-contract", allowAll)
        search.publish(missing)
        search.publish(legacy.copy(lifecycle = DocumentLifecycle.ACTIVE))

        val coordinator = DocumentVaultStartupCoordinator(
            metadataSource = FakeStartupMetadataSource(
                listOf(legacy, wrongContext, good, unsafe, archived, missing),
            ),
            blobStore = blobStore,
            secureBlobService = service,
            searchProjection = search,
        )
        val report = coordinator.start()

        assertEquals(6, report.metadataRecords)
        assertEquals(5, report.referencedDocuments)
        assertEquals(4, report.recoverableReferences)
        assertEquals(1, report.verifiedActiveDocuments)
        assertEquals(1, report.verifiedArchivedDocuments)
        assertEquals(1, report.recovery.orphanBlobsRemoved)
        assertEquals(
            setOf(good.blobId, archived.blobId, missing.blobId, wrongContext.blobId),
            blobStore.lastRecoveryReferences,
        )
        assertNull(blobStore.read(orphan.blobId))

        assertEquals(
            setOf(
                DocumentVaultStartupIssueKind.MISSING_BLOB,
                DocumentVaultStartupIssueKind.AUTHENTICATION_FAILED,
                DocumentVaultStartupIssueKind.INVALID_BLOB_REFERENCE,
            ),
            report.issues.map { it.kind }.toSet(),
        )
        assertFalse(report.isClean)
        assertEquals(listOf(good.id), search.search(SearchQuery("passport")).map { it.ref.id })
        assertTrue(search.search(SearchQuery("missing")).isEmpty())
        assertTrue(search.search(SearchQuery("legacy")).isEmpty())
        assertTrue(search.search(SearchQuery("old passport")).isEmpty())
    }
}

private fun metadata(
    id: String,
    title: String,
    lifecycle: DocumentLifecycle,
    blobId: String = "$id:revision:1",
): VaultDocumentMetadata = VaultDocumentMetadata(
    id = id,
    title = title,
    label = "identity",
    blobId = blobId,
    contentType = "application/pdf",
    revision = 1,
    lifecycle = lifecycle,
    updatedAtEpochMillis = 1,
)

private suspend fun FakeRecoverableBlobStore.writeAtomicEntry(
    metadata: VaultDocumentMetadata,
    service: SecureBlobService,
    plaintext: ByteArray,
    protectedContentType: String = metadata.contentType,
) {
    val encoded = service.protect(
        plaintext,
        context = contractSecureBlobContext(metadata.blobId, protectedContentType),
    )
    val envelope = SecureBlobCodec.decode(encoded)
    writeAtomic(
        SecureBlobMetadata(metadata.blobId, envelope.version, metadata.contentType),
        EncryptedPayload(
            ciphertext = encoded,
            integrityTag = envelope.ciphertext.copyOfRange(
                envelope.ciphertext.size - 16,
                envelope.ciphertext.size,
            ),
        ),
    )
}

private fun contractSecureBlobContext(blobId: String, contentType: String): ByteArray {
    val blobIdBytes = blobId.encodeToByteArray()
    val contentTypeBytes = contentType.encodeToByteArray()
    val secondLengthOffset = 4 + blobIdBytes.size
    return ByteArray(8 + blobIdBytes.size + contentTypeBytes.size).also { output ->
        contractWriteInt32(output, 0, blobIdBytes.size)
        blobIdBytes.copyInto(output, destinationOffset = 4)
        contractWriteInt32(output, secondLengthOffset, contentTypeBytes.size)
        contentTypeBytes.copyInto(output, destinationOffset = secondLengthOffset + 4)
    }
}

private fun contractWriteInt32(output: ByteArray, offset: Int, value: Int) {
    output[offset] = (value ushr 24).toByte()
    output[offset + 1] = (value ushr 16).toByte()
    output[offset + 2] = (value ushr 8).toByte()
    output[offset + 3] = value.toByte()
}
