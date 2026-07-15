package org.koitharu.kotatsu.parsers.site.kotatsu.en.hentais

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANHWA18", "Manhwa18.com", "en", type = ContentType.HENTAI)
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
        SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL,
        SortOrder.NEWEST, SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    private val chapterDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH)

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

    private fun extractCoverUrl(doc: org.jsoup.nodes.Document, fallback: String = ""): String {
        doc.selectFirst("meta[property=og:image]")?.attrAsAbsoluteUrl("content")?.let { return it }
        doc.selectFirst(".lazy-bg")?.attrAsAbsoluteUrl("data-bg")?.let { return it }
        val styleUrl = doc.selectFirst(".img-in-ratio")?.attr("style")
            ?: doc.selectFirst(".au-cover-art")?.attr("style")
        if (styleUrl != null) {
            val url = styleUrl.substringAfter("url('").substringBefore("')")
                .ifEmpty { styleUrl.substringAfter("url(\"").substringBefore("\"") }
            if (url.isNotEmpty()) {
                return if (url.startsWith("http")) url else url.toAbsoluteUrl(domain)
            }
        }
        return fallback
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
                    SortOrder.POPULARITY -> "top"
                    SortOrder.UPDATED -> "update"
                    SortOrder.NEWEST -> "new"
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

    @get:Synchronized
    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    override suspend fun getDetails(manga: Manga): Manga {
        detailsCache[manga.url]?.let { return it }

        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.selectFirst("h1.au-info-title")?.text()?.trim() ?: manga.title
        val coverUrl = extractCoverUrl(doc, manga.coverUrl.orEmpty())
        val description = doc.selectFirst(".au-syn-body.summary-content")?.text()?.trim()

        val statusText = doc.select(".au-stat")
            .find { it.selectFirst(".k")?.text()?.equals("Status", ignoreCase = true) == true }
            ?.selectFirst(".v")?.text()?.trim()
        val state = when (statusText?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "on hold" -> MangaState.PAUSED
            else -> null
        }

        val tags = doc.select(".au-genres a.au-genre").mapNotNull { a ->
            val slug = a.attr("href").substringAfter("/genre/").substringBefore("/").ifBlank { null }
                ?: return@mapNotNull null
            MangaTag(a.text().trim(), slug, source)
        }.toSet()

        val chapters = parseChapters(doc)

        val fullManga = manga.copy(
            title = title,
            coverUrl = coverUrl,
            description = description,
            state = state,
            tags = tags,
            authors = emptySet(),
            chapters = chapters,
        )

        detailsCache[manga.url] = fullManga
        return fullManga
    }

    private fun parseChapters(doc: org.jsoup.nodes.Document): List<MangaChapter> {
        return doc.select(".au-chgrid a.au-chtile").mapNotNull { a ->
            val chUrl = a.attrAsAbsoluteUrl("href").toRelativeUrl(domain)
            val chTitle = a.selectFirst(".au-chtile-num")?.text()?.trim() ?: return@mapNotNull null
            val dateStr = a.selectFirst(".au-chtile-date")?.text()?.substringAfter("· ")?.trim()
            val uploadDate = dateStr?.let {
                runCatching { chapterDateFormat.parse(it)?.time ?: 0L }.getOrDefault(0L)
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
