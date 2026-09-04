@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.appfusion.product.shared.vault

import com.appfusion.product.shared.BackupRecord
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

private class IosBackupRestoreKeyWrapper : DeviceKeyWrapper {
    override val keyId: String = "ios-backup-restore-kek-v1"
    private val rawKey = ByteArray(32) { (it + 67).toByte() }

    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKey, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKey, wrappedKey)
}

class DocumentVaultBackupRestoreCoordinatorIosTest {
    @Test
    fun encryptedBackupRestoresAndSurvivesIosSimulatorProcessStyleRestart() = runTest {
        val suffix = NSUUID().UUIDString
        val sourceDatabasePath = NSTemporaryDirectory() + "/document-backup-source-$suffix.db"
        val targetDatabasePath = NSTemporaryDirectory() + "/document-backup-target-$suffix.db"
        val sourceBlobRoot = NSTemporaryDirectory() + "/document-backup-source-blobs-$suffix"
        val targetBlobRoot = NSTemporaryDirectory() + "/document-backup-target-blobs-$suffix"
        val service = SecureBlobService(IosBackupRestoreKeyWrapper())
        val allowAll = DocumentAccessPolicy { true }

        try {
            val backups = createIosBackups(sourceDatabasePath, sourceBlobRoot, service, allowAll)

            val targetDatabase = buildDocumentDatabase(documentDatabaseBuilder(targetDatabasePath))
            try {
                val metadataStore = RoomDocumentMetadataStore(targetDatabase.records())
                val targetSearch = DocumentSearchProjection("ios-backup-restore", allowAll)
                val coordinator = DocumentVaultBackupRestoreCoordinator(
                    metadataStore = metadataStore,
                    blobStore = AppleFileSecureBlobStore(targetBlobRoot),
                    secureBlobService = service,
                    searchProjection = targetSearch,
                )
                assertEquals(DocumentBackupRestoreDisposition.RESTORED, coordinator.restore(backups.first).disposition)
                assertEquals(DocumentBackupRestoreDisposition.RESTORED, coordinator.restore(backups.second).disposition)
            } finally {
                targetDatabase.close()
            }

            val restartedDatabase = buildDocumentDatabase(documentDatabaseBuilder(targetDatabasePath))
            try {
                val restartedStore = AppleFileSecureBlobStore(targetBlobRoot)
                val restartedSearch = DocumentSearchProjection("ios-backup-restarted", allowAll)
                val startup = DocumentVaultStartupCoordinator(
                    metadataSource = RoomDocumentStartupMetadataSource(restartedDatabase.records()),
                    blobStore = restartedStore,
                    secureBlobService = service,
                    searchProjection = restartedSearch,
                ).start()
                assertTrue(startup.isClean)
                assertEquals(2, startup.referencedDocuments)
                assertEquals(1, startup.verifiedActiveDocuments)
                assertEquals(1, startup.verifiedArchivedDocuments)
                assertEquals(listOf("restored-active"), restartedSearch.search(SearchQuery("passport")).map { it.ref.id })
                assertTrue(restartedSearch.search(SearchQuery("old passport")).isEmpty())

                val repository = DocumentVaultRepository(
                    metadataStore = RoomDocumentMetadataStore(restartedDatabase.records()),
                    blobStore = restartedStore,
                    secureBlobService = service,
                    eventLog = InMemoryAppendOnlyActivityEventLog(),
                    searchProjection = restartedSearch,
                )
                assertContentEquals(
                    "ios restored payload".encodeToByteArray(),
                    assertNotNull(repository.read("restored-active", allowAll)).plaintext,
                )
            } finally {
                restartedDatabase.close()
            }
        } finally {
            removePath(sourceDatabasePath)
            removePath(sourceDatabasePath + "-wal")
            removePath(sourceDatabasePath + "-shm")
            removePath(targetDatabasePath)
            removePath(targetDatabasePath + "-wal")
            removePath(targetDatabasePath + "-shm")
            removePath(sourceBlobRoot)
            removePath(targetBlobRoot)
        }
    }

    private suspend fun createIosBackups(
        databasePath: String,
        blobRoot: String,
        service: SecureBlobService,
        allowAll: DocumentAccessPolicy,
    ): Pair<BackupRecord, BackupRecord> {
        val sourceDatabase = buildDocumentDatabase(documentDatabaseBuilder(databasePath))
        return try {
            val metadataStore = RoomDocumentMetadataStore(sourceDatabase.records())
            val sourceSearch = DocumentSearchProjection("ios-backup-source", allowAll)
            val repository = DocumentVaultRepository(
                metadataStore = metadataStore,
                blobStore = AppleFileSecureBlobStore(blobRoot),
                secureBlobService = service,
                eventLog = InMemoryAppendOnlyActivityEventLog(),
                searchProjection = sourceSearch,
            )
            repository.create(
                "restored-active",
                "Passport",
                "identity",
                "application/pdf",
                "ios restored payload".encodeToByteArray(),
                100,
            )
            repository.create(
                "restored-archived",
                "Old passport",
                "archive",
                "application/pdf",
                "ios archived payload".encodeToByteArray(),
                200,
            )
            repository.archive("restored-archived", 300)
            assertNotNull(repository.exportBackupRecord("restored-active", allowAll)) to
                assertNotNull(repository.exportBackupRecord("restored-archived", allowAll))
        } finally {
            sourceDatabase.close()
        }
    }

    private fun removePath(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}
