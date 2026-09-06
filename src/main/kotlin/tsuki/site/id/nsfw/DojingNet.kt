package tsuki.site.id.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("DOJINGNET", "Dojing.net", "id", ContentType.HENTAI)
internal class DojingNet(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.DOJINGNET, "dojing.net")
