package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("MANGA3ASQ", "3Asq", "ar")
internal class Manga3Asq(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGA3ASQ, "3asq.online") {
    override val datePattern = "d MMM\u060c yyy"
    override val withoutAjax = true
}
