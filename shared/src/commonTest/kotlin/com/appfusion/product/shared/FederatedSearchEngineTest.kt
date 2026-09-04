package com.appfusion.product.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FederatedSearchEngineTest {
    private class StubProvider(
        override val providerId: String,
        private val results: List<SearchResult>,
    ) : SearchProvider {
        override fun search(query: SearchQuery): List<SearchResult> = results
    }

    @Test
    fun federatesDeduplicatesNormalizesAndOrdersDeterministically() {
        val doc = EntityRef(EntityDomain.DOCUMENT, "doc-1")
        val activity = EntityRef(EntityDomain.ACTIVITY, "activity-1")
        val engine = FederatedSearchEngine(
            listOf(
                StubProvider(
                    "documents",
                    listOf(
                        SearchResult(doc, "Passport", "older", 0.60),
                        SearchResult(activity, "Renew passport", null, 1.25),
                    ),
                ),
                StubProvider(
                    "activities",
                    listOf(
                        SearchResult(doc, "Passport", "better", 0.90),
                    ),
                ),
            ),
        )

        val results = engine.search(SearchQuery("passport"))

        assertEquals(2, results.size)
        assertEquals(activity, results[0].ref)
        assertEquals(1.0, results[0].score)
        assertEquals(doc, results[1].ref)
        assertEquals("better", results[1].snippet)
    }

    @Test
    fun respectsLimitAndBlankQueryIsLocalNoOp() {
        val engine = FederatedSearchEngine(
            listOf(
                StubProvider(
                    "one",
                    listOf(
                        SearchResult(EntityRef(EntityDomain.THING, "2"), "Beta", score = 0.5),
                        SearchResult(EntityRef(EntityDomain.THING, "1"), "Alpha", score = 0.5),
                    ),
                ),
            ),
        )

        val limited = engine.search(SearchQuery("a", limit = 1))
        assertEquals(listOf("Alpha"), limited.map { it.title })
        assertTrue(engine.search(SearchQuery("   ")).isEmpty())
    }
}
