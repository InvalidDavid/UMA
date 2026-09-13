package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.KeyoApp

import tsuki.model.MangaParserSource

@MangaSourceParser("MISTSCANS", "Mist Scans", "en")
internal class MistScans(context: MangaLoaderContext) :
    KeyoApp(context, MangaParserSource.MISTSCANS, "mistscans.com", pageSize = 20) {

    override fun popularMangaSelector(): String = ".series-splide .splide__slide"
}
