package tsuki

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder

class ReadMangaContractTest {

    @Test
    fun `source supports title search through current details and pages markup`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://a.zazaza.me/search/advancedResults?q=Artifact&offset=0&years=1900%2C2099&sortType=DATE_CREATE",
                "/fixtures/readmanga/search.html",
            )
            .get(
                "https://a.zazaza.me/igrok__kotoryi_est_artefakty",
                "/fixtures/readmanga/details.html",
            )
            .get(
                "https://a.zazaza.me/igrok__kotoryi_est_artefakty/vol1/45?mtr=1",
                "/fixtures/readmanga/pages.html",
            )
            .build()
        val parser = MangaParserSource.READMANGA_RU.newParser(context)

        val manga = parser.getList(
            offset = 0,
            order = SortOrder.NEWEST,
            filter = MangaListFilter.EMPTY.copy(query = "Artifact"),
        ).single()
        assertEquals("Игрок, который ест артефакты", manga.title)

        val details = parser.getDetails(manga)
        assertEquals("Игрок, который ест артефакты", details.title)
        assertEquals("Current ReadManga description.", details.description)
        assertEquals(setOf("Боевик", "Драма"), details.tags.mapTo(linkedSetOf()) { it.title })
        assertEquals("https://rm.one-way.work/uploads/pics/example.jpg", details.largeCoverUrl)

        val chapter = details.chapters.orEmpty().single()
        assertEquals(45f, chapter.number)
        assertEquals(1, chapter.volume)

        val pages = parser.getPages(chapter)
        assertFalse(pages.isEmpty())
        assertEquals("https://images.example/page-01.jpg", parser.getPageUrl(pages.single()))
        context.verifyAllFixturesUsed()
    }
}
