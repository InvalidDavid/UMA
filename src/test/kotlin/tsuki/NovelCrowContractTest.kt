package tsuki

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder

class NovelCrowContractTest {

    @Test
    fun `source uses the public catalog instead of the disabled ajax listing`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://novelcrow.com/?s=&post_type=wp-manga&m_orderby=latest",
                "/fixtures/novelcrow/list.html",
            )
            .build()
        val parser = MangaParserSource.NOVELCROW.newParser(context)

        val manga = parser.getList(
            offset = 0,
            order = SortOrder.UPDATED,
            filter = MangaListFilter.EMPTY,
        ).single()

        assertEquals("Unusual Companions", manga.title)
        assertEquals("/comic/unusual-companions/", manga.url)
        context.verifyAllFixturesUsed()
    }
}
