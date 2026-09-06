package tsuki.site.fr

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("MANGASCANTRAD", "Manga-Scantrad", "fr")
internal class MangaScantrad(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGASCANTRAD, "manga-scantrad.io") {
    override val datePattern = "d MMMM yyyy"
}
