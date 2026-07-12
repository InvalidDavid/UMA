package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
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
