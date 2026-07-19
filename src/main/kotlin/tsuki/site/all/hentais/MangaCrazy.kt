package tsuki.site.all.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.ContentType
import tsuki.model.MangaParserSource
import tsuki.parsers.MadaraParser

import java.util.Locale

@MangaSourceParser("MANGACRAZY", "MangaCrazy", "", ContentType.HENTAI)
internal class MangaCrazy(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGACRAZY, "mangacrazy.net") {
	override val sourceLocale: Locale = Locale.ENGLISH
}