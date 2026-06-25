package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@MangaSourceParser("COFFEEMANGA", "CoffeeManga", "en")
internal class CoffeeManga(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.COFFEEMANGA, "coffeemanga.ink")