package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("DETECTIVECONAN", "DetectiveConan", "ar", ContentType.MANGA)
internal class DetectiveConan(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.DETECTIVECONAN, "manga.detectiveconanar.com") {
    override val datePattern = "MMMM dd, yyyy"
}
