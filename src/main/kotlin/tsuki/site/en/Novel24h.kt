package tsuki.site.en

import tsuki.Broken
import tsuki.site.madara.MadaraParser
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaParserSource

@MangaSourceParser("TWENTYFOURHNOVEL", "24HNovel", "en")
@Broken
internal class Novel24hParser(context: MangaLoaderContext) :
    MadaraParser(
        context = context,
        source = MangaParserSource.TWENTYFOURHNOVEL,
        domain = "24hnovel.com"
    ) {
    override val withoutAjax = true
}
