package tsuki.site.ru

import tsuki.parsers.LibSocialParser
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser

import tsuki.model.MangaParserSource

@MangaSourceParser("YAOILIB", "SlashLib", "ru")
internal class SlashLibParser(context: MangaLoaderContext) : 
    LibSocialParser(context, MangaParserSource.YAOILIB, 2, "v2.slashlib.me")
