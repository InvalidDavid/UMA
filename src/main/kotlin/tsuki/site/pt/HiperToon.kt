package tsuki.site.pt

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.HiperParser

import tsuki.model.MangaParserSource

@MangaSourceParser("HIPERTOON", "Hipertoon", "pt")
internal class HiperToon(context: MangaLoaderContext) :
    HiperParser(context, MangaParserSource.HIPERTOON, "hipertoon.com")