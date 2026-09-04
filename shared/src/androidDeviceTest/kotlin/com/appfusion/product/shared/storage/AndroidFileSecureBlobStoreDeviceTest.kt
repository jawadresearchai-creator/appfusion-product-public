package com.appfusion.product.shared.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.appfusion.product.shared.EncryptedPayload
import com.appfusion.product.shared.SecureBlobMetadata
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class AndroidFileSecureBlobStoreDeviceTest {
    @Test
    fun atomicFilesRoundTripReplaceAndRecoverOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "secureblob-file-probe-${System.nanoTime()}")
        try {
            val store = AndroidFileSecureBlobStore(root)
            val keep = SecureBlobMetadata("keep:revision:1", 2, "application/pdf")
            val orphan = SecureBlobMetadata("orphan:revision:1", 2, "application/pdf")
            val first = EncryptedPayload(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
            val replacement = EncryptedPayload(byteArrayOf(9, 8, 7), byteArrayOf(6, 5))

            store.writeAtomic(keep, first)
            store.writeAtomic(keep, replacement)
            store.writeAtomic(orphan, first)
            val storedReplacement = store.read(keep.blobId)
            assertNotNull(storedReplacement)
            assertArrayEquals(replacement.ciphertext, storedReplacement!!.second.ciphertext)

            val temporaryName = SecureBlobFileNaming.temporaryFileName(
                SecureBlobFileNaming.fileName(keep.blobId),
            )
            File(root, temporaryName).writeBytes(byteArrayOf(1, 2, 3))
            val restarted = AndroidFileSecureBlobStore(root)
            val report = restarted.recover(setOf(keep.blobId))
            assertEquals(1, report.interruptedWritesRemoved)
            assertEquals(1, report.orphanBlobsRemoved)
            assertEquals(0, report.invalidBlobs)
            assertNotNull(restarted.read(keep.blobId))
            assertNull(restarted.read(orphan.blobId))
        } finally {
            root.deleteRecursively()
        }
    }
}
