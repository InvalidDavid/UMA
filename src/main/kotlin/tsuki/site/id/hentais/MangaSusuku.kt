package tsuki.site.id.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaReaderParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

import java.util.Locale

@MangaSourceParser("MANGASUSUKU", "MangaSusuku", "id", ContentType.HENTAI)
internal class MangaSusuku(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANGASUSUKU, "mangasusuku.com", pageSize = 20, searchPageSize = 20) {
    override val listUrl = "/komik"
    override val datePattern = "MMM d, yyyy"
    override val sourceLocale: Locale = Locale.ENGLISH
    override val isNetShieldProtected = true
}
