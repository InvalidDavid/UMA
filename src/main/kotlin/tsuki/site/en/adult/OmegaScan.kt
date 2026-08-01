package tsuki.site.en.adult

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.*
import tsuki.site.heancms.HeanCmsParser

@MangaSourceParser("OMEGASCANS", "OmegaScans", "en", ContentType.HENTAI)
internal class OmegaScans(context: MangaLoaderContext) :
    HeanCmsParser(context, MangaParserSource.OMEGASCANS, "omegascans.org")