package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MangaThemesia

@MangaSourceParser("RAVENSCANS", "RavenScans", "en")
internal class RavenScans(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.RAVENSCANS, "ravenscans.org")
