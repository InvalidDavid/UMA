package tsuki

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder

class HentaiEnvyContractTest {

    @Test
    fun `source supports reading from list through pages`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://hentaienvy.com/?page=1",
                "/fixtures/hentaienvy/list.html",
            )
            .get(
                "https://hentaienvy.com/gallery/example-gallery",
                "/fixtures/hentaienvy/details.html",
            )
            .get(
                "https://hentaienvy.com/gallery/example-gallery/1/",
                "/fixtures/hentaienvy/pages.html",
            )
            .build()
        val parser = MangaParserSource.HENTAIENVY.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.HENTAIENVY,
            expectedTitle = "Example Gallery",
            sortOrder = SortOrder.UPDATED,
            expectedPageUrl = "https://cdn.example.test/example-gallery-1.jpg",
        )
    }
}
