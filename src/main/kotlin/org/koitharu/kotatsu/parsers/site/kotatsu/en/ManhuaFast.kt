package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@MangaSourceParser("MANHUAFAST", "ManhuaFast.net", "en")
internal class ManhuaFast(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANHUAFAST, "manhuafast.net") {
    override val withoutAjax = true

}