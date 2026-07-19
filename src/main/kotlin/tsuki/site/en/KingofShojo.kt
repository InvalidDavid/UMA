package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource

@MangaSourceParser("KINGOFSHOJO", "King of Shojo", "en")
internal class KingofShojo(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.KINGOFSHOJO, "kingofshojo.com")