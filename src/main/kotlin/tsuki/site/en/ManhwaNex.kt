package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

@MangaSourceParser("MANHWANEX", "ManhwaNex", "en")
internal class ManhwaNex(context: MangaLoaderContext):
    MadaraParser(context, MangaParserSource.MANHWANEX, "manhwanex.com") {

    override val postReq = false

}