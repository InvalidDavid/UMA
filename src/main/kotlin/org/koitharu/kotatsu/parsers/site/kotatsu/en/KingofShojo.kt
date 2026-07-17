package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MangaThemesia

@MangaSourceParser("KINGOFSHOJO", "King of Shojo", "en")
internal class KingofShojo(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.KINGOFSHOJO, "kingofshojo.com")
