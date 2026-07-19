package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource


@MangaSourceParser("ALLPORNCOMIC", "AllPornComic", "en", ContentType.HENTAI)
internal class AllPornComic(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.ALLPORNCOMIC, "allporncomic.com", pageSize = 24) {
    override val withoutAjax = true
    override val listUrl = "porncomic/"
    override val tagPrefix = "porncomic-cat/"
    override val datePattern = "MMMM dd, yyyy"
}