package com.appfusion.product

import android.content.Context
import com.appfusion.product.shared.InMemoryAppendOnlyActivityEventLog
import com.appfusion.product.shared.SearchQuery
import com.appfusion.product.shared.persistence.buildDocumentDatabase
import com.appfusion.product.shared.persistence.documentDatabaseBuilder
import com.appfusion.product.shared.security.AndroidKeystoreKeyWrapper
import com.appfusion.product.shared.security.SecureBlobService
import com.appfusion.product.shared.storage.AndroidFileSecureBlobStore
import com.appfusion.product.shared.vault.DocumentAccessPolicy
import com.appfusion.product.shared.vault.DocumentSearchProjection
import com.appfusion.product.shared.vault.DocumentVaultRepository
import com.appfusion.product.shared.vault.DocumentVaultStartupCoordinator
import com.appfusion.product.shared.vault.DocumentVaultStartupReport
import com.appfusion.product.shared.vault.RoomDocumentMetadataStore
import com.appfusion.product.shared.vault.RoomDocumentStartupMetadataSource
import java.io.File

data class DocumentListItem(
    val id: String,
    val title: String,
    val label: String,
)

class AndroidDocumentRuntime(context: Context) {
    private val database = buildDocumentDatabase(
        documentDatabaseBuilder(context, "appfusion-documents.db"),
    )
    private val accessPolicy = DocumentAccessPolicy { true }
    private val search = DocumentSearchProjection("document-vault", accessPolicy)
    private val blobStore = AndroidFileSecureBlobStore(File(context.filesDir, "secure-blobs"))
    private val secureBlobService = SecureBlobService(
        AndroidKeystoreKeyWrapper(alias = "appfusion-document-kek-v1"),
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

    suspend fun start(): DocumentVaultStartupReport = startup.start()

    suspend fun createDocument(id: String, title: String, body: String): DocumentListItem {
        val metadata = repository.create(
            id = id,
            title = title,
            label = "Encrypted note",
            contentType = "text/plain; charset=utf-8",
            plaintext = body.encodeToByteArray(),
            occurredAtEpochMillis = System.currentTimeMillis(),
        )
        return DocumentListItem(metadata.id, metadata.title, metadata.label)
    }

    fun searchDocuments(query: String): List<DocumentListItem> =
        search.search(SearchQuery(query)).map {
            DocumentListItem(it.ref.id, it.title, it.snippet.orEmpty())
        }

    suspend fun readDocument(id: String): String? = repository
        .read(id, accessPolicy)
        ?.plaintext
        ?.decodeToString()
}
