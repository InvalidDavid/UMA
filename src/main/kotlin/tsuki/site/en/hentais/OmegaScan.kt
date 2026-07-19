package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.HeanCmsParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

@MangaSourceParser("OMEGASCANS", "OmegaScans", "en", ContentType.HENTAI)
internal class OmegaScans(context: MangaLoaderContext) :
    HeanCmsParser(context, MangaParserSource.OMEGASCANS, "omegascans.org")