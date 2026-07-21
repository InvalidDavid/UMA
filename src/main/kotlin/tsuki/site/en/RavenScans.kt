package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource

@MangaSourceParser("RAVENSCANS", "RavenScans", "en")
internal class RavensScan(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.RAVENSCANS, "ravenscans.org") {
    override val mangaDirectory = "series"
}
