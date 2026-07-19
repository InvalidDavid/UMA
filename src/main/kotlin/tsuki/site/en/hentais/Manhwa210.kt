package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.*

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANHWA210", "Manhwa210", "en", type = ContentType.HENTAI)
internal class Manhwa210(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANHWA210, 60) {

    override val configKeyDomain = ConfigKey.Domain("manhwa210.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    @Volatile
    private var tagsCache: Set<MangaTag>? = null
    private val tagsMutex = Mutex()

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getOrFetchTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    private suspend fun getOrFetchTags(): Set<MangaTag> {
        tagsCache?.let { return it }
        return tagsMutex.withLock {
            tagsCache ?: fetchTagsFromSite().also { tagsCache = it }
        }
    }

    private suspend fun fetchTagsFromSite(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain").parseHtml()
        return doc.select("ul.grid.grid-cols-2 a").mapToSet { a ->
            MangaTag(
                key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
                title = a.text(),
                source = source,
            )
        }
    }

    @get:Synchronized
    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

    override suspend fun getDetails(manga: Manga): Manga {
        detailsCache[manga.url]?.let { return it }

        val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val author = root.selectFirst("div.mt-2:contains(Artist) span a")?.textOrNull()

        val details = manga.copy(
            altTitles = setOfNotNull(root.selectLast("div.grow div:contains(Alt name) span")?.textOrNull()),
            state = when (root.selectFirst("div.mt-2:contains(Status) span.text-blue-500")?.text()) {
                "Ongoing" -> MangaState.ONGOING
                "Completed" -> MangaState.FINISHED
                else -> null
            },
            tags = root.select("div.mt-2:contains(Genres) a.bg-gray-500").mapToSet { a ->
                MangaTag(
                    key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
                    title = a.text(),
                    source = source,
                )
            },
            authors = setOfNotNull(author),
            description = root.selectFirst("meta[name=description]")?.attrOrNull("content"),
            chapters = root.select("div.justify-between ul.overflow-y-auto.overflow-x-hidden a")
                .mapNotNull { a ->
                    val href = a.attrAsRelativeUrl("href")
                    val name = a.selectFirst("span.text-ellipsis")?.text().orEmpty()
                    val dateText = a.parent()?.selectFirst("span.timeago")?.attr("datetime").orEmpty()
                    val number = extractChapterNumber(name)
                    MangaChapter(
                        id = generateUid(href),
                        title = name,
                        number = number,
                        volume = 0,
                        url = href,
                        scanlator = null,
                        uploadDate = runCatching { chapterDateFormat.parse(dateText)?.time ?: 0L }.getOrDefault(0L),
                        branch = null,
                        source = source,
                    )
                }
                .sortedBy { it.number }
        )

        detailsCache[manga.url] = details
        return details
    }

    private fun extractChapterNumber(title: String): Float {
        val regex = Regex("""(?i)(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)""")
        regex.find(title)?.let { match ->
            return match.groupValues[1].toFloatOrNull() ?: 0f
        }
        Regex("""(\d+(?:\.\d+)?)""").find(title)?.let {
            return it.value.toFloatOrNull() ?: 0f
        }
        return 0f
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            val query = filter.query
            when {
                !query.isNullOrEmpty() -> {
                    append("/search")
                    append("?filter[name]=")
                    append(query.urlEncoded())
                    if (page > 1) {
                        append("&page=")
                        append(page)
                    }
                    append("&sort=")
                    append(
                        when (order) {
                            SortOrder.POPULARITY -> "-views"
                            SortOrder.UPDATED -> "-updated_at"
                            SortOrder.NEWEST -> "-created_at"
                            SortOrder.ALPHABETICAL -> "name"
                            SortOrder.ALPHABETICAL_DESC -> "-name"
                            else -> "-updated_at"
                        },
                    )
                }
                filter.tags.isNotEmpty() -> {
                    val tag = filter.tags.first()
                    append("/genre/")
                    append(tag.key)
                    append("?page=")
                    append(page)
                }
                else -> {
                    append("/list")
                    append("?sort=")
                    append(
                        when (order) {
                            SortOrder.POPULARITY -> "-views"
                            SortOrder.UPDATED -> "-updated_at"
                            SortOrder.NEWEST -> "-created_at"
                            SortOrder.ALPHABETICAL -> "name"
                            SortOrder.ALPHABETICAL_DESC -> "-name"
                            else -> "-updated_at"
                        },
                    )
                    append("&page=")
                    append(page)
                }
            }
            if (filter.states.isNotEmpty()) {
                append("&filter[status]=")
                filter.states.forEach {
                    append(
                        when (it) {
                            MangaState.ONGOING -> "2,"
                            MangaState.FINISHED -> "1,"
                            else -> "1,2"
                        },
                    )
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("div.grid div.relative").map { div ->
            val href = div.selectFirst("a[href^=/manga/]")?.attrOrNull("href")
                ?: div.parseFailed("Cant find manga image!")
            val coverUrl = div.selectFirst("div.cover")?.attr("style")
                ?.substringAfter("url('")?.substringBefore("')")
            Manga(
                id = generateUid(href),
                title = div.select("div.p-2 a.text-ellipsis").text(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = coverUrl.orEmpty(),
                tags = setOf(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select("div.text-center img.lazy").mapNotNull { img ->
            val url = img.requireSrc()
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}