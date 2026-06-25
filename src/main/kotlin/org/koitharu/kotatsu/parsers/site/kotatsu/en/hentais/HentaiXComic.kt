package org.koitharu.kotatsu.parsers.site.kotatsu.en.hentais

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.parsers.MadaraParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("HENTAIXCOMIC", "HentaiXComic", "en", ContentType.HENTAI)
internal class HentaixComic(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.HENTAIXCOMIC, "hentaixcomic.com", 16) {

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/?s=&post_type=wp-manga").parseHtml()
        val set = mutableSetOf<MangaTag>()
        val titles = mutableSetOf<String>()
        doc.select("div.checkbox-group input[type=checkbox]").forEach { input ->
            val key = input.attr("value")
            val title = input.nextElementSibling()?.text()?.toTitleCase()
            if (key.isNotEmpty() && !title.isNullOrEmpty() && titles.add(title.lowercase())) {
                set.add(MangaTag(key = key, title = title, source = source))
            }
        }
        return set
    }
}