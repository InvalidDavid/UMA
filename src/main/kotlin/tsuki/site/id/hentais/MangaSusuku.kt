package tsuki.site.id.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("MANGASUSUKU", "MangaSusuku", "id", ContentType.HENTAI)
internal class MangaSusuku(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.MANGASUSUKU, "mangasusuku.com") {
    override val mangaDirectory = "komik"
    override val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)
}
