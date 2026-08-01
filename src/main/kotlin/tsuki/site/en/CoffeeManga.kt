package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.site.madara.MadaraParser

@MangaSourceParser("COFFEEMANGA", "CoffeeManga", "en")
internal class CoffeeManga(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.COFFEEMANGA, "coffeemanga.ink")