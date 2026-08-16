package org.koitharu.kotatsu.parsers.site.kotatsu.ru.madara

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@Broken
@MangaSourceParser("BEST_MANGA", "BestManga", "ru")
internal class BestManga(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.BEST_MANGA, "bestmanga.club") {
    override val datePattern = "dd.MM.yyyy"
    override val postReq = true
}
