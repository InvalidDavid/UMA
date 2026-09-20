package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.VineTheme

import tsuki.model.MangaParserSource

@MangaSourceParser("WITCHTOONS", "WitchToons", "en")
internal class WitchToons(context: MangaLoaderContext) :
    VineTheme(context,MangaParserSource.WITCHTOONS, "witchtoons.net",)
