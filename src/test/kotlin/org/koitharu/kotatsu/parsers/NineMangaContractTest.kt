package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

class NineMangaContractTest {

    @Test
    fun `reads catalog details chapters and pages from current Niadd markup`() = runBlocking {
        val context = FixtureMangaLoaderContext.builder()
            .get("https://ru.niadd.com/category/index_1", "/fixtures/ninemanga/catalog.html")
            .get("https://ru.niadd.com/manga/example.html", "/fixtures/ninemanga/details.html")
            .get("https://ru.niadd.com/manga/example/chapters.html", "/fixtures/ninemanga/chapters.html")
            .get("https://ru.niadd.com/search/?type=high", "/fixtures/ninemanga/tags.html")
            .get("https://ru.niadd.com/chapter/1_1/123/", "/fixtures/ninemanga/pages.html")
            .get("https://ru.niadd.com/chapter/1_1/123-1.html", "/fixtures/ninemanga/page.html")
            .build()
        val parser = MangaParserSource.NINEMANGA_RU.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.NINEMANGA_RU,
            expectedTitle = "Example Manga",
            sortOrder = SortOrder.POPULARITY,
            expectedPageUrl = "https://cdn.niadd.test/example-001.webp",
        )
    }
}
