package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag

import tsuki.util.parseHtml
import tsuki.util.toTitleCase




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