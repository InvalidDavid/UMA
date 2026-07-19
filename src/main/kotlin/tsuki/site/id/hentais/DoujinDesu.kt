package tsuki.site.id.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.DoujinDesuParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

import java.util.EnumSet

@MangaSourceParser("DOUJINDESU", "DoujinDesu", "id", ContentType.HENTAI)
internal class DoujinDesu(context: MangaLoaderContext) :
    DoujinDesuParser(context, MangaParserSource.DOUJINDESU) {

    override val defaultTypes: String = "doujinshi,manga,manhwa"

    override val availableContentTypes: Set<ContentType> = EnumSet.of(
        ContentType.MANGA,
        ContentType.DOUJINSHI,
        ContentType.MANHWA
    )
}