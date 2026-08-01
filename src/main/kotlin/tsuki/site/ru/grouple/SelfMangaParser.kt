package tsuki.site.ru.grouple

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("SELFMANGA", "SelfManga", "ru", type = ContentType.OTHER)
internal class SelfMangaParser(
	context: MangaLoaderContext,
) : GroupleParser(context, MangaParserSource.SELFMANGA, 3) {

	override val configKeyDomain = ConfigKey.Domain(*domains)

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("referer", "https://$domain/")
		.build()

	companion object {

		val domains = arrayOf(
			"selfmanga.live",
		)
	}
}
