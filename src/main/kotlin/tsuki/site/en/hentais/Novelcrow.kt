package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("NOVELCROW", "NovelCrow", "en", ContentType.HENTAI)
internal class Novelcrow(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.NOVELCROW, "novelcrow.com", 24) {
    override val withoutAjax = true
    override val tagPrefix = "genres-2/"
    override val listUrl = ""
}
