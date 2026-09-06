package tsuki.site.ru

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.parsers.MadaraParser

@MangaSourceParser("MANGAMAMMY", "Nimanga", "ru")
internal class MangaMammy(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGAMAMMY, "p.nimanga.com") {
	override val datePattern = "dd.MM.yyyy"
	override val postReq = true
}
