package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource
import tsuki.site.madara.MadaraParser

@MangaSourceParser("MANHWANEX", "ManhwaNex", "en")
internal class ManhwaNex(context: MangaLoaderContext):
    MadaraParser(context, MangaParserSource.MANHWANEX, "manhwanex.com") {

    override val postReq = false

}
