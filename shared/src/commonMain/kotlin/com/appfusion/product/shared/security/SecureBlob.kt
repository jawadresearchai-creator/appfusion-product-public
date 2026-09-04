package com.appfusion.product.shared.security

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES

internal const val SECURE_BLOB_CURRENT_VERSION: Int = 2
private const val SECURE_BLOB_MIN_VERSION: Int = 1
private const val AES_GCM_ALGORITHM_ID: Int = 1
private const val MAX_CONTEXT_BYTES: Int = 16 * 1024
private val MAGIC = byteArrayOf(0x41, 0x46, 0x53, 0x42) // AFSB
internal val KEY_WRAP_AAD = "appfusion-secureblob-key-wrap-v1".encodeToByteArray()

interface DeviceKeyWrapper {
    val keyId: String
    suspend fun wrap(clearKey: ByteArray): ByteArray
    suspend fun unwrap(wrappedKey: ByteArray): ByteArray
}

data class SecureBlobEnvelope(
    val version: Int,
    val keyId: String,
    val wrappedKey: ByteArray,
    val ciphertext: ByteArray,
)

object SecureBlobCodec {
    private const val MAX_KEY_ID_BYTES = 128
    private const val MAX_WRAPPED_KEY_BYTES = 4096
    private const val MAX_CIPHERTEXT_BYTES = 128 * 1024 * 1024

    fun authenticatedData(version: Int, keyId: String, wrappedKey: ByteArray): ByteArray {
        validateMetadata(version, keyId, wrappedKey)
        val keyIdBytes = keyId.encodeToByteArray()
        return Writer().apply {
            bytes(MAGIC)
            u8(version)
            u8(AES_GCM_ALGORITHM_ID)
            u16(keyIdBytes.size)
            bytes(keyIdBytes)
            i32(wrappedKey.size)
            bytes(wrappedKey)
        }.toByteArray()
    }

    fun payloadAuthenticatedData(
        version: Int,
        keyId: String,
        wrappedKey: ByteArray,
        context: ByteArray,
    ): ByteArray {
        require(context.size <= MAX_CONTEXT_BYTES) { "SecureBlob context is too large" }
        val envelopeData = authenticatedData(version, keyId, wrappedKey)
        if (context.isEmpty()) return envelopeData
        return Writer().apply {
            bytes(envelopeData)
            i32(context.size)
            bytes(context)
        }.toByteArray()
    }

    fun encode(envelope: SecureBlobEnvelope): ByteArray {
        require(envelope.ciphertext.size <= MAX_CIPHERTEXT_BYTES) { "SecureBlob ciphertext too large" }
        return Writer().apply {
            bytes(authenticatedData(envelope.version, envelope.keyId, envelope.wrappedKey))
            i32(envelope.ciphertext.size)
            bytes(envelope.ciphertext)
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): SecureBlobEnvelope {
        val reader = Reader(bytes)
        require(reader.readBytes(MAGIC.size).contentEquals(MAGIC)) { "Invalid SecureBlob magic" }
        val version = reader.u8()
        require(version in SECURE_BLOB_MIN_VERSION..SECURE_BLOB_CURRENT_VERSION) {
            "Unsupported SecureBlob version: $version"
        }
        require(reader.u8() == AES_GCM_ALGORITHM_ID) { "Unsupported SecureBlob algorithm" }
        val keyIdLength = reader.u16()
        require(keyIdLength in 1..MAX_KEY_ID_BYTES) { "Invalid SecureBlob key id length" }
        val keyIdBytes = reader.readBytes(keyIdLength)
        val keyId = keyIdBytes.decodeToString(throwOnInvalidSequence = true)
        require(keyId.encodeToByteArray().contentEquals(keyIdBytes)) { "Non-canonical SecureBlob key id" }
        val wrappedLength = reader.i32()
        require(wrappedLength in 1..MAX_WRAPPED_KEY_BYTES) { "Invalid wrapped key length" }
        val wrappedKey = reader.readBytes(wrappedLength)
        val ciphertextLength = reader.i32()
        require(ciphertextLength in 1..MAX_CIPHERTEXT_BYTES) { "Invalid ciphertext length" }
        val ciphertext = reader.readBytes(ciphertextLength)
        require(reader.remaining == 0) { "Trailing SecureBlob bytes" }
        return SecureBlobEnvelope(version, keyId, wrappedKey, ciphertext)
    }

    private fun validateMetadata(version: Int, keyId: String, wrappedKey: ByteArray) {
        require(version in SECURE_BLOB_MIN_VERSION..SECURE_BLOB_CURRENT_VERSION) { "Unsupported SecureBlob version" }
        val keyIdBytes = keyId.encodeToByteArray()
        require(keyIdBytes.size in 1..MAX_KEY_ID_BYTES) { "Invalid SecureBlob key id" }
        require(wrappedKey.size in 1..MAX_WRAPPED_KEY_BYTES) { "Invalid wrapped key" }
    }
}

class SecureBlobService(private val keyWrapper: DeviceKeyWrapper) {
    suspend fun protect(
        plaintext: ByteArray,
        version: Int = SECURE_BLOB_CURRENT_VERSION,
        context: ByteArray = byteArrayOf(),
    ): ByteArray {
        val algorithm = CryptographyProvider.Default.get(AES.GCM)
        val dataKey = algorithm.keyGenerator().generateKey()
        val rawKey = dataKey.encodeToByteArray(AES.Key.Format.RAW)
        try {
            val wrappedKey = keyWrapper.wrap(rawKey)
            val aad = SecureBlobCodec.payloadAuthenticatedData(
                version,
                keyWrapper.keyId,
                wrappedKey,
                context,
            )
            val ciphertext = dataKey.cipher().encrypt(plaintext = plaintext, associatedData = aad)
            return SecureBlobCodec.encode(
                SecureBlobEnvelope(version, keyWrapper.keyId, wrappedKey, ciphertext),
            )
        } finally {
            rawKey.fill(0)
        }
    }

    suspend fun unprotect(encoded: ByteArray, context: ByteArray = byteArrayOf()): ByteArray {
        val envelope = SecureBlobCodec.decode(encoded)
        require(envelope.keyId == keyWrapper.keyId) { "SecureBlob key id does not match active wrapper" }
        val rawKey = keyWrapper.unwrap(envelope.wrappedKey)
        try {
            require(rawKey.size == 32) { "Invalid SecureBlob data key size" }
            val key = CryptographyProvider.Default.get(AES.GCM)
                .keyDecoder()
                .decodeFromByteArray(AES.Key.Format.RAW, rawKey)
            val aad = SecureBlobCodec.payloadAuthenticatedData(
                envelope.version,
                envelope.keyId,
                envelope.wrappedKey,
                context,
            )
            return key.cipher().decrypt(ciphertext = envelope.ciphertext, associatedData = aad)
        } finally {
            rawKey.fill(0)
        }
    }

    suspend fun migrate(
        encoded: ByteArray,
        targetVersion: Int = SECURE_BLOB_CURRENT_VERSION,
        context: ByteArray = byteArrayOf(),
    ): ByteArray {
        val current = SecureBlobCodec.decode(encoded)
        if (current.version == targetVersion) return encoded.copyOf()
        require(targetVersion > current.version && targetVersion <= SECURE_BLOB_CURRENT_VERSION) {
            "SecureBlob migration must move forward to a supported version"
        }
        val plaintext = unprotect(encoded, context)
        return try {
            protect(plaintext, targetVersion, context)
        } finally {
            plaintext.fill(0)
        }
    }
}

internal suspend fun generateAes256KeyBytes(): ByteArray =
    CryptographyProvider.Default.get(AES.GCM)
        .keyGenerator()
        .generateKey()
        .encodeToByteArray(AES.Key.Format.RAW)

internal suspend fun wrapWithRawAesKey(rawKey: ByteArray, clearKey: ByteArray): ByteArray {
    require(rawKey.size == 32) { "KEK must be AES-256" }
    val key = CryptographyProvider.Default.get(AES.GCM)
        .keyDecoder()
        .decodeFromByteArray(AES.Key.Format.RAW, rawKey)
    return key.cipher().encrypt(plaintext = clearKey, associatedData = KEY_WRAP_AAD)
}

internal suspend fun unwrapWithRawAesKey(rawKey: ByteArray, wrappedKey: ByteArray): ByteArray {
    require(rawKey.size == 32) { "KEK must be AES-256" }
    val key = CryptographyProvider.Default.get(AES.GCM)
        .keyDecoder()
        .decodeFromByteArray(AES.Key.Format.RAW, rawKey)
    return key.cipher().decrypt(ciphertext = wrappedKey, associatedData = KEY_WRAP_AAD)
}

private class Writer {
    private val output = ArrayList<Byte>()
    fun bytes(value: ByteArray) { value.forEach(output::add) }
    fun u8(value: Int) { require(value in 0..0xff); output += value.toByte() }
    fun u16(value: Int) {
        require(value in 0..0xffff)
        output += (value ushr 8).toByte()
        output += value.toByte()
    }
    fun i32(value: Int) {
        require(value >= 0)
        output += (value ushr 24).toByte()
        output += (value ushr 16).toByte()
        output += (value ushr 8).toByte()
        output += value.toByte()
    }
    fun toByteArray(): ByteArray = ByteArray(output.size) { output[it] }
}

private class Reader(private val input: ByteArray) {
    private var position = 0
    val remaining: Int get() = input.size - position
    fun u8(): Int = readBytes(1)[0].toInt() and 0xff
    fun u16(): Int = (u8() shl 8) or u8()
    fun i32(): Int {
        val value = (u8().toLong() shl 24) or
            (u8().toLong() shl 16) or
            (u8().toLong() shl 8) or
            u8().toLong()
        require(value <= Int.MAX_VALUE) { "Invalid SecureBlob length" }
        return value.toInt()
    }
    fun readBytes(length: Int): ByteArray {
        require(length >= 0 && length <= remaining) { "Truncated SecureBlob" }
        val result = input.copyOfRange(position, position + length)
        position += length
        return result
    }
}
