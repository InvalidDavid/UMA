package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaKParser

import tsuki.model.MangaParserSource

@MangaSourceParser("MANGAK", "MangaK", "en")
internal class MangaK(context: MangaLoaderContext) :
    MangaKParser(context, MangaParserSource.MANGAK, "mangak.io")
