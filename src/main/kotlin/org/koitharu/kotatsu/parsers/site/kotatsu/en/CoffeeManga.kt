package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@MangaSourceParser("COFFEEMANGA", "CoffeeManga", "en")
internal class CoffeeManga(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.COFFEEMANGA, "coffeemanga.ink") {

    override fun imageFromElement(element: Element): String? = when {
        element.attr("data-src").isNotBlank() ->
            element.attr("abs:data-src")

        element.attr("data-lazy-src").isNotBlank() ->
            element.attr("abs:data-lazy-src")

        element.attr("srcset").isNotBlank() ->
            element.attr("abs:srcset").substringBefore(" ")

        element.attr("data-cfsrc").isNotBlank() ->
            element.attr("abs:data-cfsrc")

        else ->
            element.attr("abs:src").takeIf { it.isNotBlank() }
    }
}
