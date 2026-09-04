package com.appfusion.product.shared.persistence

import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.InMemoryAppendOnlyActivityEventLog
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.SecureBlobMetadata
import com.appfusion.product.shared.SecureBlobStore
import com.appfusion.product.shared.security.DeviceKeyWrapper
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.security.unwrapWithRawAesKey
import com.appfusion.product.shared.security.wrapWithRawAesKey
import com.appfusion.product.shared.vault.DocumentAccessPolicy
import com.appfusion.product.shared.vault.DocumentBackupCodec
import com.appfusion.product.shared.vault.DocumentLifecycle
import com.appfusion.product.shared.vault.DocumentMetadataStore
import com.appfusion.product.shared.vault.DocumentSearchProjection
import com.appfusion.product.shared.vault.DocumentVaultRepository
import com.appfusion.product.shared.vault.RoomDocumentMetadataStore
import com.appfusion.product.shared.vault.VaultDocumentMetadata
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class VaultTestKeyWrapper : DeviceKeyWrapper {
    override val keyId: String = "vault-test-kek-v1"
    private val rawKey = ByteArray(32) { (it + 31).toByte() }
    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKey, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKey, wrappedKey)
}

private class InMemoryAtomicBlobStore : SecureBlobStore {
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

private class FailableMetadataStore(
    private val delegate: DocumentMetadataStore,
) : DocumentMetadataStore by delegate {
    var failNextPut: Boolean = false

    override suspend fun put(metadata: VaultDocumentMetadata) {
        if (failNextPut) {
            failNextPut = false
            error("intentional metadata-store failure")
        }
        delegate.put(metadata)
    }
}

suspend fun assertDocumentVaultLifecycle(database: DocumentDomainDatabase) {
    val metadataStore = FailableMetadataStore(RoomDocumentMetadataStore(database.records()))
    val blobStore = InMemoryAtomicBlobStore()
    val eventLog = InMemoryAppendOnlyActivityEventLog()
    val allowAll = DocumentAccessPolicy { true }
    val search = DocumentSearchProjection("document-vault", allowAll)
    val repository = DocumentVaultRepository(
        metadataStore = metadataStore,
        blobStore = blobStore,
        secureBlobService = SecureBlobService(VaultTestKeyWrapper()),
        eventLog = eventLog,
        searchProjection = search,
    )

    val originalPlaintext = "private passport payload".encodeToByteArray()
    val created = repository.create(
        id = "document-1",
        title = "Passport",
        label = "identity",
        contentType = "application/pdf",
        plaintext = originalPlaintext,
        occurredAtEpochMillis = 100L,
    )
    assertEquals(1L, created.revision)
    assertContentEquals(originalPlaintext, assertNotNull(repository.read("document-1", allowAll)).plaintext)
    assertEquals(listOf("document-1"), search.search(SearchQuery("pass")).map { it.ref.id })
    val storedOriginal = assertNotNull(blobStore.read(created.blobId))
    assertFalse(storedOriginal.second.ciphertext.containsSlice(originalPlaintext))

    metadataStore.failNextPut = true
    assertSuspendFails {
        repository.update(
            id = "document-1",
            title = "Passport failed edit",
            label = "identity",
            contentType = "application/pdf",
            plaintext = "must not become visible".encodeToByteArray(),
            occurredAtEpochMillis = 200L,
        )
    }
    assertContentEquals(originalPlaintext, assertNotNull(repository.read("document-1", allowAll)).plaintext)
    assertFalse(blobStore.contains("document-1:revision:2"), "failed metadata commit must remove staged blob")
    assertEquals(1, eventLog.readAfter(0).size, "failed update must not emit an activity event")

    val updatedPlaintext = "private passport payload v2".encodeToByteArray()
    val updated = repository.update(
        id = "document-1",
        title = "Passport renewed",
        label = "identity 2036",
        contentType = "application/pdf",
        plaintext = updatedPlaintext,
        occurredAtEpochMillis = 300L,
    )
    assertEquals(2L, updated.revision)
    assertFalse(blobStore.contains(created.blobId), "superseded encrypted blob should be collected")
    assertContentEquals(updatedPlaintext, assertNotNull(repository.read("document-1", allowAll)).plaintext)

    val denied = DocumentAccessPolicy { false }
    assertNull(repository.read("document-1", denied), "unauthorized reads must not reveal document presence")
    assertNull(repository.exportBackupRecord("document-1", denied))
    val deniedSearch = DocumentSearchProjection("document-vault-denied", denied).also { it.publish(updated) }
    assertTrue(deniedSearch.search(SearchQuery("passport")).isEmpty())

    val backup = assertNotNull(repository.exportBackupRecord("document-1", allowAll))
    assertEquals(1, backup.schemaVersion)
    val decoded = DocumentBackupCodec.decode(backup.payload)
    assertEquals(updated, decoded.metadata)
    assertContentEquals(
        assertNotNull(blobStore.read(updated.blobId)).second.ciphertext,
        decoded.securePayload.ciphertext,
    )
    val corruptBackup = backup.payload.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    assertSuspendFails { DocumentBackupCodec.decode(corruptBackup) }

    val archived = repository.archive("document-1", occurredAtEpochMillis = 400L)
    assertEquals(DocumentLifecycle.ARCHIVED, archived.lifecycle)
    assertTrue(search.search(SearchQuery("passport")).isEmpty())
    assertEquals(
        listOf("DOCUMENT_CREATED", "DOCUMENT_UPDATED", "DOCUMENT_ARCHIVED"),
        eventLog.readAfter(0).map { it.event.kind },
    )
}

private fun ByteArray.containsSlice(candidate: ByteArray): Boolean {
    if (candidate.isEmpty() || candidate.size > size) return false
    return (0..size - candidate.size).any { start ->
        candidate.indices.all { offset -> this[start + offset] == candidate[offset] }
    }
}

private suspend fun assertSuspendFails(block: suspend () -> Unit) {
    var failed = false
    try {
        block()
    } catch (_: Throwable) {
        failed = true
    }
    assertTrue(failed, "expected operation to fail")
}
