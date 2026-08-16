package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

class AComicsContractTest {

    @Test
    fun `reads catalog details and pages from current AComics markup`() = runBlocking {
        val catalogUrl = "https://acomics.ru/comics?ratings[]=1&ratings[]=2&ratings[]=3" +
            "&ratings[]=4&ratings[]=5&ratings[]=6&skip=0&sort=last_update"
        val context = FixtureMangaLoaderContext.builder()
            .get(catalogUrl, "/fixtures/acomics/catalog.html")
            .get("https://acomics.ru/comics", "/fixtures/acomics/tags.html")
            .get("https://acomics.ru/~example/about", "/fixtures/acomics/details.html")
            .get("https://acomics.ru/~example/1", "/fixtures/acomics/page.html")
            .build()
        val parser = MangaParserSource.ACOMICS.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.ACOMICS,
            expectedTitle = "Example Comic",
            sortOrder = SortOrder.UPDATED,
            expectedPageUrl = "https://acomics.ru/upload/example-001.jpg",
        )
    }
}
