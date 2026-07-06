package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.koitharu.kotatsu.parsers.parsers.MadaraParser
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource

@MangaSourceParser("TWENTYFOURHNOVEL", "24HNovel", "en")
internal class Novel24hParser(context: MangaLoaderContext) :
    MadaraParser(
        context = context,
        source = MangaParserSource.TWENTYFOURHNOVEL,
        domain = "24hnovel.com"
    ) {
    override val withoutAjax = true
}
