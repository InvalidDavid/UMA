package tsuki.site.vi

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("TRUYENTUOITHO", "Truyện Tuổi Thơ", "vi")
internal class TruyenTuoiTho(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.TRUYENTUOITHO, "truyentuoitho.com") {
    override val datePattern = "dd/MM/yyyy"
    override val withoutAjax = true
}
