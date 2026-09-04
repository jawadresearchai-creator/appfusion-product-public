package com.appfusion.product.shared.activity

import com.appfusion.product.shared.persistence.activityDatabaseBuilder
import com.appfusion.product.shared.persistence.buildActivityDatabase
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test

class ActivityCadenceIosTest {
    private fun path() = NSTemporaryDirectory() + "/appfusion-activity-" + NSUUID().UUIDString + ".db"

    @Test fun persistentJourneyAndReconciliation() = runTest {
        val path = path()
        assertActivityJourneySurvivesRestart { buildActivityDatabase(activityDatabaseBuilder(path)) }
    }
    @Test fun concurrentCompletionIdempotency() = runTest {
        val path = path()
        assertConcurrentActivityCompletions { buildActivityDatabase(activityDatabaseBuilder(path)) }
    }
    @Test fun versionOneMigrationPreservesExistingActivity() = runTest {
        val path = path()
        seedActivityV1(path)
        assertLegacyActivityMigration { buildActivityDatabase(activityDatabaseBuilder(path)) }
    }
    @Test fun failedMigrationPreservesVersionOne() = runTest {
        val path = path()
        seedActivityV1(path)
        assertActivityMigrationRollback(path) { buildActivityDatabase(activityDatabaseBuilder(path), listOf(FailingActivityMigration1To2)) }
    }
    @Test fun historyCountAndCadenceCommitAtomically() = runTest {
        val path = path()
        assertCompletionTransactionRollback(path) { buildActivityDatabase(activityDatabaseBuilder(path)) }
    }
}
