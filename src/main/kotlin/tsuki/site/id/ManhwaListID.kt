package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource

@MangaSourceParser("MANHWALISTID", "ManhwaList ID", "id")
internal class ManhwaListID(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.MANHWALISTID, "manhwalist02.asia")
