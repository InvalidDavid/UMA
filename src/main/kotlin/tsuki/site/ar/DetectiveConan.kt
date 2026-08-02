package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentType
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag

@MangaSourceParser("DETECTIVECONAN", "DetectiveConan", "ar", ContentType.MANGA)
internal class DetectiveConan(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.DETECTIVECONAN, "manga.detectiveconanar.com") {
    override val datePattern = "MMMM dd, yyyy"

    override suspend fun fetchAvailableTags(): Set<MangaTag> = emptySet()

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = false,
        isYearRangeSupported = false,
    )
}
