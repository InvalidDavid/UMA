package org.koitharu.kotatsu.parsers.site.kotatsu.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import okhttp3.Interceptor
import okhttp3.Response

// TODO
// add filter options

@MangaSourceParser("MGREADIO", "Mgread.io", "en")
internal class MgreadIo(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MGREADIO, 24) {

    override val configKeyDomain = ConfigKey.Domain("mgread.io")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Referer", "https://$domain/")
            .header("Origin", "https://$domain")
            .build()
        return chain.proceed(request)
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    private val restChapterDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT+7")
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }

        if (query != null) {
            val url = "https://$domain/wp-json/initlise/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", query)
                .addQueryParameter("page", page.toString())
                .build()
            val jsonArray = JSONArray(webClient.httpGet(url).parseRaw())
            return (0 until jsonArray.length()).mapNotNull { i ->
                val obj = jsonArray.getJSONObject(i)
                val title = obj.getString("title").let { org.jsoup.Jsoup.parse(it).text() }
                val mangaUrl = obj.getString("url").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val thumb = obj.optString("thumb", null)
                Manga(
                    id = generateUid(mangaUrl),
                    title = title,
                    url = mangaUrl,
                    publicUrl = mangaUrl.toAbsoluteUrl(domain),
                    coverUrl = thumb,
                    source = source,
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                )
            }.filterNot { it.isAnime() }
        }

        val slug = if (order == SortOrder.POPULARITY) "manga-ranking" else "recently-updated"
        val url = if (page == 1) "https://$domain/$slug/" else "https://$domain/$slug/page/$page/"
        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaGrid(doc).filterNot { it.isAnime() }
    }

    private fun parseMangaGrid(doc: Document): List<Manga> {
        return doc.select(".manga-item-grid").map { element ->
            val titleElement = element.selectFirst("h2 a[href*='/manga/']")
                ?: element.selectFirst("a[href*='/manga/']:not([href*='/chapter-'])")
                ?: return@map null
            val href = titleElement.absUrl("href")
            Manga(
                id = generateUid(href),
                title = titleElement.text(),
                url = href.toHttpUrl().encodedPath,
                publicUrl = href,
                coverUrl = element.selectFirst("img")?.imageUrl(),
                source = source,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
            )
        }.filterNotNull()
    }

    private fun Manga.isAnime(): Boolean {
        val t = title.lowercase()
        return t.startsWith("anime -") || t.startsWith("anime –") ||
                url.substringAfter("/manga/", "").startsWith("anime-")
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val title = doc.selectFirst("#manga-title")?.ownText()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" [Ch.")?.trim()
            ?: manga.title

        val cover = doc.selectFirst(".story-cover img")?.imageUrl()
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: manga.coverUrl

        val descElement = doc.selectFirst("#manga-description")
        val descriptionText = descElement?.wholeText()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val genre = doc.select("#genre-tags a[href*='/genre/']")
            .joinToString { it.ownText().ifEmpty { it.text() } }

        val statusText = doc.selectFirst("#manga-status")?.text()?.lowercase(Locale.US)?.trim()
        val state = when (statusText) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "season end", "source hiatus", "caught up" -> MangaState.PAUSED
            "dropped" -> MangaState.ABANDONED
            else -> null
        }

        val metaRow = doc.selectFirst("#manga-title + div")
        val metadata = buildList {
            metaRow?.ownText()?.substringBefore("Chapters")?.trim()?.takeIf(String::isNotEmpty)?.let {
                add("Chapters: $it")
            }
            doc.selectFirst("#comic-othername")?.text()?.takeIf(String::isNotEmpty)?.let {
                add("Alternative title: $it")
            }
            doc.selectFirst(".init-review-info")?.text()?.takeIf(String::isNotEmpty)?.let {
                add("Rating: $it")
            }
            metaRow?.selectFirst(".init-plugin-suite-view-count-number")?.text()?.takeIf(String::isNotEmpty)?.let {
                add("Views: $it")
            }
            doc.selectFirst("#last-updated")?.text()?.takeIf(String::isNotEmpty)?.let {
                add("Last updated: $it")
            }
        }

        val description = buildString {
            if (descriptionText != null) append(descriptionText)
            if (metadata.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                metadata.joinTo(this, separator = "\n")
            }
        }.trim().takeIf(String::isNotEmpty)

        val tags = genre.split(",").mapNotNull { it.trim().takeIf(String::isNotEmpty) }.map { name ->
            MangaTag(name, name.lowercase(), source)
        }.toSet()

        val mangaId = doc.selectFirst("#manga-title[data-id], #chapter-search-input[data-manga-id]")
            ?.attr("data-id")?.ifEmpty { doc.selectFirst("#chapter-search-input[data-manga-id]")?.attr("data-manga-id") }
            ?.toIntOrNull()

        val chapters = if (mangaId != null) {
            fetchChaptersApi(mangaId, manga.url)
        } else {
            doc.select(".chapter-list .chapter-item").mapNotNull { chapterFromElement(it) }
        }

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            tags = tags,
            state = state,
            chapters = chapters,
        )
    }

    private suspend fun fetchChaptersApi(mangaId: Int, mangaPath: String): List<MangaChapter> {
        val result = mutableListOf<MangaChapter>()
        var page = 1
        do {
            val url = "https://$domain/wp-json/initmanga/v1/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("manga_id", mangaId.toString())
                .addQueryParameter("paged", page.toString())
                .addQueryParameter("per_page", "50")
                .build()
            val json = webClient.httpGet(url).parseJson()
            val items = json.optJSONArray("items") ?: break
            val totalPages = json.optInt("total_pages", 1)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val number = item.optDouble("number", -1.0).toFloat()
                val title = item.optString("title", "")
                val slug = item.optString("slug", "")
                val createdAt = item.optString("created_at", "")
                val chapterName = if (title.isNotEmpty()) "Chapter ${number.toInt()} - $title" else "Chapter ${number.toInt()}"
                val cleanPath = mangaPath.substringBefore("/chapter/").trimEnd('/')
                result.add(
                    MangaChapter(
                        id = generateUid("$cleanPath/$slug/"),
                        title = chapterName,
                        number = number,
                        url = "$cleanPath/$slug/",
                        uploadDate = restChapterDateFormat.parseSafe(createdAt),
                        source = source,
                        volume = 0,
                        scanlator = null,
                        branch = null,
                    )
                )
            }
            page++
        } while (page <= totalPages)
        return result.sortedBy { it.number }
    }

    private fun chapterFromElement(element: Element): MangaChapter? {
        val urlElement = element.selectFirst("a[href*='/chapter-']") ?: return null
        val chapterUrl = urlElement.absUrl("href")
        val name = element.selectFirst("h3")?.text()
            ?: chapterUrl.substringBeforeLast('/').substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercase() }
        val number = Regex("""/chapter-(\d+(?:\.\d+)?)/""").find(chapterUrl)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        val dateStr = element.selectFirst("time[datetime]")?.attr("datetime")
        val date = dateStr?.parseChapterDate() ?: 0L
        return MangaChapter(
            id = generateUid(chapterUrl.toHttpUrl().encodedPath),
            title = name,
            number = number,
            url = chapterUrl.toHttpUrl().encodedPath,
            uploadDate = date,
            source = source,
            volume = 0,
            scanlator = null,
            branch = null,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("#chapter-content img[src]").mapIndexed { _, img ->
            val src = img.absUrl("src")
            MangaPage(
                id = generateUid(src),
                url = src,
                preview = null,
                source = source,
            )
        }
    }

    private fun String?.parseChapterDate(): Long {
        if (isNullOrBlank()) return 0L
        val normalized = this
            .replace(Regex("""([+-]\d{2}):(\d{2})$"""), "$1$2")
            .replace(Regex("""Z$"""), "+0000")
        return chapterDateFormat.parseSafe(normalized)
    }

    private fun Element.imageUrl(): String? {
        return when (normalName()) {
            "meta" -> attr("content")
            else -> attr("abs:data-src").ifEmpty {
                attr("abs:data-lazy-src").ifEmpty { attr("abs:src") }
            }
        }.takeIf(String::isNotEmpty)
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private fun SimpleDateFormat.parseSafe(date: String): Long =
        runCatching { parse(date)?.time ?: 0L }.getOrDefault(0L)
}
