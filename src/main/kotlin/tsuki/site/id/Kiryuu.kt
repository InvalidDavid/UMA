package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.NatsuParser

import tsuki.model.MangaParserSource

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class Kiryuu(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.KIRYUU, "v7.kiryuu.to")
