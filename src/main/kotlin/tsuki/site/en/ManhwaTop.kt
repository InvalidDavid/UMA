package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.site.madara.MadaraParser

@MangaSourceParser("MANHWATOP", "ManhwaTop", "en")
internal class ManhwaTop(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANHWATOP, "manhwatop.com")
