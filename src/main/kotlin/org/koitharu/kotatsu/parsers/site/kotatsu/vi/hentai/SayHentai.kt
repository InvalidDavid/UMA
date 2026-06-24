package org.koitharu.kotatsu.parsers.site.kotatsu.vi.hentai

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.parsers.ManhwaZ

@MangaSourceParser("SAYHENTAI", "SayHentai", "vi", ContentType.HENTAI)
internal class SayHentai(context: MangaLoaderContext) :
    ManhwaZ(context, MangaParserSource.SAYHENTAI, "sayhentai.cx") {

    override val searchPath = "search"

    override val tagPath = "genre"

    override val availableSortOrders: Set<SortOrder> = emptySet()

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = setOf(
                MangaTag(
                    key = "manhwa",
                    title = "Manhwa",
                    source = source,
                ),
                MangaTag(
                    key = "manga",
                    title = "Manga",
                    source = source,
                ),
            ),
        )
    }
}
