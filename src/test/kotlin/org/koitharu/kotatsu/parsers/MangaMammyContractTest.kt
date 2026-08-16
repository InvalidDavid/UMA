package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

class MangaMammyContractTest {

    @Test
    fun `source supports reading from list through pages`() = runTest {
        val context = FixtureMangaLoaderContext.builder()
            .post(
                "https://p.nimanga.com/wp-admin/admin-ajax.php",
                "/fixtures/mangamammy/list.html",
            )
            .get(
                "https://p.nimanga.com/manga/example-title",
                "/fixtures/mangamammy/details.html",
            )
            .get(
                "https://p.nimanga.com/chapter/example-title-1?style=list",
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
