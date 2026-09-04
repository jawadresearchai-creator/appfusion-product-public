package com.appfusion.product.shared.vault

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.appfusion.product.shared.BackupRecord
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private class AndroidBackupRestoreKeyWrapper : DeviceKeyWrapper {
    override val keyId: String = "android-backup-restore-kek-v1"
    private val rawKey = ByteArray(32) { (it + 53).toByte() }

    override suspend fun wrap(clearKey: ByteArray): ByteArray = wrapWithRawAesKey(rawKey, clearKey)
    override suspend fun unwrap(wrappedKey: ByteArray): ByteArray = unwrapWithRawAesKey(rawKey, wrappedKey)
}

@RunWith(AndroidJUnit4::class)
class DocumentVaultBackupRestoreCoordinatorDeviceTest {
    @Test
    fun encryptedBackupRestoresAndSurvivesAndroidProcessStyleRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = System.nanoTime().toString()
        val sourceDatabaseName = "document-backup-source-$suffix.db"
        val targetDatabaseName = "document-backup-target-$suffix.db"
        val sourceBlobRoot = File(context.filesDir, "document-backup-source-blobs-$suffix")
        val targetBlobRoot = File(context.filesDir, "document-backup-target-blobs-$suffix")
        val service = SecureBlobService(AndroidBackupRestoreKeyWrapper())
        val allowAll = DocumentAccessPolicy { true }
        context.deleteDatabase(sourceDatabaseName)
        context.deleteDatabase(targetDatabaseName)

        try {
            val backups = createAndroidBackups(
                context,
                sourceDatabaseName,
                sourceBlobRoot,
                service,
                allowAll,
            )

            val targetDatabase = buildDocumentDatabase(documentDatabaseBuilder(context, targetDatabaseName))
            try {
                val metadataStore = RoomDocumentMetadataStore(targetDatabase.records())
                val targetSearch = DocumentSearchProjection("android-backup-restore", allowAll)
                val coordinator = DocumentVaultBackupRestoreCoordinator(
                    metadataStore = metadataStore,
                    blobStore = AndroidFileSecureBlobStore(targetBlobRoot),
                    secureBlobService = service,
                    searchProjection = targetSearch,
                )
                assertEquals(DocumentBackupRestoreDisposition.RESTORED, coordinator.restore(backups.first).disposition)
                assertEquals(DocumentBackupRestoreDisposition.RESTORED, coordinator.restore(backups.second).disposition)
            } finally {
                targetDatabase.close()
            }

            val restartedDatabase = buildDocumentDatabase(documentDatabaseBuilder(context, targetDatabaseName))
            try {
                val restartedStore = AndroidFileSecureBlobStore(targetBlobRoot)
                val restartedSearch = DocumentSearchProjection("android-backup-restarted", allowAll)
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
                assertArrayEquals(
                    "android restored payload".encodeToByteArray(),
                    requireNotNull(repository.read("restored-active", allowAll)).plaintext,
                )
            } finally {
                restartedDatabase.close()
            }
        } finally {
            context.deleteDatabase(sourceDatabaseName)
            context.deleteDatabase(targetDatabaseName)
            sourceBlobRoot.deleteRecursively()
            targetBlobRoot.deleteRecursively()
        }
    }

    private suspend fun createAndroidBackups(
        context: Context,
        databaseName: String,
        blobRoot: File,
        service: SecureBlobService,
        allowAll: DocumentAccessPolicy,
    ): Pair<BackupRecord, BackupRecord> {
        val sourceDatabase = buildDocumentDatabase(documentDatabaseBuilder(context, databaseName))
        return try {
            val metadataStore = RoomDocumentMetadataStore(sourceDatabase.records())
            val sourceSearch = DocumentSearchProjection("android-backup-source", allowAll)
            val repository = DocumentVaultRepository(
                metadataStore = metadataStore,
                blobStore = AndroidFileSecureBlobStore(blobRoot),
                secureBlobService = service,
                eventLog = InMemoryAppendOnlyActivityEventLog(),
                searchProjection = sourceSearch,
            )
            repository.create(
                "restored-active",
                "Passport",
                "identity",
                "application/pdf",
                "android restored payload".encodeToByteArray(),
                100,
            )
            repository.create(
                "restored-archived",
                "Old passport",
                "archive",
                "application/pdf",
                "android archived payload".encodeToByteArray(),
                200,
            )
            repository.archive("restored-archived", 300)
            requireNotNull(repository.exportBackupRecord("restored-active", allowAll)) to
                requireNotNull(repository.exportBackupRecord("restored-archived", allowAll))
        } finally {
            sourceDatabase.close()
        }
    }
}
