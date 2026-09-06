package tsuki.site.ru

import tsuki.parsers.GroupleParser
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey

import tsuki.model.MangaParserSource

@MangaSourceParser("SEIMANGA", "SeiManga", "ru")
internal class SeiManga(context: MangaLoaderContext) :
    GroupleParser(context, MangaParserSource.SEIMANGA, 21) {
    override val configKeyDomain = ConfigKey.Domain("1.seimanga.me", "seimanga.me")
}
