package tsuki.site.id.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource
import tsuki.model.ContentType

@MangaSourceParser("KANZENIN", "Kanzenin", "id", ContentType.HENTAI)
internal class Kanzenin(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.KANZENIN, "kanzenin.info")
