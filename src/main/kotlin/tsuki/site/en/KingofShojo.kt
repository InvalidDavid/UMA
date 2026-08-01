package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.site.mangathemesia.MangaThemesia

@MangaSourceParser("KINGOFSHOJO", "King of Shojo", "en")
internal class KingofShojo(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.KINGOFSHOJO, "kingofshojo.com")
