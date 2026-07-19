package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.*
import tsuki.util.*
import tsuki.util.json.getFloatOrDefault
import tsuki.util.json.getIntOrDefault
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSON

import org.jsoup.nodes.Document
import java.text.SimpleDateFormat

@MangaSourceParser("ZINMANGA", "ZinManga.net", "en")
internal class Zinmanga(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.ZINMANGA, "zinmanga.net") {
    override val datePattern = "yyyy-MM-dd"
    override val withoutAjax = true

    override val selectRequiredLogin = ".content-blocked"

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://www.zinmanga.net/")
        .build()

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val slug = manga.url.removeSuffix("/").substringAfterLast('/')
        val mangaUrl = manga.url.removeSuffix("/")
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
        val collected = ArrayList<MangaChapter>()
        var page = 1
        while (true) {
            val url = "https://$domain/api/comics/$slug/chapters?page=$page"
            val data = webClient.httpGet(url).parseJson().getJSONObject("data")
            data.getJSONArray("chapters").mapJSON { jo ->
                val chapterSlug = jo.getString("chapter_slug")
                val href = "$mangaUrl/$chapterSlug" + stylePage
                collected += MangaChapter(
                    id = generateUid("$mangaUrl/$chapterSlug"),
                    title = jo.getStringOrNull("chapter_name"),
                    number = jo.getFloatOrDefault("chapter_num", 0f),
                    volume = 0,
                    url = href,
                    uploadDate = parseChapterDate(
                        dateFormat,
                        jo.getStringOrNull("updated_at")?.substringBefore('T'),
                    ),
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            }
            val lastPage = data.getIntOrDefault("last_page", page)
            if (page >= lastPage) break
            page++
        }
        return collected.sortedBy { it.number }
    }
}