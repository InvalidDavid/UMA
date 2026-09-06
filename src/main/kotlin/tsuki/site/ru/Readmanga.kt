package tsuki.site.ru

import tsuki.parsers.GroupleParser
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey

import tsuki.model.MangaParserSource

@MangaSourceParser("READMANGA_RU", "ReadManga", "ru")
internal class Readmanga(context: MangaLoaderContext) :
    GroupleParser(context, MangaParserSource.READMANGA_RU, 1) {
    override val configKeyDomain = ConfigKey.Domain("a.zazaza.me", "3.readmanga.ru")
}
