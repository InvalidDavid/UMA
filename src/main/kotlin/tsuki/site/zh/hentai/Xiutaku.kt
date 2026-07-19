package tsuki.site.zh.hentai

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.GalleryParser

import tsuki.model.ContentType
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaParserSource

@MangaSourceParser("XIUTAKU", "Xiutaku", "zh", type = ContentType.OTHER)
internal class Xiutaku(context: MangaLoaderContext) :
    GalleryParser(context, MangaParserSource.XIUTAKU, "xiutaku.com") {

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(availableTags = fetchTags())
}