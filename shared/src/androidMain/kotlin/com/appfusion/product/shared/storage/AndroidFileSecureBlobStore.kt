package com.appfusion.product.shared.storage

import android.system.Os
import android.system.OsConstants
import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.SecureBlobMetadata
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AndroidFileSecureBlobStore(rootDirectory: File) : RecoverableSecureBlobStore {
    private val delegate = AtomicFileSecureBlobStore(AndroidAtomicBlobFileBackend(rootDirectory))

    override fun writeAtomic(metadata: SecureBlobMetadata, payload: EncryptedPayload) =
        delegate.writeAtomic(metadata, payload)

    override fun read(blobId: String): Pair<SecureBlobMetadata, EncryptedPayload>? = delegate.read(blobId)
    override fun delete(blobId: String): Boolean = delegate.delete(blobId)
    override fun recover(referencedBlobIds: Set<String>): BlobRecoveryReport = delegate.recover(referencedBlobIds)
}

private class AndroidAtomicBlobFileBackend(
    rootDirectory: File,
) : AtomicBlobFileBackend {
    private val root = rootDirectory.absoluteFile.toPath().normalize().toFile()

    override fun ensureRoot() {
        require(root.exists() || root.mkdirs()) { "Unable to create SecureBlob directory" }
        require(root.isDirectory) { "SecureBlob root is not a directory" }
    }

    override fun writeAtomically(targetFileName: String, bytes: ByteArray) {
        val target = safeFile(targetFileName)
        val temporary = safeFile(SecureBlobFileNaming.temporaryFileName(targetFileName))
        if (temporary.exists() && !temporary.delete()) error("Unable to remove stale SecureBlob temporary file")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory()
        } catch (failure: Throwable) {
            // A complete temporary file is intentionally left for startup recovery.
            throw failure
        }
    }

    override fun read(fileName: String): ByteArray? {
        val file = safeFile(fileName)
        if (!file.exists()) return null
        require(file.isFile) { "SecureBlob path is not a regular file" }
        require(file.length() <= 129L * 1024L * 1024L) { "SecureBlob file is too large" }
        return file.readBytes()
    }

    override fun delete(fileName: String): Boolean {
        val file = safeFile(fileName)
        if (!file.exists()) return false
        require(file.isFile) { "SecureBlob path is not a regular file" }
        val removed = file.delete()
        if (removed) syncDirectory()
        return removed
    }

    override fun listFileNames(): List<String> = root.listFiles()
        ?.filter { it.isFile }
        ?.map { it.name }
        ?.sorted()
        ?: emptyList()

    private fun safeFile(fileName: String): File {
        SecureBlobFileNaming.requireSafeInternalFileName(fileName)
        val candidate = File(root, fileName).absoluteFile.toPath().normalize().toFile()
        require(candidate.parentFile == root) { "SecureBlob path escaped its root" }
        return candidate
    }

    private fun syncDirectory() {
        val descriptor = Os.open(
            root.path,
            OsConstants.O_RDONLY,
            0,
        )
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
}
