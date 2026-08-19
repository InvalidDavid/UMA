package tsuki.site.ru.grouple

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.model.MangaParserSource

@MangaSourceParser("USAGI", "Usagi", "ru")
internal class UsagiParser(
	context: MangaLoaderContext,
) : GroupleParser(context, MangaParserSource.USAGI, 1) {

	override val configKeyDomain = ConfigKey.Domain(*domains)

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("referer", "https://$domain/")
		.build()

	companion object {

		val domains = arrayOf("web.usagi.one")
	}
}
