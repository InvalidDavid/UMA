package tsuki

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import tsuki.model.MangaListFilter
import tsuki.model.MangaSource
import tsuki.model.SortOrder

internal suspend fun verifySourceReadingContract(
    parser: MangaParser,
    context: FixtureMangaLoaderContext,
    expectedSource: MangaSource,
    expectedTitle: String,
    sortOrder: SortOrder,
    expectedPageUrl: String? = null,
) {
    val manga = parser.getList(
        offset = 0,
        order = sortOrder,
        filter = MangaListFilter.EMPTY,
    ).single()

    assertEquals(expectedSource, manga.source)
    assertEquals(expectedTitle, manga.title)

    val details = parser.getDetails(manga)

    assertEquals(manga.id, details.id)
    assertEquals(manga.url, details.url)
    assertEquals(manga.source, details.source)

    val chapter = details.chapters.orEmpty().single()
    assertEquals(details.source, chapter.source)

    val pages = parser.getPages(chapter)

    assertFalse(pages.isEmpty())
    assertEquals(details.source, pages.single().source)
    if (expectedPageUrl != null) {
        assertEquals(expectedPageUrl, parser.getPageUrl(pages.single()))
    }
    context.verifyAllFixturesUsed()
}
