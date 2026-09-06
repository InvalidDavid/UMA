package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource

@MangaSourceParser("ENTHUNDERSCANS", "EnThunderScans", "en")
internal class EnThunderScans(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.ENTHUNDERSCANS, "en-thunderscans.com", pageSize = 30) {
    override val mangaDirectory = "comics"
    override val chapterListSelector = "div.bxcl li:not(:has(a[data-bs-target='#lockedChapterModal']))"
}
