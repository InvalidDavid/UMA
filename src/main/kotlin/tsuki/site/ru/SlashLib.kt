package tsuki.site.ru

import tsuki.parsers.LibSocialParser
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser

import tsuki.model.MangaParserSource

@MangaSourceParser("SLASHLIB", "SlashLib", "ru")
internal class SlashLib(context: MangaLoaderContext) :
    LibSocialParser(context, MangaParserSource.SLASHLIB, 2, "v2.slashlib.me")
