package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@MangaSourceParser("TOPMANHUA", "ManhuaTop", "en")
internal class TopManhua(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.TOPMANHUA, "manhuatop.org") {
    override val tagPrefix = "manhua-genre/"
    override val listUrl = "manhua/"
    override val datePattern = "MM/dd/yyyy"
}