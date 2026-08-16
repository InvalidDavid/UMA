package org.koitharu.kotatsu.parsers.site.kotatsu.ru.madara

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@MangaSourceParser("MANGAONELOVE", "MangaOneLove", "ru")
internal class MangaoneLove(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGAONELOVE, "mangaonelove.website", 10) {
    override val datePattern = "dd.MM.yyyy"
    override val postReq = true
}
