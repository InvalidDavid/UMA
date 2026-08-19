package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.attrAsRelativeUrl
import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.textOrNull
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.hours

@MangaSourceParser("LAVASCANS", "Lava Scans", "ar")
internal class LavaScans(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.LAVASCANS, "lavascans.com") {

    override val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale("ar"))

    init {
        paginator.firstPage = 0
        searchPaginator.firstPage = 0
    }

    private val slugRegex = Regex("""^\d+-""")
    private val listUrl = "https://$domain/$mangaDirectory/list-mode/"
    private val listSelector = "div#content div.soralist ul li a.series"

    private var urlMapCache: Map<String, String> = emptyMap()
    private var mapFetchedAt: Long = 0L
    private val mapMutex = Mutex()

    private suspend fun getUrlMap(): Map<String, String> {
        if (System.currentTimeMillis() - mapFetchedAt < 1.hours.inWholeMilliseconds) return urlMapCache
        return mapMutex.withLock {
            if (System.currentTimeMillis() - mapFetchedAt < 1.hours.inWholeMilliseconds) return urlMapCache
            urlMapCache = fetchUrlMap()
            mapFetchedAt = System.currentTimeMillis()
            urlMapCache
        }
    }

    private suspend fun fetchUrlMap(): Map<String, String> {
        return try {
            val doc = webClient.httpGet(listUrl).parseHtml()
            doc.select(listSelector).associate { a ->
                val fullUrl = a.attr("abs:href")
                val slug = fullUrl.removeSuffix("/").substringAfterLast("/")
                val permaSlug = slug.replaceFirst(slugRegex, "")
                permaSlug to slug
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private val browseSelector = ".listupd .manga-card-v"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val page = page + 1

        val url = if (query.isNotEmpty()) {
            val pagePath = if (page > 1) "page/$page/" else ""
            "https://$domain/$pagePath?s=${query.urlEncoded()}"
        } else {
            buildString {
                append("https://$domain/$mangaDirectory/")
                append("?page=$page")
                append("&order=")
                append(when (order) {
                    SortOrder.UPDATED -> "update"
                    SortOrder.POPULARITY -> "popular"
                    SortOrder.ADDED -> "latest"
                    SortOrder.ALPHABETICAL -> "title"
                    SortOrder.ALPHABETICAL_DESC -> "titlereverse"
                    else -> "update"
                })
                filter.states.firstOrNull()?.let {
                    append("&status=")
                    append(when (it) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.PAUSED -> "hiatus"
                        MangaState.ABANDONED -> "dropped"
                        else -> ""
                    })
                }
                filter.types.firstOrNull()?.let {
                    append("&type=")
                    append(when (it) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        ContentType.COMICS -> "comic"
                        ContentType.NOVEL -> "novel"
                        else -> ""
                    })
                }
                filter.tags.forEach { append("&genre[]=${it.key.urlEncoded()}") }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        val selector = if (query.isNotEmpty()) ".legend-card" else browseSelector
        val elements = doc.select(selector)
        if (elements.isEmpty()) return emptyList()

        val list = elements.mapNotNull { element ->
            parseMangaElement(element, isSearch = query.isNotEmpty())
        }

        return list.map { manga ->
            if (manga.url.startsWith("/$mangaDirectory/")) {
                val slug = manga.url.removeSuffix("/").substringAfterLast("/")
                val permaSlug = slug.replaceFirst(slugRegex, "")
                manga.copy(url = "/$mangaDirectory/$permaSlug/")
            } else {
                manga
            }
        }
    }

    private fun parseMangaElement(element: Element, isSearch: Boolean): Manga? {
        return if (isSearch) {
            val link = element.selectFirst(".legend-title a")
                ?: element.selectFirst(".legend-poster a") ?: return null
            val title = link.text().trim().ifBlank { link.attr("title") }
            val cover = element.selectFirst(".legend-poster img")?.imgAttr()
            val href = link.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                authors = emptySet(),
                coverUrl = cover,
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                state = null,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                source = source,
            )
        } else {
            val titleEl = element.selectFirst("h3.card-v-title a") ?: return null
            val title = titleEl.text().trim()
            val cover = element.selectFirst(".card-v-cover img")?.imgAttr()
            val href = titleEl.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                authors = emptySet(),
                coverUrl = cover,
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                state = null,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val permaSlug = manga.url.removeSuffix("/").substringAfterLast("/")
        val randomSlug = getUrlMap()[permaSlug] ?: permaSlug
        val fullUrl = "https://$domain/$mangaDirectory/$randomSlug/"
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val detailsContainer = doc.selectFirst("div.lh-container") ?: return manga
        val title = detailsContainer.select(".lh-title").text()
        val cover = detailsContainer.select(".lh-poster img").first()?.imgAttr() ?: manga.coverUrl
        val genres = detailsContainer.select(".lh-genres a").map { it.text() }.toSet()
        val desc = detailsContainer.select("#manga-story").textOrNull()?.trim()
        val statusText = detailsContainer.select(".status-badge-lux").text()
        val state = parseStatus(statusText)
        val chapters = loadChapters(doc, fullUrl)

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = desc,
            tags = genres.map { MangaTag(it.lowercase(), it, source) }.toSet(),
            state = state,
            chapters = chapters,
            altTitles = emptySet(),
            authors = emptySet()
        )
    }

    override val chapterListSelector = "#chapters-list-container .ch-item:not(.locked)"

    override suspend fun loadChapters(doc: Document, mangaUrl: String): List<MangaChapter> {
        val elements = doc.select(chapterListSelector)
        return elements.map { el ->
            val a = el.selectFirst("a") ?: throw Exception("Missing chapter link")
            val href = a.attrAsRelativeUrl("href")
            val name = el.selectFirst(".ch-num")?.text().orEmpty().ifBlank {
                a.selectFirst(".chapternum")?.text() ?: a.ownText()
            }
            val dateStr = el.selectFirst(".ch-date")?.text()
            val uploadDate = dateStr?.let { dateFormat.parse(it)?.time ?: 0L } ?: 0L
            MangaChapter(
                id = generateUid(href),
                url = href,
                title = name,
                number = name.extractChapterNumber(),
                volume = 0,
                uploadDate = uploadDate,
                scanlator = null,
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }
    }
}
