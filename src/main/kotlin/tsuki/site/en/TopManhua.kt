package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("TOPMANHUA", "ManhuaTop", "en")
internal class TopManhua(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.TOPMANHUA, "manhuatop.org") {
    override val tagPrefix = "manhua-genre/"
    override val listUrl = "manhua/"
    override val datePattern = "MM/dd/yyyy"
}