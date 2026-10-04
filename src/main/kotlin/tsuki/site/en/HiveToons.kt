package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.IkenParser

import tsuki.model.MangaParserSource

@MangaSourceParser("HIVETOONS", "HiveToons", "en")
internal class HiveToons(context: MangaLoaderContext) :
    IkenParser(context, MangaParserSource.HIVETOONS, "hivetoons.org")
