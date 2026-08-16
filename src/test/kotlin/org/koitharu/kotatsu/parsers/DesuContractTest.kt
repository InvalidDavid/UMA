package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

class DesuContractTest {

    @Test
    fun `source supports current html reader from catalog through pages`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .get("https://desu.uno/manga/?page=1", "/fixtures/desu/catalog.html")
            .get("https://desu.uno/manga/example.42/", "/fixtures/desu/details.html")
            .get("https://desu.uno/manga/example.42/vol2/ch3/rus", "/fixtures/desu/pages.html")
            .get("https://desu.uno/api/manga/42/chapters/1003", "/fixtures/desu/pages.json")
            .build()
        val parser = MangaParserSource.DESUME.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.DESUME,
            expectedTitle = "Пример манги",
            sortOrder = SortOrder.UPDATED,
            expectedPageUrl = "https://img3.desu.uno/manga/rus/example/001.webp?1003",
        )
    }
}
