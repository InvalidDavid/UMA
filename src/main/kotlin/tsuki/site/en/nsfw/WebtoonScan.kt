package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("WEBTOONSCAN", "WebtoonScan", "en", ContentType.HENTAI)
internal class WebtoonScan(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.WEBTOONSCAN, "webtoonscan.com")
