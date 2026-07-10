package org.koitharu.kotatsu.parsers.site.kotatsu.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.parsers.DoujinDesuParser
import java.util.EnumSet

@MangaSourceParser("DOUJINDESU", "DoujinDesu", "id")
internal class DoujinDesu(context: MangaLoaderContext) :
    DoujinDesuParser(context, MangaParserSource.DOUJINDESU) {

    override val defaultTypes: String = "doujinshi,manga,manhwa"

    override val availableContentTypes: Set<ContentType> = EnumSet.of(
        ContentType.MANGA,
        ContentType.DOUJINSHI,
        ContentType.MANHWA
    )
}
