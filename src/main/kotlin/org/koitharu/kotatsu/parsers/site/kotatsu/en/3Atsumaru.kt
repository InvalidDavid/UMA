package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ATSUMARU", "Atsumaru", "en")
internal class Atsumaru(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ATSUMARU, 24) {

    override val configKeyDomain = ConfigKey.Domain(DOMAIN)

    override val availableSortOrders = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    // =========================
    // SIMPLE IN-MEMORY CACHE
    // =========================
    private val mangaCache = mutableMapOf<String, Manga>()
    private val chapterCache = mutableMapOf<String, List<MangaChapter>>()

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {

        filter.query?.takeIf { it.isNotEmpty() }?.let {
            return getSearchPage(page, it)
        }

        val endpoint = when (order) {
            SortOrder.UPDATED -> RECENT_ENDPOINT
            else -> TRENDING_ENDPOINT
        }

        val url = buildString {
            append(BASE_API)
            append(endpoint)
            append("?page=")
            append(page - 1)
            append("&types=Manga,Manwha,Manhua,OEL")
        }

        val items = webClient.httpGet(url)
            .parseJson()
            .optJSONArray("items") ?: return emptyList()

        return List(items.length()) { i ->
            parseMangaDto(items.getJSONObject(i))
        }
    }

    private suspend fun getSearchPage(page: Int, query: String): List<Manga> {

        val url = buildString {
            append("https://")
            append(domain)
            append(SEARCH_ENDPOINT)
            append("?q=")
            append(query.urlEncoded())
            append("&query_by=title,englishTitle,otherNames")
            append("&limit=")
            append(pageSize)
            append("&page=")
            append(page)
            append("&query_by_weights=3,2,1")
            append("&include_fields=id,title,englishTitle,poster")
            append("&num_typos=4,3,2")
        }

        val hits = webClient.httpGet(url)
            .parseJson()
            .optJSONArray("hits") ?: return emptyList()

        return List(hits.length()) { i ->
            hits.getJSONObject(i)
                .getJSONObject("document")
                .let(::parseMangaDto)
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {

        val slug = manga.url

        // ======================
        // MANGA CACHE CHECK
        // ======================
        mangaCache[slug]?.let { cached ->
            return cached
        }

        val detailsJson = webClient.httpGet(
            "$BASE_API/manga/page?id=$slug"
        ).parseJson()

        val mangaPage = detailsJson.getJSONObject("mangaPage")
        val base = parseMangaDto(mangaPage)

        // ======================
        // CHAPTER CACHE CHECK
        // ======================
        chapterCache[slug]?.let { cachedChapters ->
            val cached = base.copy(chapters = cachedChapters, state = base.state)
            mangaCache[slug] = cached
            return cached
        }

        val chapters = mutableListOf<MangaChapter>()

        var page = 0

        while (true) {

            val json = webClient.httpGet(
                "$BASE_API/manga/chapters?id=$slug&filter=all&sort=desc&page=$page"
            ).parseJson()

            val arr = json.optJSONArray("chapters") ?: break

            if (arr.length() == 0) break

            // ✅ IMPORTANT FIX: build in correct order (1 → N)
            for (i in 0 until arr.length()) {

                val ch = arr.getJSONObject(i)

                chapters.add(
                    MangaChapter(
                        id = generateUid("$slug/${ch.getString("id")}"),
                        title = ch.optString("title"),
                        number = ch.optDouble("number", 0.0).toFloat(),
                        volume = 0,
                        url = "$slug/${ch.getString("id")}",
                        uploadDate = parseDate(ch.optString("createdAt")),
                        source = source,
                        scanlator = null,
                        branch = null
                    )
                )
            }

            val totalPages = json.optInt("pages", 0)
            val currentPage = json.optInt("page", 0)

            if (currentPage + 1 >= totalPages) break
            page++
        }

        val finalManga = base.copy(
            chapters = chapters,
            state = base.state
        )

        // ======================
        // SAVE CACHE
        // ======================
        chapterCache[slug] = chapters
        mangaCache[slug] = finalManga

        return finalManga
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {

        val (slug, chapterId) = chapter.url.split('/')

        val pages = webClient.httpGet(
            "$BASE_API/read/chapter?mangaId=$slug&chapterId=$chapterId"
        ).parseJson()
            .getJSONObject("readChapter")
            .getJSONArray("pages")

        return List(pages.length()) { i ->

            val obj = pages.getJSONObject(i)
            val image = obj.getString("image")

            MangaPage(
                id = generateUid(image),
                url = "https://$domain$image",
                preview = null,
                source = source
            )
        }
    }

    // =========================
    // HELPERS
    // =========================

    private fun parseMangaDto(json: JSONObject): Manga {

        val id = json.getString("id")
        val title = json.getString("title")

        val poster = json.opt("poster") ?: json.opt("image")

        val image = when (poster) {
            is JSONObject -> poster.optString("image")
            is String -> poster
            else -> null
        }?.removePrefix("/")

        return Manga(
            id = generateUid(id),
            url = id,
            publicUrl = "$BASE_WEB/manga/$id",
            coverUrl = image?.let { "$BASE_WEB/static/$it" },
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = parseState(json.optString("status")),
            source = source,
            description = json.optString("synopsis").nullIfEmpty(),
            contentRating = ContentRating.SAFE
        )
    }

    private fun parseState(status: String?): MangaState? {
        return when (status?.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "canceled", "cancelled" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseDate(str: String?): Long? {
        return runCatching {
            dateFormat.parse(str)?.time
        }.getOrNull()
    }

    companion object {
        private const val DOMAIN = "atsu.moe"
        private const val BASE_API = "https://atsu.moe/api"
        private const val BASE_WEB = "https://atsu.moe"

        private const val SEARCH_ENDPOINT =
            "/collections/manga/documents/search"

        private const val TRENDING_ENDPOINT =
            "/infinite/trending"

        private const val RECENT_ENDPOINT =
            "/infinite/recentlyUpdated"

        private val dateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    }
}