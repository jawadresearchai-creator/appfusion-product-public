package com.appfusion.product.shared.storage

import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.SecureBlobMetadata
import com.appfusion.product.shared.SecureBlobStore

private const val FILE_FORMAT_VERSION = 1
private const val MAX_BLOB_ID_BYTES = 1024
private const val MAX_CONTENT_TYPE_BYTES = 4096
private const val MAX_CIPHERTEXT_BYTES = 128 * 1024 * 1024
private const val MAX_INTEGRITY_TAG_BYTES = 4096
private val FILE_MAGIC = byteArrayOf(0x41, 0x46, 0x42, 0x46) // AFBF

data class BlobRecoveryReport(
    val interruptedWritesRemoved: Int,
    val orphanBlobsRemoved: Int,
    val invalidBlobs: Int,
) {
    init {
        require(interruptedWritesRemoved >= 0)
        require(orphanBlobsRemoved >= 0)
        require(invalidBlobs >= 0)
    }
}

interface RecoverableSecureBlobStore : SecureBlobStore {
    fun recover(referencedBlobIds: Set<String>): BlobRecoveryReport
}

internal interface AtomicBlobFileBackend {
    fun ensureRoot()
    fun writeAtomically(targetFileName: String, bytes: ByteArray)
    fun read(fileName: String): ByteArray?
    fun delete(fileName: String): Boolean
    fun listFileNames(): List<String>
}

internal class AtomicFileSecureBlobStore(
    private val backend: AtomicBlobFileBackend,
) : RecoverableSecureBlobStore {
    private var startupInterruptedWrites = 0

    init {
        backend.ensureRoot()
        startupInterruptedWrites = removeInterruptedWrites()
    }

    override fun writeAtomic(metadata: SecureBlobMetadata, payload: EncryptedPayload) {
        val target = SecureBlobFileNaming.fileName(metadata.blobId)
        backend.writeAtomically(target, SecureBlobFileCodec.encode(metadata, payload))
    }

    override fun read(blobId: String): Pair<SecureBlobMetadata, EncryptedPayload>? {
        val target = SecureBlobFileNaming.fileName(blobId)
        val bytes = backend.read(target) ?: return null
        val decoded = SecureBlobFileCodec.decode(bytes)
        require(decoded.first.blobId == blobId) { "SecureBlob file identity mismatch" }
        require(SecureBlobFileNaming.fileName(decoded.first.blobId) == target) {
            "SecureBlob file name is not canonical"
        }
        return decoded
    }

    override fun delete(blobId: String): Boolean =
        backend.delete(SecureBlobFileNaming.fileName(blobId))

    override fun recover(referencedBlobIds: Set<String>): BlobRecoveryReport {
        val referencedFileNames = referencedBlobIds.mapTo(mutableSetOf()) {
            SecureBlobFileNaming.fileName(it)
        }
        var orphanBlobsRemoved = 0
        var invalidBlobs = 0
        backend.listFileNames()
            .filter(SecureBlobFileNaming::isBlobFileName)
            .forEach { fileName ->
                val bytes = backend.read(fileName)
                if (bytes == null) {
                    invalidBlobs += 1
                    return@forEach
                }
                val decoded = runCatching { SecureBlobFileCodec.decode(bytes) }.getOrNull()
                if (decoded == null || SecureBlobFileNaming.fileName(decoded.first.blobId) != fileName) {
                    invalidBlobs += 1
                } else if (fileName !in referencedFileNames && backend.delete(fileName)) {
                    orphanBlobsRemoved += 1
                }
            }
        val interrupted = startupInterruptedWrites + removeInterruptedWrites()
        startupInterruptedWrites = 0
        return BlobRecoveryReport(interrupted, orphanBlobsRemoved, invalidBlobs)
    }

    private fun removeInterruptedWrites(): Int = backend.listFileNames()
        .filter(SecureBlobFileNaming::isTemporaryFileName)
        .count { backend.delete(it) }
}

internal object SecureBlobFileNaming {
    private const val PREFIX = "b-"
    private const val SUFFIX = ".blob"
    private const val TEMP_SUFFIX = ".tmp"

    fun fileName(blobId: String): String {
        validateBlobId(blobId)
        val encoded = blobId.encodeToByteArray().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(radix = 16).padStart(2, '0')
        }
        return "$PREFIX$encoded$SUFFIX"
    }

    fun temporaryFileName(targetFileName: String): String {
        require(isBlobFileName(targetFileName)) { "Invalid SecureBlob target file name" }
        return targetFileName + TEMP_SUFFIX
    }

    fun isBlobFileName(fileName: String): Boolean =
        fileName.startsWith(PREFIX) && fileName.endsWith(SUFFIX) &&
            fileName.length > PREFIX.length + SUFFIX.length &&
            fileName.substring(PREFIX.length, fileName.length - SUFFIX.length).let { encoded ->
                encoded.length % 2 == 0 && encoded.all { it in '0'..'9' || it in 'a'..'f' }
            }

    fun isTemporaryFileName(fileName: String): Boolean =
        fileName.endsWith(TEMP_SUFFIX) && isBlobFileName(fileName.removeSuffix(TEMP_SUFFIX))

    fun requireSafeInternalFileName(fileName: String) {
        require(isBlobFileName(fileName) || isTemporaryFileName(fileName)) {
            "Unsafe SecureBlob internal file name"
        }
        require('/' !in fileName && '\\' !in fileName && ".." !in fileName) {
            "SecureBlob path traversal is forbidden"
        }
    }

    private fun validateBlobId(blobId: String) {
        require(blobId.isNotBlank()) { "Blob ID must not be blank" }
        val bytes = blobId.encodeToByteArray()
        require(bytes.size <= MAX_BLOB_ID_BYTES) { "Blob ID is too long" }
        require(blobId.none { it == '/' || it == '\\' || it == '\u0000' || it == '\n' || it == '\r' }) {
            "Blob ID contains a path or control character"
        }
        require(blobId != "." && blobId != "..") { "Blob ID must not be a path segment" }
    }
}

private object SecureBlobFileCodec {
    fun encode(metadata: SecureBlobMetadata, payload: EncryptedPayload): ByteArray = BlobFileWriter().apply {
        bytes(FILE_MAGIC)
        u8(FILE_FORMAT_VERSION)
        string(metadata.blobId, MAX_BLOB_ID_BYTES)
        i32(metadata.envelopeVersion)
        nullableString(metadata.contentType, MAX_CONTENT_TYPE_BYTES)
        byteArray(payload.ciphertext, MAX_CIPHERTEXT_BYTES)
        byteArray(payload.integrityTag, MAX_INTEGRITY_TAG_BYTES)
    }.toByteArray()

    fun decode(bytes: ByteArray): Pair<SecureBlobMetadata, EncryptedPayload> {
        val reader = BlobFileReader(bytes)
        require(reader.bytes(FILE_MAGIC.size).contentEquals(FILE_MAGIC)) { "Invalid SecureBlob file magic" }
        require(reader.u8() == FILE_FORMAT_VERSION) { "Unsupported SecureBlob file version" }
        val metadata = SecureBlobMetadata(
            blobId = reader.string(MAX_BLOB_ID_BYTES),
            envelopeVersion = reader.i32(),
            contentType = reader.nullableString(MAX_CONTENT_TYPE_BYTES),
        )
        val payload = EncryptedPayload(
            ciphertext = reader.byteArray(MAX_CIPHERTEXT_BYTES),
            integrityTag = reader.byteArray(MAX_INTEGRITY_TAG_BYTES),
        )
        require(reader.remaining == 0) { "Trailing SecureBlob file bytes" }
        return metadata to payload
    }
}

private class BlobFileWriter {
    private val output = ArrayList<Byte>()
    fun bytes(value: ByteArray) = value.forEach(output::add)
    fun u8(value: Int) { require(value in 0..0xff); output += value.toByte() }
    fun i32(value: Int) {
        require(value >= 0)
        for (shift in 24 downTo 0 step 8) output += (value ushr shift).toByte()
    }
    fun string(value: String, maximum: Int) {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= maximum) { "SecureBlob file string is too large" }
        i32(encoded.size)
        bytes(encoded)
    }
    fun nullableString(value: String?, maximum: Int) {
        if (value == null) {
            i32(0)
        } else {
            val encoded = value.encodeToByteArray()
            require(encoded.size <= maximum) { "SecureBlob file string is too large" }
            i32(encoded.size + 1)
            bytes(encoded)
        }
    }
    fun byteArray(value: ByteArray, maximum: Int) {
        require(value.size <= maximum) { "SecureBlob file field is too large" }
        i32(value.size)
        bytes(value)
    }
    fun toByteArray(): ByteArray = ByteArray(output.size) { output[it] }
}

private class BlobFileReader(private val input: ByteArray) {
    private var position = 0
    val remaining: Int get() = input.size - position
    fun u8(): Int = bytes(1)[0].toInt() and 0xff
    fun i32(): Int {
        val value = (u8().toLong() shl 24) or (u8().toLong() shl 16) or
            (u8().toLong() shl 8) or u8().toLong()
        require(value <= Int.MAX_VALUE) { "Invalid SecureBlob file length" }
        return value.toInt()
    }
    fun string(maximum: Int): String = canonicalString(i32(), maximum)
    fun nullableString(maximum: Int): String? {
        val encodedLength = i32()
        if (encodedLength == 0) return null
        return canonicalString(encodedLength - 1, maximum)
    }
    fun byteArray(maximum: Int): ByteArray {
        val length = i32()
        require(length <= maximum) { "SecureBlob file field is too large" }
        return bytes(length)
    }
    fun bytes(length: Int): ByteArray {
        require(length >= 0 && length <= remaining) { "Truncated SecureBlob file" }
        return input.copyOfRange(position, position + length).also { position += length }
    }
    private fun canonicalString(length: Int, maximum: Int): String {
        require(length in 0..maximum) { "Invalid SecureBlob file string length" }
        val encoded = bytes(length)
        val value = encoded.decodeToString(throwOnInvalidSequence = true)
        require(value.encodeToByteArray().contentEquals(encoded)) { "Non-canonical SecureBlob file string" }
        return value
    }
}
