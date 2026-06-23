package org.koitharu.kotatsu.parsers.site.kotatsu.en.hentais

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@MangaSourceParser("ALLPORNCOMIC", "AllPornComic", "en", ContentType.HENTAI)
internal class AllPornComic(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.ALLPORNCOMIC, "allporncomic.com", pageSize = 24) {
    override val withoutAjax = true
    override val listUrl = "porncomic/"
    override val tagPrefix = "porncomic-cat/"
    override val datePattern = "MMMM dd, yyyy"
}