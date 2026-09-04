package com.appfusion.product.shared.persistence

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Entity(tableName = "document_records")
data class DocumentRecordEntity(
    @PrimaryKey val id: String,
    val title: String,
    val label: String = "",
    val blobId: String = "",
    val contentType: String = "application/octet-stream",
    val revision: Long = 0L,
    val lifecycle: String = "ACTIVE",
    val updatedAtEpochMillis: Long = 0L,
)

@Dao
interface DocumentRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: DocumentRecordEntity)

    @Query("SELECT * FROM document_records WHERE id = :id LIMIT 1")
    suspend fun find(id: String): DocumentRecordEntity?

    @Query("SELECT * FROM document_records WHERE lifecycle = 'ACTIVE' ORDER BY id")
    suspend fun listActive(): List<DocumentRecordEntity>

    @Query("SELECT * FROM document_records ORDER BY id")
    suspend fun listAll(): List<DocumentRecordEntity>

    @Query("DELETE FROM document_records WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Database(
    entities = [DocumentRecordEntity::class],
    version = 3,
    exportSchema = true,
)
@ConstructedBy(DocumentDomainDatabaseConstructor::class)
abstract class DocumentDomainDatabase : RoomDatabase() {
    abstract fun records(): DocumentRecordDao
}

@Suppress("KotlinNoActualForExpect")
expect object DocumentDomainDatabaseConstructor : RoomDatabaseConstructor<DocumentDomainDatabase> {
    override fun initialize(): DocumentDomainDatabase
}

@Entity(tableName = "activity_records")
data class ActivityRecordEntity(
    @PrimaryKey val id: String,
    val title: String,
    val completedCount: Long,
)

@Dao
interface ActivityRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: ActivityRecordEntity)

    @Query("SELECT * FROM activity_records WHERE id = :id LIMIT 1")
    suspend fun find(id: String): ActivityRecordEntity?
}

@Database(
    entities = [ActivityRecordEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(ActivityDomainDatabaseConstructor::class)
abstract class ActivityDomainDatabase : RoomDatabase() {
    abstract fun records(): ActivityRecordDao
}

@Suppress("KotlinNoActualForExpect")
expect object ActivityDomainDatabaseConstructor : RoomDatabaseConstructor<ActivityDomainDatabase> {
    override fun initialize(): ActivityDomainDatabase
}

val DocumentMigration1To2 = Migration(1, 2) { connection ->
    connection.execSQL(
        "ALTER TABLE document_records ADD COLUMN label TEXT NOT NULL DEFAULT ''",
    )
}

val DocumentMigration2To3 = Migration(2, 3) { connection ->
    connection.execSQL("ALTER TABLE document_records ADD COLUMN blobId TEXT NOT NULL DEFAULT ''")
    connection.execSQL(
        "ALTER TABLE document_records ADD COLUMN contentType TEXT NOT NULL DEFAULT 'application/octet-stream'",
    )
    connection.execSQL("ALTER TABLE document_records ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
    connection.execSQL("ALTER TABLE document_records ADD COLUMN lifecycle TEXT NOT NULL DEFAULT 'ACTIVE'")
    connection.execSQL("ALTER TABLE document_records ADD COLUMN updatedAtEpochMillis INTEGER NOT NULL DEFAULT 0")
    connection.execSQL("UPDATE document_records SET blobId = 'legacy:' || id")
    connection.execSQL("UPDATE document_records SET revision = 1")
    connection.execSQL("UPDATE document_records SET lifecycle = 'LEGACY_MIGRATION_REQUIRED'")
}

val FailingDocumentMigration1To2 = Migration(1, 2) { connection ->
    connection.execSQL(
        "ALTER TABLE document_records ADD COLUMN label TEXT NOT NULL DEFAULT ''",
    )
    error("intentional persistence-probe migration failure")
}

val FailingDocumentMigration2To3 = Migration(2, 3) { connection ->
    connection.execSQL("ALTER TABLE document_records ADD COLUMN blobId TEXT NOT NULL DEFAULT ''")
    error("intentional document-vault migration failure")
}

fun buildDocumentDatabase(
    builder: RoomDatabase.Builder<DocumentDomainDatabase>,
    migrations: List<Migration> = listOf(DocumentMigration1To2, DocumentMigration2To3),
): DocumentDomainDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .addMigrations(*migrations.toTypedArray())
    .build()

fun buildActivityDatabase(
    builder: RoomDatabase.Builder<ActivityDomainDatabase>,
): ActivityDomainDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .build()
