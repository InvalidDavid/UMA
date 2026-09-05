package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.MangaParserSource

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("APKOMIK", "Apkomik", "id")
internal class Apkomik(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.APKOMIK, "01.apkomik.com") {
    override val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))

    override fun parseAltTitles(doc: Document, table: Element?, hasTable: Boolean): Set<String> {
        return doc.select(".wd-full:contains(Alternative) span, .wd-full:contains(Alt) span")
            .flatMap { splitAltTitles(it.text()) }
            .toSet()
    }
}
