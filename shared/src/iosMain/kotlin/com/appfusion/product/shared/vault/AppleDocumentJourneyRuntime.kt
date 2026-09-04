package com.appfusion.product.shared.vault

import com.appfusion.product.shared.InMemoryAppendOnlyActivityEventLog
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.persistence.buildDocumentDatabase
import com.appfusion.product.shared.persistence.documentDatabaseBuilder
import com.appfusion.product.shared.security.AppleKeychainKeyWrapper
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.storage.AppleFileSecureBlobStore
import kotlinx.coroutines.runBlocking

/**
 * Small iOS-facing facade for Journey J1.
 *
 * Product storage/encryption/search behavior remains owned by the shared vault stack;
 * this facade only adapts scalar values into Swift-friendly synchronous calls.
 */
class AppleDocumentJourneyRuntime(rootDirectoryPath: String) {
    private val database = buildDocumentDatabase(
        documentDatabaseBuilder("$rootDirectoryPath/appfusion-documents.db"),
    )
    private val accessPolicy = DocumentAccessPolicy { true }
    private val search = DocumentSearchProjection("document-vault", accessPolicy)
    private val blobStore = AppleFileSecureBlobStore("$rootDirectoryPath/secure-blobs")
    private val secureBlobService = SecureBlobService(
        AppleKeychainKeyWrapper(
            service = "com.appfusion.product.document-vault",
            account = "document-kek-v1",
            keyId = "apple-keychain-document-kek-v1",
        ),
    )
    private val repository = DocumentVaultRepository(
        metadataStore = RoomDocumentMetadataStore(database.records()),
        blobStore = blobStore,
        secureBlobService = secureBlobService,
        eventLog = InMemoryAppendOnlyActivityEventLog(),
        searchProjection = search,
    )
    private val startup = DocumentVaultStartupCoordinator(
        metadataSource = RoomDocumentStartupMetadataSource(database.records()),
        blobStore = blobStore,
        secureBlobService = secureBlobService,
        searchProjection = search,
    )

    fun startVault(): String = runCatching {
        runBlocking {
            val report = startup.start()
            if (report.isClean) {
                "OK:${report.verifiedActiveDocuments}"
            } else {
                "ERROR:startup:${report.issues.size}:${report.recovery.invalidBlobs}"
            }
        }
    }.getOrElse { "ERROR:startup:${safeMessage(it)}" }

    fun createDocument(
        id: String,
        title: String,
        body: String,
        occurredAtEpochMillis: Long,
    ): String = runCatching {
        require(id.isNotBlank()) { "Document ID is required" }
        require(title.isNotBlank()) { "Document title is required" }
        require(body.isNotBlank()) { "Document body is required" }
        runBlocking {
            repository.create(
                id = id,
                title = title,
                label = "Encrypted note",
                contentType = "text/plain; charset=utf-8",
                plaintext = body.encodeToByteArray(),
                occurredAtEpochMillis = occurredAtEpochMillis,
            ).id
        }.let { "OK:$it" }
    }.getOrElse { "ERROR:create:${safeMessage(it)}" }

    fun searchFirstDocumentId(query: String): String? = runCatching {
        search.search(SearchQuery(query)).firstOrNull()?.ref?.id
    }.getOrNull()

    fun searchFirstDocumentTitle(query: String): String? = runCatching {
        search.search(SearchQuery(query)).firstOrNull()?.title
    }.getOrNull()

    fun readDocument(id: String): String? = runCatching {
        runBlocking {
            repository.read(id, accessPolicy)?.plaintext?.decodeToString()
        }
    }.getOrNull()

    fun closeVault() {
        runCatching { database.close() }
    }

    private fun safeMessage(error: Throwable): String =
        error.message.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(160)
            .ifBlank { error::class.simpleName ?: "failure" }
}
