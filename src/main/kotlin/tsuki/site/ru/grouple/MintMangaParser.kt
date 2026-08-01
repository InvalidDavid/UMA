package tsuki.site.ru.grouple

import tsuki.Broken
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.model.MangaParserSource

@Broken
@MangaSourceParser("MINTMANGA", "MintManga", "ru")
internal class MintMangaParser(
	context: MangaLoaderContext,
) : GroupleParser(context, MangaParserSource.MINTMANGA, 2) {

	override val configKeyDomain = ConfigKey.Domain(*domains)

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("referer", "https://$domain/")
		.build()

	companion object {

		val domains = arrayOf(
			"2.mintmanga.one",
			"24.mintmanga.one",
			"mintmanga.live",
			"mintmanga.com",
		)
	}
}
