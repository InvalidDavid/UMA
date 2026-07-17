package org.koitharu.kotatsu.parsers.site.kotatsu.en

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAFOX", "MangaFox", "en")
internal class MangaFox(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAFOX, pageSize = 24) {

    private val baseUrl = "fanfox.net"
    private val mobileUrl = "https://m.fanfox.net"
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)

    override val configKeyDomain = ConfigKey.Domain(baseUrl)

    private val apiClient: OkHttpWebClient by lazy {
        val httpClient = context.httpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Referer", "https://$baseUrl/")
                    .build()
                chain.proceed(request)
            }
            .build()
        OkHttpWebClient(httpClient, source)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true
    )

    @Volatile
    private var tagsCache: Set<MangaTag>? = null
    private val tagsMutex = Mutex()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = getOrFetchTags()
        )
    }

    private suspend fun getOrFetchTags(): Set<MangaTag> {
        tagsCache?.let { return it }
        return tagsMutex.withLock {
            tagsCache ?: fetchTags().also { tagsCache = it }
        }
    }

    private fun fetchTags(): Set<MangaTag> {
        return listOf(
            "action", "adventure", "comedy", "drama", "fantasy",
            "harem", "romance", "school-life", "shounen", "shoujo",
            "seinen", "sci-fi", "slice-of-life", "supernatural",
            "ecchi", "mystery", "psychological", "historical",
            "martial-arts", "mecha", "sports"
        ).map {
            MangaTag(key = it, title = it.replace("-", " "), source = source)
        }.toSet()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildUrl(page, filter, order)
        val doc = apiClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun buildUrl(page: Int, filter: MangaListFilter, order: SortOrder): String {
        val query = filter.query?.trim().orEmpty()
        val tag = filter.tags.firstOrNull()?.key
        return when {
            !tag.isNullOrBlank() && query.isEmpty() ->
                "https://m.fanfox.net/search/cate/$tag"
            query.isNotEmpty() ->
                "https://m.fanfox.net/search?k=${query.urlEncoded()}"
            else ->
                buildString {
                    append("https://$baseUrl/directory/")
                    if (page > 1) append("$page.html")
                    if (order == SortOrder.UPDATED) append("?latest")
                }
        }
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        val searchItems = doc.select(".post-list li")
        if (searchItems.isNotEmpty()) {
            return searchItems.mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.attrAsRelativeUrl("href")
                Manga(
                    id = generateUid(href),
                    title = a.attr("title").ifBlank { a.selectFirst(".cover-info .title")?.text() ?: a.text() },
                    url = href,
                    publicUrl = a.absUrl("href"),
                    coverUrl = el.selectFirst("img")?.attr("abs:src").orEmpty(),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    tags = emptySet(),
                    authors = emptySet(),
                    state = null,
                    altTitles = emptySet(),
                    largeCoverUrl = null,
                    description = null,
                    source = source
                )
            }
        }

        val dirSelectors = listOf(
            "ul.manga-list-1-list li",
            "ul.manga-list-4-list li",
            "ul.manga-list-2-list li"
        )
        for (sel in dirSelectors) {
            val items = doc.select(sel)
            if (items.isNotEmpty()) {
                return items.mapNotNull { el ->
                    val a = el.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attrAsRelativeUrl("href")
                    Manga(
                        id = generateUid(href),
                        title = a.attr("title").ifBlank { a.text() },
                        url = href,
                        publicUrl = a.absUrl("href"),
                        coverUrl = el.selectFirst("img")?.attr("abs:src").orEmpty(),
                        rating = RATING_UNKNOWN,
                        contentRating = null,
                        tags = emptySet(),
                        authors = emptySet(),
                        state = null,
                        altTitles = emptySet(),
                        largeCoverUrl = null,
                        description = null,
                        source = source
                    )
                }
            }
        }
        return emptyList()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = apiClient.httpGet(manga.url.toAbsoluteUrl(baseUrl)).parseHtml()
        val info = doc.selectFirst(".detail-info-right")!!

        val chapters = doc.select("ul.detail-main-list li").mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
            val href = a.attrAsRelativeUrl("href")
            val name = a.selectFirst("p")?.text() ?: a.ownText()
            val rawDate = el.text()
                .let { text ->
                    Regex("""(Today|Yesterday|\w{3}\s\d{1,2},\s\d{4}|\d{4}-\d{2}-\d{2})""")
                        .find(text)?.value
                }
            val parsedDate = parseChapterDate(rawDate)
            MangaChapter(
                id = generateUid(href),
                title = name,
                number = extractChapterNumber(name),
                volume = 0,
                url = href,
                uploadDate = parsedDate.takeIf { it > 0L }
                    ?: (System.currentTimeMillis() - (i * 86_400_000L)),
                scanlator = null,
                branch = null,
                source = source
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = doc.selectFirst("h1")?.text() ?: manga.title,
            authors = info.select(".detail-info-right-say a").mapToSet { it.text() },
            tags = info.select(".detail-info-right-tag-list a").mapToSet {
                MangaTag(key = it.text().lowercase(), title = it.text(), source = source)
            },
            description = info.selectFirst("p.fullcontent")?.text(),
            state = parseStatus(info.selectFirst(".detail-info-right-title-tip")?.text().orEmpty()),
            coverUrl = doc.selectFirst(".detail-info-cover-img")?.attr("abs:src").orEmpty(),
            chapters = chapters
        )
    }

    private fun parseStatus(status: String): MangaState? = when {
        status.contains("Ongoing", true) -> MangaState.ONGOING
        status.contains("Completed", true) -> MangaState.FINISHED
        else -> null
    }

    private fun extractChapterNumber(name: String): Float {
        return Regex("""(?i)(chapter|ch\.)\s*(\d+(\.\d+)?)""")
            .find(name)
            ?.groupValues
            ?.getOrNull(2)
            ?.toFloatOrNull()
            ?: 0f
    }

    private fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val clean = date.trim()
        return when {
            "Today" in clean -> Calendar.getInstance().timeInMillis
            "Yesterday" in clean -> Calendar.getInstance().apply { add(Calendar.DATE, -1) }.timeInMillis
            clean.matches(Regex("""\d{4}-\d{2}-\d{2}""")) -> runCatching {
                val parts = clean.split("-")
                Calendar.getInstance().apply { set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt()) }.timeInMillis
            }.getOrDefault(0L)
            else -> runCatching { dateFormat.parse(clean)?.time ?: 0L }.getOrDefault(0L)
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val mobilePath = chapter.url.replace("/manga/", "/roll_manga/")
        val url = "$mobileUrl$mobilePath"
        val doc = apiClient.httpGet(url).parseHtml()
        return doc.select("#viewer img").map {
            val imgUrl = it.attr("abs:data-original")
            MangaPage(id = generateUid(imgUrl), url = imgUrl, preview = null, source = source)
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
