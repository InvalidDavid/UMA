package org.koitharu.kotatsu.parsers.site.kotatsu.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@MangaSourceParser("TRUYENTUOITHO", "Truyện Tuổi Thơ", "vi")
internal class TruyenTuoiTho(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.TRUYENTUOITHO, "truyentuoitho.com") {
    override val datePattern = "dd/MM/yyyy"
    override val withoutAjax = true
}
