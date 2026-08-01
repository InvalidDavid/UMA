package tsuki

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder

class MangaCrazyContractTest {

    @Test
    fun `source supports reading from list through pages`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .post(
                "https://mangacrazy.net/wp-admin/admin-ajax.php",
                "/fixtures/mangacrazy/list.html",
            )
            .get(
                "https://mangacrazy.net/manga/example-title",
                "/fixtures/mangacrazy/details.html",
            )
            .get(
                "https://mangacrazy.net/chapter/example-title-1?style=list",
                "/fixtures/mangacrazy/pages.html",
            )
            .build()
        val parser = MangaParserSource.MANGACRAZY.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.MANGACRAZY,
            expectedTitle = "Example Title",
            sortOrder = SortOrder.UPDATED,
        )
    }
}
