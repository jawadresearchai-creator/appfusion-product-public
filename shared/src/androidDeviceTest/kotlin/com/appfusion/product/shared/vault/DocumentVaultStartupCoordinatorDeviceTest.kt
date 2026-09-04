package com.appfusion.product.shared.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appfusion.product.shared.InMemoryAppendOnlyActivityEventLog
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.persistence.buildDocumentDatabase
import com.appfusion.product.shared.persistence.documentDatabaseBuilder
import com.appfusion.product.shared.security.DeviceKeyWrapper
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.security.unwrapWithRawAesKey
import com.appfusion.product.shared.security.wrapWithRawAesKey
import com.appfusion.product.shared.storage.AndroidFileSecureBlobStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private class AndroidStartupRestartKeyWrapper : DeviceKeyWrapper {
    override val keyId: String = "android-startup-restart-kek-v1"
    private val rawKey = ByteArray(32) { (it + 19).toByte() }

    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKey, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKey, wrappedKey)
}

@RunWith(AndroidJUnit4::class)
class DocumentVaultStartupCoordinatorDeviceTest {
    @Test
    fun roomAndFilesystemReferencesVerifyAfterProcessStyleRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val databaseName = "document-vault-startup-$suffix.db"
        val blobRoot = File(context.filesDir, "document-vault-startup-blobs-$suffix")
        context.deleteDatabase(databaseName)
        val keyWrapper = AndroidStartupRestartKeyWrapper()
        val service = SecureBlobService(keyWrapper)

        try {
            val firstDatabase = buildDocumentDatabase(documentDatabaseBuilder(context, databaseName))
            try {
                val metadataStore = RoomDocumentMetadataStore(firstDatabase.records())
                val search = DocumentSearchProjection("android-startup-before-restart", DocumentAccessPolicy { true })
                val repository = DocumentVaultRepository(
                    metadataStore = metadataStore,
                    blobStore = AndroidFileSecureBlobStore(blobRoot),
                    secureBlobService = service,
                    eventLog = InMemoryAppendOnlyActivityEventLog(),
                    searchProjection = search,
                )
                repository.create(
                    id = "active-document",
                    title = "Passport",
                    label = "identity",
                    contentType = "application/pdf",
                    plaintext = "android persisted passport".encodeToByteArray(),
                    occurredAtEpochMillis = 100,
                )
                repository.create(
                    id = "archived-document",
                    title = "Old passport",
                    label = "archive",
                    contentType = "application/pdf",
                    plaintext = "android archived passport".encodeToByteArray(),
                    occurredAtEpochMillis = 200,
                )
                repository.archive("archived-document", occurredAtEpochMillis = 300)
            } finally {
                firstDatabase.close()
            }

            val restartedDatabase = buildDocumentDatabase(documentDatabaseBuilder(context, databaseName))
            try {
                val restartedSearch = DocumentSearchProjection(
                    "android-startup-after-restart",
                    DocumentAccessPolicy { true },
                )
                val report = DocumentVaultStartupCoordinator(
                    metadataSource = RoomDocumentStartupMetadataSource(restartedDatabase.records()),
                    blobStore = AndroidFileSecureBlobStore(blobRoot),
                    secureBlobService = service,
                    searchProjection = restartedSearch,
                ).start()

                assertTrue(report.isClean)
                assertEquals(2, report.metadataRecords)
                assertEquals(2, report.referencedDocuments)
                assertEquals(2, report.recoverableReferences)
                assertEquals(1, report.verifiedActiveDocuments)
                assertEquals(1, report.verifiedArchivedDocuments)
                assertEquals(
                    listOf("active-document"),
                    restartedSearch.search(SearchQuery("passport")).map { it.ref.id },
                )
                assertTrue(restartedSearch.search(SearchQuery("old passport")).isEmpty())
            } finally {
                restartedDatabase.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
            blobRoot.deleteRecursively()
        }
    }
}
