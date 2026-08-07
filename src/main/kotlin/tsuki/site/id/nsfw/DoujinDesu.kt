package tsuki.site.id.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.DoujinDesuParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("DOUJINDESU", "DoujinDesu", "id", ContentType.HENTAI)
internal class DoujinDesu(context: MangaLoaderContext) :
    DoujinDesuParser(context, MangaParserSource.DOUJINDESU)
