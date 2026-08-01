package tsuki

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder

class AquaMangaContractTest {

    @Test
    fun `source parses the current archive cards`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://aquareader.org/?s=&post_type=wp-manga&m_orderby=latest",
                "/fixtures/aquamanga/list.html",
            )
            .build()
        val parser = MangaParserSource.AQUAMANGA.newParser(context)

        val manga = parser.getList(
            offset = 0,
            order = SortOrder.UPDATED,
            filter = MangaListFilter.EMPTY,
        ).single()

        assertEquals("Magic Emperor", manga.title)
        assertEquals("/manga/magic-emperor/", manga.url)
        assertEquals("https://aquareader.org/manga/magic-emperor/", manga.publicUrl)
        assertEquals("https://aquareader.org/uploads/magic-emperor.webp", manga.coverUrl)
        context.verifyAllFixturesUsed()
    }
}
