package com.appfusion.product.shared.persistence

import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test

class PersistenceIosTest {
    private fun databasePath(prefix: String): String =
        NSTemporaryDirectory() + "/" + prefix + "-" + NSUUID().UUIDString + ".db"

    @Test
    fun domainStoresRoundTripIndependently() = runTest {
        val documentPath = databasePath("documents")
        val activityPath = databasePath("activities")
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
        val path = databasePath("migration")
        seedLegacyDocumentV1(path)
        assertSuccessfulMigration(path)
    }

    @Test
    fun documentVaultLifecycleComposesRoomAndSecureBlob() = runTest {
        val database = buildDocumentDatabase(documentDatabaseBuilder(databasePath("vault")))
        try {
            assertDocumentVaultLifecycle(database)
        } finally {
            database.close()
        }
    }

    @Test
    fun failingMigrationRollsBackAtomically() = runTest {
        val path = databasePath("rollback")
        seedLegacyDocumentV1(path)
        assertFailingMigrationRollsBack(path)
    }

    @Test
    fun failingVaultMigrationRollsBackAtomically() = runTest {
        val path = databasePath("vault-rollback")
        seedLegacyDocumentV2(path)
        assertFailingVaultMigrationRollsBack(path)
    }
}
