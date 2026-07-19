package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaReaderParser

import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaParserSource

@MangaSourceParser("ENTHUNDERSCANS", "EnThunderScans", "en")
internal class EnThunderScans(context: MangaLoaderContext) :
    MangaReaderParser(
        context,
        MangaParserSource.ENTHUNDERSCANS,
        "en-thunderscans.com",
        pageSize = 30,
        searchPageSize = 10,
    ) {
    override val listUrl = "/comics"

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )
}