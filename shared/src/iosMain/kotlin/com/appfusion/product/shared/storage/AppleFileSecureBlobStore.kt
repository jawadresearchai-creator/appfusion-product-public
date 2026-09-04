@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.appfusion.product.shared.storage

import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.SecureBlobMetadata
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.posix.ENOENT
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.access
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fsync
import platform.posix.lseek
import platform.posix.open
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rename
import platform.posix.unlink

class AppleFileSecureBlobStore(rootDirectoryPath: String) : RecoverableSecureBlobStore {
    private val delegate = AtomicFileSecureBlobStore(AppleAtomicBlobFileBackend(rootDirectoryPath))

    override fun writeAtomic(metadata: SecureBlobMetadata, payload: EncryptedPayload) =
        delegate.writeAtomic(metadata, payload)

    override fun read(blobId: String): Pair<SecureBlobMetadata, EncryptedPayload>? = delegate.read(blobId)
    override fun delete(blobId: String): Boolean = delegate.delete(blobId)
    override fun recover(referencedBlobIds: Set<String>): BlobRecoveryReport = delegate.recover(referencedBlobIds)
}

private class AppleAtomicBlobFileBackend(
    rootDirectoryPath: String,
) : AtomicBlobFileBackend {
    private val root = rootDirectoryPath.trimEnd('/')

    init {
        require(root.startsWith('/') && root.length > 1) { "SecureBlob root must be an absolute non-root path" }
    }

    override fun ensureRoot() {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(root)) {
            require(
                manager.createDirectoryAtPath(
                    root,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                ),
            ) { "Unable to create SecureBlob directory" }
        }
        val directory = opendir(root) ?: error("SecureBlob root is not a readable directory: errno=$errno")
        closedir(directory)
    }

    override fun writeAtomically(targetFileName: String, bytes: ByteArray) {
        val targetPath = safePath(targetFileName)
        val temporaryPath = safePath(SecureBlobFileNaming.temporaryFileName(targetFileName))
        if (unlink(temporaryPath) != 0 && errno != ENOENT) {
            error("Unable to remove stale SecureBlob temporary file: errno=$errno")
        }

        val descriptor = open(temporaryPath, O_WRONLY or O_CREAT or O_TRUNC, 384)
        require(descriptor >= 0) { "Unable to open SecureBlob temporary file: errno=$errno" }
        try {
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val written = platform.posix.write(
                        descriptor,
                        pinned.addressOf(offset),
                        (bytes.size - offset).convert(),
                    )
                    require(written > 0) { "Unable to write SecureBlob temporary file: errno=$errno" }
                    offset += written.toInt()
                }
            }
            require(fsync(descriptor) == 0) { "Unable to flush SecureBlob temporary file: errno=$errno" }
        } finally {
            close(descriptor)
        }

        require(rename(temporaryPath, targetPath) == 0) {
            "Unable to atomically replace SecureBlob file: errno=$errno"
        }
        syncDirectoryWhereSupported()
    }

    override fun read(fileName: String): ByteArray? {
        val path = safePath(fileName)
        val descriptor = open(path, O_RDONLY)
        if (descriptor < 0 && errno == ENOENT) return null
        require(descriptor >= 0) { "Unable to open SecureBlob file: errno=$errno" }
        try {
            val size = lseek(descriptor, 0, SEEK_END)
            require(size >= 0L && size <= 129L * 1024L * 1024L) { "Invalid SecureBlob file size" }
            require(lseek(descriptor, 0, SEEK_SET) == 0L) { "Unable to seek SecureBlob file: errno=$errno" }
            val result = ByteArray(size.toInt())
            if (result.isNotEmpty()) {
                result.usePinned { pinned ->
                    var offset = 0
                    while (offset < result.size) {
                        val count = platform.posix.read(
                            descriptor,
                            pinned.addressOf(offset),
                            (result.size - offset).convert(),
                        )
                        require(count > 0) { "Truncated SecureBlob file: errno=$errno" }
                        offset += count.toInt()
                    }
                }
            }
            return result
        } finally {
            close(descriptor)
        }
    }

    override fun delete(fileName: String): Boolean {
        val path = safePath(fileName)
        if (access(path, F_OK) != 0) return false
        require(unlink(path) == 0) { "Unable to delete SecureBlob file: errno=$errno" }
        syncDirectoryWhereSupported()
        return true
    }

    override fun listFileNames(): List<String> {
        val directory = opendir(root) ?: error("Unable to list SecureBlob directory: errno=$errno")
        val names = mutableListOf<String>()
        try {
            while (true) {
                val entry = readdir(directory) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") names += name
            }
        } finally {
            closedir(directory)
        }
        return names.sorted()
    }

    private fun safePath(fileName: String): String {
        SecureBlobFileNaming.requireSafeInternalFileName(fileName)
        return "$root/$fileName"
    }

    private fun syncDirectoryWhereSupported() {
        val descriptor = open(root, O_RDONLY)
        if (descriptor < 0) return
        try {
            // Darwin may reject directory fsync on some filesystems; the file itself
            // has already been flushed before the same-directory atomic rename.
            fsync(descriptor)
        } finally {
            close(descriptor)
        }
    }
}
