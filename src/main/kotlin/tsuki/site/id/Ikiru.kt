package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.NatsuParser

import tsuki.model.MangaParserSource

@MangaSourceParser("IKIRU", "Ikiru", "id")
internal class Ikiru(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.IKIRU,"07.ikiru.wtf")
