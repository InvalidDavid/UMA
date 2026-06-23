@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.parsers.site.kotatsu.en

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.Include
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.Match
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField.STATE
import org.koitharu.kotatsu.parsers.model.search.SearchableField.TAG
import org.koitharu.kotatsu.parsers.model.search.SearchableField.TITLE_NAME
import org.koitharu.kotatsu.parsers.parsers.MangaboxParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.util.rateLimit
import kotlin.time.Duration.Companion.seconds

@MangaSourceParser("MANGABAT", "MangaBat", "en")
internal class Mangabat(context: MangaLoaderContext) :
    MangaboxParser(context, MangaParserSource.MANGABAT) {

    override val configKeyDomain = ConfigKey.Domain("mangabats.com")

    override val webClient = OkHttpWebClient(
        context.httpClient.newBuilder()
            .rateLimit(5, 10.seconds)
            .build(),
        source,
    )


    override val listUrl = "/genre/all"
    override val searchUrl = "/search/story"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
    )

    @Deprecated("Use availableSortOrders instead", ReplaceWith("availableSortOrders"))
    override val searchQueryCapabilities: MangaSearchQueryCapabilities
        get() = MangaSearchQueryCapabilities(
            SearchCapability(
                field = TAG,
                criteriaTypes = setOf(Include::class),
                isMultiple = false,
            ),
            SearchCapability(
                field = TITLE_NAME,
                criteriaTypes = setOf(Match::class),
                isMultiple = false,
                isExclusive = true,
            ),
            SearchCapability(
                field = STATE,
                criteriaTypes = setOf(Include::class),
                isMultiple = false,
            ),
        )

    override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
        val titleQuery = query.criteria.filterIsInstance<Match<*>>()
            .firstOrNull { it.field == TITLE_NAME }
            ?.value as? String

        val url = if (!titleQuery.isNullOrBlank()) {
            "https://$domain/search/story/${normalizeSearchQuery(titleQuery).replace('_', '-')}?page=$page"
        } else {
            val tagCriterion = query.criteria.filterIsInstance<Include<*>>()
                .firstOrNull { it.field == TAG }

            val tagKey = (tagCriterion?.values?.firstOrNull() as? MangaTag)?.key
                ?: tagCriterion?.values?.firstOrNull()?.toString()

            val baseUrl = if (!tagKey.isNullOrBlank()) {
                "https://$domain/genre/$tagKey"
            } else {
                "https://$domain/genre/all"
            }

            val sortParam = when (query.order ?: SortOrder.UPDATED) {
                SortOrder.POPULARITY -> "topview"
                SortOrder.NEWEST -> "newest"
                else -> "latest"
            }

            val stateParam = query.criteria.filterIsInstance<Include<*>>()
                .firstOrNull { it.field == STATE }
                ?.values
                ?.firstOrNull()
                ?.let {
                    when (it) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        else -> "all"
                    }
                } ?: "all"

            "$baseUrl?type=$sortParam&state=$stateParam&page=$page"
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseSearchResults(doc)
    }

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain").parseHtml()
        val tags = doc.select("td a[href*='/genre/'], div.panel-genres-list a[href*='/genre/']")
            .drop(1)

        val result = LinkedHashSet<MangaTag>()

        for (a in tags) {
            val key = a.attr("href")
                .removeSuffix("/")
                .substringAfter("/genre/")
                .substringBefore("?")
                .nullIfEmpty()
                ?: continue

            if (result.any { it.key == key }) {
                continue
            }

            val title = a.text()
                .replace(" Manga", "")
                .toTitleCase(sourceLocale)

            result.add(
                MangaTag(
                    key = key,
                    title = title,
                    source = source,
                ),
            )
        }

        return result
    }

    override suspend fun getChapters(doc: Document): List<MangaChapter> {
        val slug = doc.location()
            .substringAfter("/manga/", "")
            .substringBefore("/")
            .trim()
            .ifEmpty { return super.getChapters(doc) }

        val apiChapters = runCatching {
            fetchChaptersApi(slug)
        }.getOrDefault(emptyList())

        return apiChapters.ifEmpty {
            super.getChapters(doc)
        }
    }

    private suspend fun fetchChaptersApi(slug: String): List<MangaChapter> {
        val rawChapters = ArrayList<JSONObject>()
        var offset = 0

        while (true) {
            val apiUrl = "https://$domain/api/manga/$slug/chapters?limit=$CHAPTER_LIST_TAKE&offset=$offset"
            val json = webClient.httpGet(apiUrl).parseJson()
            val data = json.optJSONObject("data") ?: break
            val chapters = data.optJSONArray("chapters") ?: break

            for (i in 0 until chapters.length()) {
                chapters.optJSONObject(i)?.let(rawChapters::add)
            }

            val hasMore = data.optJSONObject("pagination")?.optBoolean("has_more", false) == true
            if (!hasMore) {
                break
            }

            offset += CHAPTER_LIST_TAKE
        }

        return rawChapters.mapNotNull { chapter ->
            val chapterSlug = chapter.optString("chapter_slug").nullIfEmpty()
                ?: return@mapNotNull null
            val chapterName = chapter.optString("chapter_name").nullIfEmpty()
                ?: "Chapter"
            val chapterNumber = chapter.optString("chapter_num").toFloatOrNull()
                ?: chapter.optDouble("chapter_num", Double.NaN).takeUnless(Double::isNaN)?.toFloat()
                ?: 0f

            val url = "/manga/$slug/$chapterSlug"

            MangaChapter(
                id = generateUid(url),
                title = chapterName,
                number = chapterNumber,
                volume = 0,
                url = url,
                uploadDate = parseApiDate(chapter.optString("updated_at")),
                source = source,
                scanlator = null,
                branch = null,
            )
        }.sortedBy { it.number }
    }

    private fun parseApiDate(date: String?): Long {
        if (date.isNullOrBlank()) {
            return 0L
        }

        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
        ).onEach {
            it.timeZone = TimeZone.getTimeZone("UTC")
        }

        for (format in formats) {
            val parsed = format.parseSafe(date)
            if (parsed != 0L) {
                return parsed
            }
        }

        return 0L
    }

    @Deprecated("Use getRequestHeaders instead", ReplaceWith("getRequestHeaders()"))
    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://www.mangabats.com/")
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.method == "GET" || request.method == "POST") {
            val newRequest = request.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Accept-Encoding")
                .build()

            return chain.proceed(newRequest)
        }

        return chain.proceed(request)
    }

    companion object {

        private const val CHAPTER_LIST_TAKE = 1000
    }
}