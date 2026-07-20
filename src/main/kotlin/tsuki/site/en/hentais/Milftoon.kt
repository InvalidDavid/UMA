package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("MILFTOON", "MilfToon", "en", ContentType.HENTAI)
internal class MilfToon(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MILFTOON, "milftoon.xxx", 20) {
    override val withoutAjax = true
    override val listUrl = "comics/"
}
