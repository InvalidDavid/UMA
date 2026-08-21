package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("ANISASCANS", "AnisaScans", "en")
internal class AnisaScans(context: MangaLoaderContext):
    MadaraParser(context, MangaParserSource.ANISASCANS, "anisascans.in") {
    override val datePattern = "d MMMM, yyyy"
}
