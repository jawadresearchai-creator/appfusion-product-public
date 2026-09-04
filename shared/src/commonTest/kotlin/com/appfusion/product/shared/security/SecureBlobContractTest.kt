package com.appfusion.product.shared.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private class InMemoryKeyWrapper(
    override val keyId: String = "test-kek-v1",
    private val rawKek: ByteArray = ByteArray(32) { (it + 1).toByte() },
) : DeviceKeyWrapper {
    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKek, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKek, wrappedKey)
}

class SecureBlobContractTest {
    @Test
    fun envelopeCodecIsCanonicalAndStrict() {
        val envelope = SecureBlobEnvelope(
            version = 2,
            keyId = "test-kek-v1",
            wrappedKey = byteArrayOf(1, 2, 3, 4),
            ciphertext = byteArrayOf(5, 6, 7, 8, 9),
        )
        val first = SecureBlobCodec.encode(envelope)
        val second = SecureBlobCodec.encode(envelope)
        assertContentEquals(first, second)
        val decoded = SecureBlobCodec.decode(first)
        assertEquals(2, decoded.version)
        assertEquals("test-kek-v1", decoded.keyId)
        assertContentEquals(envelope.wrappedKey, decoded.wrappedKey)
        assertContentEquals(envelope.ciphertext, decoded.ciphertext)
        assertFails { SecureBlobCodec.decode(first.copyOf(first.size - 1)) }
        val unsupported = first.copyOf().also { it[4] = 99 }
        assertFails { SecureBlobCodec.decode(unsupported) }
    }

    @Test
    fun payloadRoundTripUsesFreshPerBlobCryptography() = runTest {
        val service = SecureBlobService(InMemoryKeyWrapper())
        val plaintext = "private document payload".encodeToByteArray()
        val first = service.protect(plaintext)
        val second = service.protect(plaintext)
        assertFalse(first.contentEquals(second), "fresh DEK/nonce must change the envelope")
        assertContentEquals(plaintext, service.unprotect(first))
        assertContentEquals(plaintext, service.unprotect(second))
    }

    @Test
    fun tamperingWrappedKeyOrCiphertextIsRejected() = runTest {
        val service = SecureBlobService(InMemoryKeyWrapper())
        val encoded = service.protect("classified".encodeToByteArray())
        val envelope = SecureBlobCodec.decode(encoded)

        val tamperedWrapped = envelope.wrappedKey.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val wrappedEnvelope = SecureBlobCodec.encode(envelope.copy(wrappedKey = tamperedWrapped))
        assertSuspendFails { service.unprotect(wrappedEnvelope) }

        val tamperedCiphertext = envelope.ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val ciphertextEnvelope = SecureBlobCodec.encode(envelope.copy(ciphertext = tamperedCiphertext))
        assertSuspendFails { service.unprotect(ciphertextEnvelope) }
    }

    @Test
    fun wrongKeyIdentityIsRejectedBeforeUnwrap() = runTest {
        val service = SecureBlobService(InMemoryKeyWrapper())
        val envelope = SecureBlobCodec.decode(service.protect("data".encodeToByteArray()))
        val changed = SecureBlobCodec.encode(envelope.copy(keyId = "different-kek"))
        assertSuspendFails { service.unprotect(changed) }
    }

    @Test
    fun callerContextPreventsCiphertextReassignment() = runTest {
        val service = SecureBlobService(InMemoryKeyWrapper())
        val originalContext = "document-1:revision-1".encodeToByteArray()
        val changedContext = "document-2:revision-1".encodeToByteArray()
        val encoded = service.protect("bound payload".encodeToByteArray(), context = originalContext)
        assertContentEquals(
            "bound payload".encodeToByteArray(),
            service.unprotect(encoded, context = originalContext),
        )
        assertSuspendFails { service.unprotect(encoded, context = changedContext) }
        assertSuspendFails { service.unprotect(encoded) }
    }

    @Test
    fun versionOneEnvelopeMigratesByDecryptingAndReencrypting() = runTest {
        val service = SecureBlobService(InMemoryKeyWrapper())
        val plaintext = "migration payload".encodeToByteArray()
        val legacy = service.protect(plaintext, version = 1)
        assertEquals(1, SecureBlobCodec.decode(legacy).version)
        val migrated = service.migrate(legacy, targetVersion = 2)
        assertEquals(2, SecureBlobCodec.decode(migrated).version)
        assertFalse(legacy.contentEquals(migrated))
        assertContentEquals(plaintext, service.unprotect(migrated))
    }
}

private suspend fun assertSuspendFails(block: suspend () -> Unit) {
    var failed = false
    try {
        block()
    } catch (_: Throwable) {
        failed = true
    }
    assertTrue(failed, "expected authenticated operation to fail")
}
