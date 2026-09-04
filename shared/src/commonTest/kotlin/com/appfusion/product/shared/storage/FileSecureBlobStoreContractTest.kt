package com.appfusion.product.shared.storage

import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.SecureBlobMetadata
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSecureBlobStoreContractTest {
    @Test
    fun strictFileRoundTripAndPathBoundary() {
        val backend = InMemoryAtomicBlobFileBackend()
        val store = AtomicFileSecureBlobStore(backend)
        val metadata = SecureBlobMetadata("document-1:revision:1", 2, "application/pdf")
        val payload = EncryptedPayload(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7))

        store.writeAtomic(metadata, payload)
        val stored = assertNotNull(store.read(metadata.blobId))
        assertEquals(metadata, stored.first)
        assertContentEquals(payload.ciphertext, stored.second.ciphertext)
        assertContentEquals(payload.integrityTag, stored.second.integrityTag)
        assertEquals(1, backend.files.size)
        assertTrue(backend.files.keys.single().endsWith(".blob"))

        assertFailsWith<IllegalArgumentException> {
            store.writeAtomic(metadata.copy(blobId = "../../escape"), payload)
        }
        assertFailsWith<IllegalArgumentException> { store.read("folder/escape") }
        assertEquals(1, backend.files.size, "rejected paths must not create files")
    }

    @Test
    fun interruptedReplacementPreservesOldBlobAndRecoversTemporaryFile() {
        val backend = InMemoryAtomicBlobFileBackend()
        val metadata = SecureBlobMetadata("document-1:revision:1", 2, "application/pdf")
        val original = EncryptedPayload(byteArrayOf(1, 2, 3), byteArrayOf(4))
        val replacement = EncryptedPayload(byteArrayOf(9, 8, 7), byteArrayOf(6))
        val store = AtomicFileSecureBlobStore(backend)
        store.writeAtomic(metadata, original)

        backend.failBeforeReplace = true
        assertFailsWith<IllegalStateException> { store.writeAtomic(metadata, replacement) }
        assertContentEquals(original.ciphertext, assertNotNull(store.read(metadata.blobId)).second.ciphertext)
        assertTrue(backend.files.keys.any(SecureBlobFileNaming::isTemporaryFileName))

        val restarted = AtomicFileSecureBlobStore(backend)
        val report = restarted.recover(setOf(metadata.blobId))
        assertEquals(1, report.interruptedWritesRemoved)
        assertEquals(0, report.orphanBlobsRemoved)
        assertEquals(0, report.invalidBlobs)
        assertFalse(backend.files.keys.any(SecureBlobFileNaming::isTemporaryFileName))
        assertContentEquals(original.ciphertext, assertNotNull(restarted.read(metadata.blobId)).second.ciphertext)
    }

    @Test
    fun recoveryRemovesOnlyUnreferencedValidBlobsAndReportsCorruption() {
        val backend = InMemoryAtomicBlobFileBackend()
        val store = AtomicFileSecureBlobStore(backend)
        val keep = SecureBlobMetadata("keep:revision:1", 2, "application/pdf")
        val orphan = SecureBlobMetadata("orphan:revision:1", 2, "application/pdf")
        val corrupt = SecureBlobMetadata("corrupt:revision:1", 2, "application/pdf")
        val payload = EncryptedPayload(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        store.writeAtomic(keep, payload)
        store.writeAtomic(orphan, payload)
        store.writeAtomic(corrupt, payload)
        backend.corrupt(SecureBlobFileNaming.fileName(corrupt.blobId))

        val report = store.recover(setOf(keep.blobId, corrupt.blobId))
        assertEquals(0, report.interruptedWritesRemoved)
        assertEquals(1, report.orphanBlobsRemoved)
        assertEquals(1, report.invalidBlobs)
        assertNotNull(store.read(keep.blobId))
        assertNull(store.read(orphan.blobId))
        assertFailsWith<IllegalArgumentException> { store.read(corrupt.blobId) }
    }
}

private class InMemoryAtomicBlobFileBackend : AtomicBlobFileBackend {
    val files = mutableMapOf<String, ByteArray>()
    var failBeforeReplace = false

    override fun ensureRoot() = Unit

    override fun writeAtomically(targetFileName: String, bytes: ByteArray) {
        SecureBlobFileNaming.requireSafeInternalFileName(targetFileName)
        val temporary = SecureBlobFileNaming.temporaryFileName(targetFileName)
        files[temporary] = bytes.copyOf()
        if (failBeforeReplace) {
            failBeforeReplace = false
            error("intentional interruption before atomic replacement")
        }
        files[targetFileName] = assertNotNull(files[temporary]).copyOf()
        files.remove(temporary)
    }

    override fun read(fileName: String): ByteArray? = files[fileName]?.copyOf()
    override fun delete(fileName: String): Boolean = files.remove(fileName) != null
    override fun listFileNames(): List<String> = files.keys.sorted()

    fun corrupt(fileName: String) {
        val bytes = assertNotNull(files[fileName]).copyOf()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        files[fileName] = bytes
    }
}
