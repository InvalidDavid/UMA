package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

class AllHentaiContractTest {

    @Test
    fun `source supports current tags details and pages markup`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://x.ahen.me/search/advancedResults?q=Club&offset=0&years=1900%2C2099&sortType=DATE_CREATE",
                "/fixtures/allhentai/search.html",
            )
            .get("https://x.ahen.me/example", "/fixtures/allhentai/details.html")
            .get("https://x.ahen.me/example/vol1/1?mtr=1", "/fixtures/allhentai/pages.html")
            .build()
        val parser = MangaParserSource.ALLHENTAI.newParser(context)

        val manga = parser.getList(
            offset = 0,
            order = SortOrder.NEWEST,
            filter = MangaListFilter.EMPTY.copy(query = "Club"),
        ).single()
        val details = parser.getDetails(manga)

        assertEquals(setOf("Drama"), details.tags.mapTo(linkedSetOf()) { it.title })
        val chapter = details.chapters.orEmpty().single()
        val pages = parser.getPages(chapter)
        assertFalse(pages.isEmpty())
        assertEquals("https://images.allhentai.test/page-01.jpg", parser.getPageUrl(pages.single()))
        context.verifyAllFixturesUsed()
    }
}
