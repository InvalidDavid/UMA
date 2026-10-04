package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.VineTheme

import tsuki.model.MangaParserSource

@MangaSourceParser("DRAKESCANS", "DrakeScans", "en")
internal class DrakeScans(context: MangaLoaderContext) :
    VineTheme(context,MangaParserSource.DRAKESCANS, "drakecomic.net")
