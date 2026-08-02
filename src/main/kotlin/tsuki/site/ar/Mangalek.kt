package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource
import tsuki.model.MangaChapter
import tsuki.model.Manga
import tsuki.model.MangaTag

import tsuki.util.attrAsRelativeUrl
import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.selectFirstOrThrow
import tsuki.util.toAbsoluteUrl
import tsuki.util.removeSuffix

import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("MANGALEK", "Mangalek", "ar")
internal class Mangalek(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGALEK, "mangalik.net") {

    override val datePattern = "MMMM dd, yyyy"
    override val stylePage = ""

    private val altDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun parseDateTwoFormats(primary: java.text.DateFormat, date: String?): Long {
        date ?: return 0L
        return try {
            primary.parse(date)?.time ?: 0L
        } catch (_: Exception) {
            try {
                altDateFormat.parse(date)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
        return doc.body().select(selectChapter).map { li ->
            val a = li.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val link = href + stylePage   // stylePage is empty, so just href
            val dateText = li.selectFirst("a.c-new-tag")?.attr("title")
                ?: li.selectFirst(selectDate)?.text()
            val name = a.selectFirst("p")?.text() ?: a.ownText()
            MangaChapter(
                id = generateUid(href),
                title = name,
                number = name.extractChapterNumber(),
                volume = 0,
                url = link,
                uploadDate = parseDateTwoFormats(dateFormat, dateText),
                source = source,
                scanlator = null,
                branch = null,
            )
        }.sortedBy { it.number }
    }

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
        val doc = if (postReq) {
            val mangaId = document.select("div#manga-chapters-holder").attr("data-id")
            val url = "https://$domain/wp-admin/admin-ajax.php"
            val postData = postDataReq + mangaId
            webClient.httpPost(url, postData).parseHtml()
        } else {
            val url = mangaUrl.toAbsoluteUrl(domain).removeSuffix('/') + "/ajax/chapters/"
            webClient.httpPost(url, emptyMap()).parseHtml()
        }
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
        return doc.select(selectChapter).map { li ->
            val a = li.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val link = href + stylePage
            val dateText = li.selectFirst("a.c-new-tag")?.attr("title")
                ?: li.selectFirst(selectDate)?.text()
            val name = a.selectFirst("p")?.text() ?: a.ownText()
            MangaChapter(
                id = generateUid(href),
                url = link,
                title = name,
                number = name.extractChapterNumber(),
                volume = 0,
                branch = null,
                uploadDate = parseDateTwoFormats(dateFormat, dateText),
                scanlator = null,
                source = source,
            )
        }.sortedBy { it.number }
    }

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/$listUrl").parseHtml()
        return doc.selectFirst("div.genres")
            ?.select("a")
            .orEmpty()
            .mapNotNull { a ->
                val genre = a.ownText()
                if (genre.isBlank()) null
                else MangaTag(key = genre.lowercase(), title = genre, source = source)
            }
            .toSet()
    }
}
