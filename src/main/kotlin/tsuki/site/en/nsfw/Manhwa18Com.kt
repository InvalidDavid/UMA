package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.attrAsAbsoluteUrl
import tsuki.util.generateUid
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl
import tsuki.util.toTitleCase
import tsuki.util.urlEncoded

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANHWA18", "Manhwa18.com", "en", ContentType.HENTAI)
internal class Manhwa18Com(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANHWA18, pageSize = 18) {

    override val configKeyDomain = ConfigKey.Domain("manhwa18.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,   // most viewed
        SortOrder.RELEVANCE,            // most liked
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.ADDED,                // new manga
        SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    private val chapterNumberRegex = Regex("""(?:chapter|ch|ep)\s*\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val numericRegex = Regex("""\d+""")

    @Volatile
    private var tagsCache: Set<MangaTag>? = null
    private val tagsMutex = Mutex()

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getOrFetchTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
    )

    private suspend fun getOrFetchTags(): Set<MangaTag> {
        tagsCache?.let { return it }
        return tagsMutex.withLock {
            tagsCache ?: fetchTags().also { tagsCache = it }
        }
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/tim-kiem?q=").parseHtml()
        val list = doc.select("[data-genre-id]")
        if (list.isEmpty()) return emptySet()
        return list.mapNotNull { item ->
            val id = item.attr("data-genre-id")
            val name = item.text().trim()
            if (id.isNotEmpty() && name.isNotEmpty()) {
                MangaTag(name.toTitleCase(Locale.ENGLISH), id, source)
            } else null
        }.toSet()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
        val url = buildString {
            append("https://$domain/tim-kiem?page=$page")
            if (query != null) {
                append("&q=")
                append(query.urlEncoded())
            }
            append("&accept_genres=")
            if (filter.tags.isNotEmpty()) {
                append(filter.tags.joinToString(",") { it.key })
            }
            append("&reject_genres=")
            if (filter.tagsExclude.isNotEmpty()) {
                append(filter.tagsExclude.joinToString(",") { it.key })
            }
            append("&sort=")
            append(
                when (order) {
                    SortOrder.ALPHABETICAL -> "az"
                    SortOrder.ALPHABETICAL_DESC -> "za"
                    SortOrder.POPULARITY -> "top"
                    SortOrder.RELEVANCE -> "like"
                    SortOrder.UPDATED -> "update"
                    SortOrder.ADDED -> "new"
                    SortOrder.RATING -> "like"
                    else -> "update"
                }
            )
            filter.states.oneOrThrowIfMany()?.let {
                append("&status=")
                append(
                    when (it) {
                        MangaState.ONGOING -> "1"
                        MangaState.FINISHED -> "3"
                        MangaState.PAUSED -> "2"
                        else -> ""
                    }
                )
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".thumb-item-flow").map { element ->
            val a = element.selectFirst("a") ?: return@map null
            val absUrl = a.attrAsAbsoluteUrl("href")
            val title = element.selectFirst(".series-title a")?.text() ?: a.text()
            val cover = element.selectFirst(".lazy-bg")?.attrAsAbsoluteUrl("data-bg") ?: ""
            Manga(
                id = generateUid(absUrl.toRelativeUrl(domain)),
                title = title,
                altTitles = emptySet(),
                url = absUrl.toRelativeUrl(domain),
                publicUrl = absUrl,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }.filterNotNull()
    }

    private val detailsCacheLock = Any()

    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    override suspend fun getDetails(manga: Manga): Manga {
        synchronized(detailsCacheLock) {
            detailsCache[manga.url]?.let { return it }
        }

        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.selectFirst("span.series-name a")?.text()?.trim() ?: manga.title

        val coverUrl = doc.selectFirst("meta[property=og:image]")?.attrAsAbsoluteUrl("content")
            ?: doc.selectFirst(".img-in-ratio")?.attr("style")?.let { style ->
                val url = style.substringAfter("url('").substringBefore("')")
                    .ifEmpty { style.substringAfter("url(\"").substringBefore("\"") }
                if (url.isNotEmpty()) url.toAbsoluteUrl(domain) else null
            } ?: manga.coverUrl.orEmpty()

        val descElement = doc.selectFirst(".summary-content")
        val description = descElement?.let {
            it.selectFirst(".summary-more")?.remove()
            it.text().trim()
        }

        val statusText = doc.select(".info-item")
            .firstOrNull { it.selectFirst(".info-name")?.text()?.equals("Status:", ignoreCase = true) == true }
            ?.selectFirst(".info-value a")?.text()?.trim()
        val state = when (statusText?.lowercase()) {
            "on going" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "on hold" -> MangaState.PAUSED
            else -> null
        }

        val altTitle = doc.select(".info-item")
            .firstOrNull { it.selectFirst(".info-name")?.text()?.equals("Other name:", ignoreCase = true) == true }
            ?.selectFirst(".info-value")?.text()?.trim()
            ?.removeSurrounding("(", ")")

        val genreItem = doc.select(".info-item")
            .firstOrNull { it.selectFirst(".info-name")?.text()?.equals("Genre:", ignoreCase = true) == true }
        val tags = genreItem?.select("a[href*='/genre/']")?.mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/genre/").substringBefore("/").ifBlank { null }
                ?: return@mapNotNull null
            val name = a.text().trim()
            if (slug.isNotEmpty() && name.isNotEmpty()) MangaTag(name, slug, source) else null
        }?.toSet() ?: emptySet()

        val author = doc.select(".info-item")
            .firstOrNull { it.selectFirst(".info-name")?.text()?.equals("Author:", ignoreCase = true) == true }
            ?.selectFirst(".info-value a")?.text()?.trim()

        val chapters = parseChapters(doc)

        val result = manga.copy(
            title = title,
            altTitles = if (altTitle != null) setOf(altTitle) else emptySet(),
            coverUrl = coverUrl,
            description = description,
            state = state,
            tags = tags,
            authors = if (author != null) setOf(author) else emptySet(),
            chapters = chapters,
        )
        synchronized(detailsCacheLock) {
            detailsCache[manga.url] = result
        }
        return result
    }

    private fun parseChapters(doc: org.jsoup.nodes.Document): List<MangaChapter> {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)
        return doc.select("ul.list-chapters.at-series > a").mapNotNull { a ->
            val chUrl = a.attrAsAbsoluteUrl("href").toRelativeUrl(domain)
            val chTitle = a.selectFirst(".chapter-name")?.text()?.trim() ?: return@mapNotNull null
            val dateTimeStr = a.selectFirst(".chapter-time")?.text()?.trim()
            val uploadDate = dateTimeStr?.substringAfter(" - ")?.let {
                runCatching { dateFormat.parse(it)?.time ?: 0L }.getOrDefault(0L)
            } ?: 0L
            val number = chapterNumberRegex.find(chTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                ?: numericRegex.find(chUrl)?.value?.toFloatOrNull()
                ?: 0f
            MangaChapter(
                id = generateUid(chUrl),
                title = chTitle,
                number = number,
                volume = 0,
                url = chUrl,
                scanlator = null,
                uploadDate = uploadDate,
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("#chapter-content img.lazy").mapNotNull { img ->
            val src = img.attrAsAbsoluteUrl("data-src")
                .takeIf { it.isNotEmpty() }
                ?: img.attrAsAbsoluteUrl("src").takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            MangaPage(
                id = generateUid(src),
                url = src,
                preview = null,
                source = source,
            )
        }
    }
}
