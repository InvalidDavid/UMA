package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.kotatsu.ru.parseWaMangaAlternativeTitles
import org.koitharu.kotatsu.parsers.site.kotatsu.ru.parseWaMangaGenres
import org.koitharu.kotatsu.parsers.site.kotatsu.ru.parseWaMangaSearchText

class WaMangaContractTest {

    @Test
    fun `source supports current v1 API from catalog through pages`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .get("https://wamanga.ru/api/v1/manga", "/fixtures/wamanga/catalog.json")
            .get("https://wamanga.ru/api/v1/manga/manga-id", "/fixtures/wamanga/details.json")
            .get("https://wamanga.ru/api/v1/manga/manga-id/chapters", "/fixtures/wamanga/chapters.json")
            .get("https://wamanga.ru/api/v1/chapters/chapter-id", "/fixtures/wamanga/pages.json")
            .build()
        val parser = MangaParserSource.WAMANGA.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.WAMANGA,
            expectedTitle = "Example Manga",
            sortOrder = SortOrder.ALPHABETICAL,
            expectedPageUrl = "https://wamanga.ru/app/uploads/example/001.webp",
        )
    }

    @Test
    fun `catalog search contract includes all current title fields`() {
        val item = JSONObject(
            """{
              "title":"Russian title",
              "titleEnglish":"English title",
              "alternateTitles":["Original title"],
              "genres":["Action","Drama"]
            }""",
        )

        assertEquals(setOf("English title", "Original title"), item.parseWaMangaAlternativeTitles())
        assertEquals(setOf("Action", "Drama"), item.parseWaMangaGenres())
        assertEquals(
            setOf("Russian title", "English title", "Original title"),
            item.parseWaMangaSearchText(),
        )
    }
}
