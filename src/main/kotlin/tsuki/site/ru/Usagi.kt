package tsuki.site.ru

import tsuki.parsers.GroupleParser
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey

import tsuki.model.MangaParserSource

@MangaSourceParser("USAGI", "Usagi", "ru")
internal class UsagiParser(context: MangaLoaderContext) : 
    GroupleParser(context, MangaParserSource.USAGI, 1) {
    override val configKeyDomain = ConfigKey.Domain("web.usagi.one")
}
