package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.parsers.NatsuParser

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class Kiryuu(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.KIRYUU, "v7.kiryuu.to")
