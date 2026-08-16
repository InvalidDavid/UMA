package org.koitharu.kotatsu.parsers.site.kotatsu.ru.madara

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@MangaSourceParser("MANGAMAMMY", "Nimanga", "ru")
internal class MangaMammy(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGAMAMMY, "p.nimanga.com") {
    override val datePattern = "dd.MM.yyyy"
    override val postReq = true
}
