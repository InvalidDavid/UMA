package org.koitharu.kotatsu.parsers.site.kotatsu.en.hentais

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.parsers.HeanCmsParser

@MangaSourceParser("OMEGASCANS", "OmegaScans", "en", ContentType.HENTAI)
internal class OmegaScans(context: MangaLoaderContext) :
    HeanCmsParser(context, MangaParserSource.OMEGASCANS, "omegascans.org")