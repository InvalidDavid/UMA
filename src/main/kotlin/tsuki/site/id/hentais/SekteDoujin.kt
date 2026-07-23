package tsuki.site.id.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource
import tsuki.model.ContentType

@MangaSourceParser("SEKTEDOUJIN", "SekteDoujin", "id", ContentType.HENTAI)
internal class SekteDoujin(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.SEKTEDOUJIN, "sektedoujin.cc")
