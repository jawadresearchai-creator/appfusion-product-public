@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.appfusion.product.shared.storage

import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.SecureBlobMetadata
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

class AppleFileSecureBlobStoreSimulatorTest {
    @Test
    fun atomicFilesRoundTripReplaceAndRecoverOnSimulator() {
        val root = NSTemporaryDirectory() + "/secureblob-file-probe-" + NSUUID().UUIDString
        try {
            val store = AppleFileSecureBlobStore(root)
            val keep = SecureBlobMetadata("keep:revision:1", 2, "application/pdf")
            val orphan = SecureBlobMetadata("orphan:revision:1", 2, "application/pdf")
            val first = EncryptedPayload(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
            val replacement = EncryptedPayload(byteArrayOf(9, 8, 7), byteArrayOf(6, 5))

            store.writeAtomic(keep, first)
            store.writeAtomic(keep, replacement)
            store.writeAtomic(orphan, first)
            assertContentEquals(replacement.ciphertext, assertNotNull(store.read(keep.blobId)).second.ciphertext)

            val report = store.recover(setOf(keep.blobId))
            assertEquals(0, report.interruptedWritesRemoved)
            assertEquals(1, report.orphanBlobsRemoved)
            assertEquals(0, report.invalidBlobs)
            assertNotNull(store.read(keep.blobId))
            assertNull(store.read(orphan.blobId))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, error = null)
        }
    }
}
