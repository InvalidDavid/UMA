package tsuki

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.SortOrder

class MangaWtfContractTest {

    @Test
    fun `source sends excluded labels separately from included labels`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://api.inkstory.net/v2/books?page=0&size=20&type=COMIC&sort=updatedAt%2Cdesc&labelsInclude=action&labelsExclude=romance",
                "/fixtures/mangawtf/list.json",
            )
            .build()
        val parser = MangaParserSource.MANGA_WTF.newParser(context)
        val source = MangaParserSource.MANGA_WTF

        val manga = parser.getList(
            offset = 0,
            order = SortOrder.UPDATED,
            filter = MangaListFilter.EMPTY.copy(
                tags = setOf(MangaTag("Action", "action", source)),
                tagsExclude = setOf(MangaTag("Romance", "romance", source)),
            ),
        ).single()

        assertEquals("Test title", manga.title)
        context.verifyAllFixturesUsed()
    }
}
