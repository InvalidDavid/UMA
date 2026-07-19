package tsuki.site.all.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.parsers.GalleryParser

import tsuki.model.MangaParserSource
import tsuki.model.ContentType

@MangaSourceParser("BUONDUA", "Buon Dua", type = ContentType.OTHER)
internal class BuonDua(context: MangaLoaderContext) :
    GalleryParser(context, MangaParserSource.BUONDUA, "buondua.com") {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(
        "buondua.com",
        "buondua.us",
    )
}