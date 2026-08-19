package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.Manga
import tsuki.model.MangaParserSource

@MangaSourceParser("WEBTOONXYZ", "WebtoonXYZ", "en")
internal class WebtoonXYZ(context: MangaLoaderContext):
    MadaraParser(context, MangaParserSource.WEBTOONXYZ, "webtoon.xyz", 20) {

    override val datePattern = "dd MMMM yyyy"

    override val listUrl = "webtoons/"

    private val thumbnailRegex = Regex("-\\d+x\\d+(\\.[a-zA-Z]+)$")

    override fun parseMangaList(doc: org.jsoup.nodes.Document): List<Manga> {
        return super.parseMangaList(doc).map { manga ->
            manga.copy(
                coverUrl = manga.coverUrl?.replace(thumbnailRegex, "$1")
            )
        }
    }
}
