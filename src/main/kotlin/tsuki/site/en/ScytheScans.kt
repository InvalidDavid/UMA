package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import java.util.Base64

@MangaSourceParser("SYCTHESCANS", "Scythe Scans", "en")
internal class ScytheScans(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.SYCTHESCANS, "erosscans.com", pageSize = 20) {

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl).parseHtml()
        val script = doc.selectFirst("script[src^=data:text/javascript;base64,dHNfcmVhZGVyLnJ1bih7]")
        if (script != null) {
            val base64 = script.attr("src").substringAfter("base64,")
            val decoded = String(Base64.getDecoder().decode(base64), Charsets.UTF_8)
            val imageListJson = Regex("""images"\s*:\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
                .find(decoded)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

            val jsonArray = org.json.JSONArray(imageListJson)
            return (0 until jsonArray.length()).map { i ->
                val url = jsonArray.getString(i)
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }
        return super.getPages(chapter)
    }
}
