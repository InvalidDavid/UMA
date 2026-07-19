package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("TWENTYFOURHNOVEL", "24HNovel", "en")
internal class Novel24hParser(context: MangaLoaderContext) :
    MadaraParser(
        context = context,
        source = MangaParserSource.TWENTYFOURHNOVEL,
        domain = "24hnovel.com"
    ) {
    override val withoutAjax = true
}