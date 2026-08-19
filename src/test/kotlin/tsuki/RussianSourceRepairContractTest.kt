package tsuki

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder

class RussianSourceRepairContractTest {

    @Test
    fun `repaired Russian sources satisfy their current reading contracts`() = runTest {
        verifyAComics()
        verifyDesu()
        verifyMangaOneLove()
    }

    private suspend fun verifyAComics() {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://acomics.ru/comics?ratings[]=1&ratings[]=2&ratings[]=3&ratings[]=4&ratings[]=5&ratings[]=6&skip=0&sort=last_update",
                "/fixtures/acomics/catalog.html",
            )
            .get("https://acomics.ru/~fixture/about", "/fixtures/acomics/details.html")
            .get("https://acomics.ru/comics", "/fixtures/acomics/catalog.html")
            .get("https://acomics.ru/~fixture/1", "/fixtures/acomics/page.html")
            .build()
        val parser = MangaParserSource.ACOMICS.newParser(context)

        val manga = parser.getList(
            offset = 0,
            order = SortOrder.UPDATED,
            filter = MangaListFilter.EMPTY,
        ).single()
        assertEquals("Fixture Comic", manga.title)
        assertEquals("https://acomics.ru/upload/fixture-cover.jpg", manga.coverUrl)

        val details = parser.getDetails(manga)
        assertEquals("Current AComics description.", details.description)
        assertEquals(setOf("Fantasy"), details.tags.mapTo(linkedSetOf()) { it.title })
        assertEquals(setOf("Fixture Author"), details.authors)

        val pages = parser.getPages(details.chapters.orEmpty().single())
        assertEquals(3, pages.size)
        assertEquals("https://acomics.ru/upload/fixture-page.jpg", parser.getPageUrl(pages.first()))
        context.verifyAllFixturesUsed()
    }

    private suspend fun verifyDesu() {
        val context = FixtureMangaLoaderContext.builder()
            .get("https://desu.uno/manga/?page=1", "/fixtures/desu/catalog.html")
            .get("https://desu.uno/manga/example.42/", "/fixtures/desu/details.html")
            .get("https://desu.uno/api/manga/42/chapters/690", "/fixtures/desu/pages.json")
            .build()
        val parser = MangaParserSource.DESUME.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.DESUME,
            expectedTitle = "Пример манги",
            sortOrder = SortOrder.UPDATED,
            expectedPageUrl = "https://img3.desu.uno/manga/rus/example/001.webp",
        )
    }

    private suspend fun verifyMangaOneLove() {
        val context = FixtureMangaLoaderContext.builder()
            .post(
                "https://mangaonelove.su/wp-admin/admin-ajax.php",
                "/fixtures/mangaonelove/list.html",
            )
            .get(
                "https://mangaonelove.su/manga/example-title",
                "/fixtures/mangaonelove/details.html",
            )
            .get(
                "https://mangaonelove.su/chapter/example-title-1?style=list",
                "/fixtures/mangaonelove/pages.html",
            )
            .build()
        val parser = MangaParserSource.MANGAONELOVE.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.MANGAONELOVE,
            expectedTitle = "Example Title",
            sortOrder = SortOrder.UPDATED,
        )
    }
}
