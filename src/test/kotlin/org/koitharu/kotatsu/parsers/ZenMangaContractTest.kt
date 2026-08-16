package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

class ZenMangaContractTest {

    @Test
    fun `uses the current InkStory domain`() {
        val parser = MangaParserSource.ZENMANGA.newParser(FixtureMangaLoaderContext.builder().build())

        assertEquals("inkstory.net", parser.domain)
    }

    @Test
    fun `reads catalog details chapters and pages from InkStory contracts`() = runBlocking {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://api.inkstory.net/v2/books?page=0&size=30&sort=viewsCount%2Cdesc",
                "/fixtures/zenmanga/catalog.json",
            )
            .get("https://inkstory.net/content/example", "/fixtures/zenmanga/details.html")
            .get("https://inkstory.net/content/example/chapter-1", "/fixtures/zenmanga/pages.html")
            .build()
        val parser = MangaParserSource.ZENMANGA.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.ZENMANGA,
            expectedTitle = "Example Manga",
            sortOrder = SortOrder.POPULARITY,
            expectedPageUrl = "https://cdn.inkstory.test/example-001.jpg?format=webp&width=1600",
        )
    }
}
