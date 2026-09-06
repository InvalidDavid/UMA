package tsuki.site.fr

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.OriginesParser

import tsuki.model.MangaParserSource

@MangaSourceParser("MANGASORIGINESFR", "Mangas Origines", "fr")
class MangasOriginesFr(context: MangaLoaderContext) :
    OriginesParser(context, MangaParserSource.MANGASORIGINESFR, "mangas-origines.fr") {
    override val mangaPath = "oeuvre"
    override val legacyMangaPaths = setOf("catalogues")
}
