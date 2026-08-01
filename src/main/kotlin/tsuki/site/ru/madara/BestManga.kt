package tsuki.site.ru.madara

import tsuki.Broken
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.site.madara.MadaraParser

@Broken
@MangaSourceParser("BEST_MANGA", "BestManga", "ru")
internal class BestManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.BEST_MANGA, "bestmanga.club") {
	override val datePattern = "dd.MM.yyyy"
	override val postReq = true
}
