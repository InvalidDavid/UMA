package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@MangaSourceParser("MANHWANEX", "ManhwaNex", "en")
internal class ManhwaNex(context: MangaLoaderContext):
    MadaraParser(context, MangaParserSource.MANHWANEX, "manhwanex.com") {

    override val postReq = false

}
