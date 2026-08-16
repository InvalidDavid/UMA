package org.koitharu.kotatsu.parsers.site.kotatsu.ru.madara

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@Broken
@MangaSourceParser("MANGAZAVR", "Mangazavr", "ru", ContentType.HENTAI)
internal class Mangazavr(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGAZAVR, "mangazavr.ru") {
    override val listUrl = "/?s=&post_type=wp-manga"
    override val datePattern = "dd.MM.yyyy"
}
