package com.appfusion.product.shared.persistence

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PersistenceJvmTest {
    @Test
    fun domainStoresRoundTripIndependently() = runTest {
        val root = Files.createTempDirectory("appfusion-persistence-jvm")
        val documentPath = root.resolve("documents.db").toString()
        val activityPath = root.resolve("activities.db").toString()
        val documentDb = buildDocumentDatabase(documentDatabaseBuilder(documentPath))
        val activityDb = buildActivityDatabase(activityDatabaseBuilder(activityPath))
        try {
            assertDomainSeparatedRoundTrip(documentDb, activityDb)
        } finally {
            documentDb.close()
            activityDb.close()
        }
    }

    @Test
    fun versionOneDocumentStoreMigratesToCurrentVersion() = runTest {
        val root = Files.createTempDirectory("appfusion-migration-jvm")
        val path = root.resolve("documents.db").toString()
        seedLegacyDocumentV1(path)
        assertSuccessfulMigration(path)
    }

    @Test
    fun documentVaultLifecycleComposesRoomAndSecureBlob() = runTest {
        val root = Files.createTempDirectory("appfusion-vault-jvm")
        val database = buildDocumentDatabase(documentDatabaseBuilder(root.resolve("documents.db").toString()))
        try {
            assertDocumentVaultLifecycle(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun failingMigrationRollsBackAtomically() = runTest {
        val root = Files.createTempDirectory("appfusion-rollback-jvm")
        val path = root.resolve("documents.db").toString()
        seedLegacyDocumentV1(path)
        assertFailingMigrationRollsBack(path)
    }

    @Test
    fun failingVaultMigrationRollsBackAtomically() = runTest {
        val root = Files.createTempDirectory("appfusion-vault-rollback-jvm")
        val path = root.resolve("documents.db").toString()
        seedLegacyDocumentV2(path)
        assertFailingVaultMigrationRollsBack(path)
    }
}
