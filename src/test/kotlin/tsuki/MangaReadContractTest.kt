package tsuki

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder

class MangaReadContractTest {

    @Test
    fun `source supports reading from list through pages`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .post(
                "https://www.mangaread.org/wp-admin/admin-ajax.php",
                "/fixtures/mangaread/list.html",
            )
            .get(
                "https://www.mangaread.org/manga/example-title",
                "/fixtures/mangaread/details.html",
            )
            .get(
                "https://www.mangaread.org/chapter/example-title-1?style=list",
                "/fixtures/mangaread/pages.html",
            )
            .build()
        val parser = MangaParserSource.MANGAREAD.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.MANGAREAD,
            expectedTitle = "Example Title",
            sortOrder = SortOrder.UPDATED,
        )
    }
}
