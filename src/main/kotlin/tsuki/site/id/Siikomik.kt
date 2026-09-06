package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

import org.jsoup.nodes.Element

@MangaSourceParser("SIIKOMIK", "Siikomik", "id")
internal class Siikomik(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.SIIKOMIK, "siikomik.id") {

    override val listUrl = "komik/"

    override fun transformChapterName(element: Element, name: String): String {
        return if (element.hasClass("premium") || element.hasClass("premium-block")) {
            "🔒 $name"
        } else {
            name
        }
    }
}
