package tsuki.site.id.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("KOMIKDEWASAART", "Komik Dewasa.art", "id", ContentType.HENTAI)
internal class KomikDewasaART(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.KOMIKDEWASAART, "komikdewasa.art") {
    override val mangaDirectory = "komik"
    override val chapterListSelector = "div.bxcl li:not(:has(a[data-bs-target='#lockedChapterModal']))"
}
