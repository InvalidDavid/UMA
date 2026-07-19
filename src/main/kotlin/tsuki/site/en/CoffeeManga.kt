package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("COFFEEMANGA", "CoffeeManga", "en")
internal class CoffeeManga(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.COFFEEMANGA, "coffeemanga.ink")