@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.appfusion.product.shared.vault

import com.appfusion.product.shared.InMemoryAppendOnlyActivityEventLog
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.persistence.buildDocumentDatabase
import com.appfusion.product.shared.persistence.documentDatabaseBuilder
import com.appfusion.product.shared.security.DeviceKeyWrapper
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.security.unwrapWithRawAesKey
import com.appfusion.product.shared.security.wrapWithRawAesKey
import com.appfusion.product.shared.storage.AppleFileSecureBlobStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

private class AppleStartupRestartKeyWrapper : DeviceKeyWrapper {
    override val keyId: String = "apple-startup-restart-kek-v1"
    private val rawKey = ByteArray(32) { (it + 43).toByte() }

    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKey, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKey, wrappedKey)
}

class DocumentVaultStartupCoordinatorIosTest {
    @Test
    fun roomAndFilesystemReferencesVerifyAfterProcessStyleRestart() = runTest {
        val suffix = NSUUID().UUIDString
        val databasePath = NSTemporaryDirectory() + "/document-vault-startup-$suffix.db"
        val blobRoot = NSTemporaryDirectory() + "/document-vault-startup-blobs-$suffix"
        val service = SecureBlobService(AppleStartupRestartKeyWrapper())

        try {
            val firstDatabase = buildDocumentDatabase(documentDatabaseBuilder(databasePath))
            try {
                val metadataStore = RoomDocumentMetadataStore(firstDatabase.records())
                val search = DocumentSearchProjection("ios-startup-before-restart", DocumentAccessPolicy { true })
                val repository = DocumentVaultRepository(
                    metadataStore = metadataStore,
                    blobStore = AppleFileSecureBlobStore(blobRoot),
                    secureBlobService = service,
                    eventLog = InMemoryAppendOnlyActivityEventLog(),
                    searchProjection = search,
                )
                repository.create(
                    id = "active-document",
                    title = "Passport",
                    label = "identity",
                    contentType = "application/pdf",
                    plaintext = "ios persisted passport".encodeToByteArray(),
                    occurredAtEpochMillis = 100,
                )
                repository.create(
                    id = "archived-document",
                    title = "Old passport",
                    label = "archive",
                    contentType = "application/pdf",
                    plaintext = "ios archived passport".encodeToByteArray(),
                    occurredAtEpochMillis = 200,
                )
                repository.archive("archived-document", occurredAtEpochMillis = 300)
            } finally {
                firstDatabase.close()
            }

            val restartedDatabase = buildDocumentDatabase(documentDatabaseBuilder(databasePath))
            try {
                val restartedSearch = DocumentSearchProjection(
                    "ios-startup-after-restart",
                    DocumentAccessPolicy { true },
                )
                val report = DocumentVaultStartupCoordinator(
                    metadataSource = RoomDocumentStartupMetadataSource(restartedDatabase.records()),
                    blobStore = AppleFileSecureBlobStore(blobRoot),
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
            val manager = NSFileManager.defaultManager
            manager.removeItemAtPath(blobRoot, error = null)
            manager.removeItemAtPath(databasePath, error = null)
            manager.removeItemAtPath(databasePath + "-wal", error = null)
            manager.removeItemAtPath(databasePath + "-shm", error = null)
        }
    }
}
