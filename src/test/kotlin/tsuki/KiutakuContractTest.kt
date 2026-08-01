package tsuki

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder

class KiutakuContractTest {

    @Test
    fun `source supports reading from list through pages`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://kiutaku.com/?start=20",
                "/fixtures/kiutaku/list.html",
            )
            .get(
                "https://kiutaku.com/gallery/example-gallery",
                "/fixtures/kiutaku/details.html",
            )
            .get(
                "https://kiutaku.com/gallery/example-gallery/1",
                "/fixtures/kiutaku/pages.html",
            )
            .build()
        val parser = MangaParserSource.KIUTAKU.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.KIUTAKU,
            expectedTitle = "Example Gallery",
            sortOrder = SortOrder.NEWEST,
        )
    }
}
