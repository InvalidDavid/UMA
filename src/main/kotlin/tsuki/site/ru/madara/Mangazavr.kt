package tsuki.site.ru.madara

import tsuki.Broken
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.ContentType
import tsuki.model.MangaParserSource
import tsuki.parsers.MadaraParser

@Broken
@MangaSourceParser("MANGAZAVR", "Mangazavr", "ru", ContentType.HENTAI)
internal class Mangazavr(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGAZAVR, "mangazavr.ru") {
	override val listUrl = "/?s=&post_type=wp-manga"
	override val datePattern = "dd.MM.yyyy"
}
