package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.Manga18Parser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("MANGA18", "Manga18", "en", ContentType.HENTAI)
internal class Manga18(context: MangaLoaderContext) :
    Manga18Parser(context, MangaParserSource.MANGA18, "manga18.club")