package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaKParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("TOONTOPIO", "ToonTop.io", "en", ContentType.HENTAI)
internal class ToonTopIO(context: MangaLoaderContext) :
    MangaKParser(context, MangaParserSource.TOONTOPIO, "toontop.io")
