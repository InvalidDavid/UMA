package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource

@MangaSourceParser("DESPAIRMANGA", "Despair Manga", "ar")
internal class DespairManga(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.DESPAIRMANGA, "despair-manga.net")
