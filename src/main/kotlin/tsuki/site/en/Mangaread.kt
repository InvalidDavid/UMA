package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("MANGAREAD", "MangaRead", "en")
internal class MangaRead(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGAREAD, "www.mangaread.org") {
    override val tagPrefix = "genres/"
    override val datePattern = "dd.MM.yyyy"
}