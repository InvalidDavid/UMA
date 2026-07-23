package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.Manga
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaParserSource

import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl

@MangaSourceParser("ENTHUNDERSCANS", "EnThunderScans", "en")
internal class EnThunderScans(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.ENTHUNDERSCANS, "en-thunderscans.com", pageSize = 30) {

    override val mangaDirectory = "comics"

    override val chapterListSelector = "div.bxcl li:not(:has(a[data-bs-target='#lockedChapterModal']))"

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val base = super.getDetails(manga)
        val altTitle = doc.selectFirst(".alternative .desktop-titles, .alternative .mobile-titles.first")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return base.copy(
            altTitles = if (altTitle != null) setOf(altTitle) else base.altTitles
        )
    }
}
