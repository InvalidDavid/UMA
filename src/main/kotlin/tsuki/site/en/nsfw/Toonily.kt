package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("TOONILY", "Toonily", "en", ContentType.HENTAI)
internal class Toonily(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.TOONILY, "toonily.com") {
    override val listUrl = "webtoon/"
    override val datePattern = "MMMM dd, yyyy"
}
