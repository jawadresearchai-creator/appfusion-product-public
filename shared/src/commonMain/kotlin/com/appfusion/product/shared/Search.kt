package com.appfusion.product.shared

data class SearchQuery(
    val text: String,
    val limit: Int = 50,
) {
    init {
        require(limit in 1..500) { "Search limit must be between 1 and 500" }
    }
}

data class SearchResult(
    val ref: EntityRef,
    val title: String,
    val snippet: String? = null,
    val score: Double,
    val action: String? = null,
) {
    init {
        require(title.isNotBlank()) { "Search title must not be blank" }
        require(score.isFinite()) { "Search score must be finite" }
    }
}

interface SearchProvider {
    val providerId: String
    fun search(query: SearchQuery): List<SearchResult>
}

class FederatedSearchEngine(
    providers: List<SearchProvider>,
) {
    private val providers = providers.toList()

    init {
        require(this.providers.map { it.providerId }.all { it.isNotBlank() }) {
            "Search provider IDs must not be blank"
        }
        require(this.providers.map { it.providerId }.distinct().size == this.providers.size) {
            "Search provider IDs must be unique"
        }
    }

    fun search(query: SearchQuery): List<SearchResult> {
        if (query.text.isBlank()) return emptyList()

        val bestByEntity = mutableMapOf<EntityRef, SearchResult>()
        providers.forEach { provider ->
            provider.search(query).forEach { candidate ->
                val normalized = candidate.copy(score = candidate.score.coerceIn(0.0, 1.0))
                val current = bestByEntity[normalized.ref]
                if (current == null || resultComparator.compare(normalized, current) < 0) {
                    bestByEntity[normalized.ref] = normalized
                }
            }
        }

        return bestByEntity.values
            .sortedWith(resultComparator)
            .take(query.limit)
    }

    private companion object {
        val resultComparator = compareByDescending<SearchResult> { it.score }
            .thenBy { it.title.lowercase() }
            .thenBy { it.ref.domain.name }
            .thenBy { it.ref.id }
    }
}
