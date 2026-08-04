package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("TOONILY", "Toonily", "en")
internal class Toonily(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.TOONILY, "toonily.com") {
    override val listUrl = "webtoon/"
    override val datePattern = "MMMM dd, yyyy"
}
