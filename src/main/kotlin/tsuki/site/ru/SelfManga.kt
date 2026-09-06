package tsuki.site.ru

import tsuki.parsers.GroupleParser
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("SELFMANGA", "SelfManga", "ru", ContentType.OTHER)
internal class SelfMangaParser(context: MangaLoaderContext) : 
    GroupleParser(context, MangaParserSource.SELFMANGA, 3) {
    override val configKeyDomain = ConfigKey.Domain("1.selfmanga.live")
}
