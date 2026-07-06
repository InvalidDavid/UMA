package org.koitharu.kotatsu.parsers.site.tachiyomi.en.mistscans

import org.koitharu.kotatsu.parsers.site.tachiyomi.parsers.Keyoapp
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.TachiyomiSource

@TachiyomiSource("MISTSCANS", "Mist Scans", "en")
class MistScans : Keyoapp() {

    override fun popularMangaSelector() = ".series-splide .splide__slide"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val a = element.selectFirst("a[href]")!!
        title = a.attr("title")
        setUrlWithoutDomain(a.attr("abs:href"))
        thumbnail_url = element.getImageUrl("*[style*=background-image]")
    }

    override val name: String
        get() = "Mist Scans"
    override val lang: String
        get() = "en"
    override val baseUrl: String
        get() = "https://mistscans.com"
}
