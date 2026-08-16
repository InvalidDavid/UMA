package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder

class LiveSourceSmokeTest {

    @Test
    fun `run configured live source flow`() = runBlocking {
        val sourceName = System.getenv(ENV_SOURCE)
        assumeTrue(!sourceName.isNullOrBlank(), "$ENV_SOURCE is not configured")

        val source = MangaParserSource.valueOf(sourceName)
        val stage = LiveStage.valueOf(System.getenv(ENV_STAGE)?.uppercase() ?: LiveStage.PAGES.name)
        val order = SortOrder.valueOf(System.getenv(ENV_SORT)?.uppercase() ?: SortOrder.UPDATED.name)
        val query = System.getenv(ENV_QUERY)?.takeIf(String::isNotBlank)
        val expectedTitle = System.getenv(ENV_EXPECT_TITLE)?.takeIf(String::isNotBlank)
        val context = LiveMangaLoaderContext()
        val parser = context.newParserInstance(source)
        val filter = MangaListFilter.EMPTY.copy(query = query)

        val list = liveStep(source, "list") {
            parser.getList(offset = 0, order = order, filter = filter)
        }
        assertFalse(list.isEmpty(), "Live list is empty for ${source.name}")
        val manga = expectedTitle?.let { title ->
            list.firstOrNull { it.title.equals(title, ignoreCase = true) }
                ?: error("Cannot find '$title' in ${list.size} live list entries")
        } ?: list.first()
        println("[LIVE-SOURCE] list ok: count=${list.size}, selected=${manga.title}, url=${manga.url}")
        if (stage == LiveStage.LIST) return@runBlocking

        val details = liveStep(source, "details") { parser.getDetails(manga) }
        println(
            "[LIVE-SOURCE] details ok: title=${details.title}, " +
                "chapters=${details.chapters.orEmpty().size}, description=${!details.description.isNullOrBlank()}",
        )
        if (stage == LiveStage.DETAILS) return@runBlocking

        val chapter = details.chapters.orEmpty().firstOrNull()
            ?: error("Live details contain no chapters for ${source.name}: ${details.title}")
        println("[LIVE-SOURCE] chapter selected: title=${chapter.title}, url=${chapter.url}")
        if (stage == LiveStage.CHAPTERS) return@runBlocking

        val pages = liveStep(source, "pages") { parser.getPages(chapter) }
        assertFalse(pages.isEmpty(), "Live pages are empty for ${source.name}: ${chapter.title}")
        val resolvedUrl = liveStep(source, "page-url") { parser.getPageUrl(pages.first()) }
        println("[LIVE-SOURCE] pages ok: count=${pages.size}, first=$resolvedUrl")
    }

    private suspend fun <T> liveStep(
        source: MangaParserSource,
        operation: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (error: Throwable) {
        throw AssertionError("Live $operation failed for ${source.name}: ${error.message}", error)
    }

    private enum class LiveStage {
        LIST,
        DETAILS,
        CHAPTERS,
        PAGES,
    }

    private companion object {
        const val ENV_SOURCE = "LIVE_SOURCE"
        const val ENV_STAGE = "LIVE_STAGE"
        const val ENV_SORT = "LIVE_SORT"
        const val ENV_QUERY = "LIVE_QUERY"
        const val ENV_EXPECT_TITLE = "LIVE_EXPECT_TITLE"
    }
}
