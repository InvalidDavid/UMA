package tsuki

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tsuki.model.MangaParserSource
import tsuki.model.SortOrder

class MangaMammyContractTest {

    @Test
    fun `source supports reading from list through pages`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .post(
                "https://mangamammy.ru/wp-admin/admin-ajax.php",
                "/fixtures/mangamammy/list.html",
            )
            .get(
                "https://mangamammy.ru/manga/example-title",
                "/fixtures/mangamammy/details.html",
            )
            .get(
                "https://mangamammy.ru/chapter/example-title-1?style=list",
                "/fixtures/mangamammy/pages.html",
            )
            .build()
        val parser = MangaParserSource.MANGAMAMMY.newParser(context)

        verifySourceReadingContract(
            parser = parser,
            context = context,
            expectedSource = MangaParserSource.MANGAMAMMY,
            expectedTitle = "Example Title",
            sortOrder = SortOrder.UPDATED,
        )
    }
}
