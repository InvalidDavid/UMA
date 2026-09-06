package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("MANHWATOP", "ManhwaTop", "en")
internal class ManhwaTop(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANHWATOP, "manhwatop.com")