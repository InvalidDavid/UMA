package tsuki.site.en

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

import tsuki.util.generateUid
import tsuki.util.parseJson

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.EnumSet

@MangaSourceParser("CHIKARI", "Chikari", "en")
class Chikari(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.CHIKARI, pageSize = 36) {

    override val configKeyDomain = ConfigKey.Domain("chikari.moe")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.UPDATED,
        SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isSearchWithFiltersSupported = true,
    )

    private val tags by lazy {
        GENRES.entries.map { (slug, title) ->
            MangaTag(key = slug, title = title, source = source)
        }.toSet()
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = tags,
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.OTHER, // OEL
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val offset = (page - 1) * pageSize
        val url = buildApiUrl(offset, order, filter)
        val response = webClient.httpGet(url).parseJson()
        return parseMangaList(response)
    }

    private fun buildApiUrl(offset: Int, order: SortOrder, filter: MangaListFilter): String {
        val sortParam = when (order) {
            SortOrder.NEWEST -> "added"
            SortOrder.POPULARITY -> "popular"
            SortOrder.ALPHABETICAL -> "title"
            SortOrder.UPDATED -> "updated"
            SortOrder.RATING -> "top_rated"
            else -> "popular"
        }

        return "https://$domain/api/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", sortParam)
            addQueryParameter("limit", pageSize.toString())
            addQueryParameter("offset", offset.toString())

            filter.query?.takeIf { it.isNotBlank() }?.let {
                addQueryParameter("q", it)
            }

            filter.tags.forEach { tag ->
                addQueryParameter("genre", tag.key)
            }

            filter.tagsExclude.forEach { tag ->
                addQueryParameter("genre_exclude", tag.key)
            }

            filter.types.forEach { type ->
                addQueryParameter("type", type.toQueryParam())
            }

            filter.states.firstOrNull()?.let { state ->
                addQueryParameter("status", state.toQueryParam())
            }
        }.build().toString()
    }

    private fun parseMangaList(json: JSONObject): List<Manga> {
        val items = json.getJSONArray("items")
        return (0 until items.length()).mapNotNull { index ->
            parseMangaItem(items.getJSONObject(index))
        }
    }

    private fun parseStatus(status: String?): MangaState? = when (status?.lowercase()) {
        "releasing" -> MangaState.ONGOING
        "completed" -> MangaState.FINISHED
        "hiatus" -> MangaState.PAUSED
        "cancelled" -> MangaState.ABANDONED
        else -> null
    }

    private fun normalizeRating(rating: Double): Float =
        if (rating > 0) rating.toFloat() / 10f else RATING_UNKNOWN

    private fun parseContentRating(isNsfw: Boolean): ContentRating =
        if (isNsfw) ContentRating.ADULT else ContentRating.SAFE

    private fun parseMangaItem(item: JSONObject): Manga? {
        val slug = item.optString("slug").takeIf { it.isNotEmpty() } ?: return null
        val title = item.optString("title").takeIf { it.isNotEmpty() } ?: return null

        val url = "/series/$slug".toAbsoluteUrl(domain)
        val state = parseStatus(item.optString("status"))
        val rating = normalizeRating(item.optDouble("rating", 0.0))
        val contentRating = parseContentRating(item.optBoolean("is_nsfw", false))
        val coverUrl = item.optString("cover_url").takeIf { it.isNotEmpty() }

        return Manga(
            id = generateUid(slug),
            title = title,
            altTitles = emptySet(),
            url = url,
            publicUrl = url,
            rating = rating,
            contentRating = contentRating,
            coverUrl = coverUrl,
            tags = emptySet(),
            state = state,
            authors = emptySet(),
            source = source,
        )
    }

    private val detailsCacheLock = Any()
    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    override suspend fun getDetails(manga: Manga): Manga {
        synchronized(detailsCacheLock) {
            detailsCache[manga.url]?.let { return it }
        }

        val result = try {
            coroutineScope {
                val slug = manga.url.substringAfterLast("/")
                val detailsJson = webClient.httpGet("https://$domain/api/series/$slug").parseJson()
                val data = detailsJson.optJSONObject("data") ?: detailsJson

                val chaptersDeferred = async { fetchChapters(slug, data) }

                val title = data.optString("title", manga.title)
                val cover = data.optString("cover_url").takeIf { it.isNotEmpty() } ?: manga.coverUrl
                val description = data.optString("description").takeIf { it.isNotEmpty() } ?: ""

                val state = parseStatus(data.optString("status"))

                val allTags = mutableSetOf<MangaTag>()

                data.optJSONArray("genres")?.let { genresArray ->
                    for (i in 0 until genresArray.length()) {
                        val genreObj = genresArray.optJSONObject(i) ?: continue
                        val genreSlug = genreObj.optString("slug")
                        val genreName = genreObj.optString("name")
                        if (genreSlug.isNotBlank() && genreName.isNotBlank()) {
                            allTags.add(MangaTag(key = genreSlug, title = genreName, source = source))
                        }
                    }
                }

                val authors = mutableSetOf<String>()
                data.optJSONArray("authors")?.let { authorsArray ->
                    for (i in 0 until authorsArray.length()) {
                        val authorObj = authorsArray.optJSONObject(i) ?: continue
                        val authorName = authorObj.optString("name")
                        if (authorName.isNotBlank()) {
                            authors.add(authorName)
                        }
                    }
                }

                val altTitles = mutableSetOf<String>()
                data.optJSONArray("alt_titles")?.let { altArray ->
                    for (i in 0 until altArray.length()) {
                        altArray.optString(i)?.takeIf { it.isNotBlank() }?.let { altTitles.add(it) }
                    }
                }

                val rating = normalizeRating(data.optDouble("rating", 0.0))
                val contentRating = parseContentRating(data.optBoolean("is_nsfw", false))

                val chapters = chaptersDeferred.await()

                manga.copy(
                    title = title,
                    coverUrl = cover,
                    description = description,
                    authors = authors,
                    tags = allTags,
                    altTitles = altTitles,
                    state = state,
                    rating = rating,
                    contentRating = contentRating,
                    chapters = chapters,
                )
            }
        } catch (_: Exception) {
            manga
        }

        synchronized(detailsCacheLock) {
            detailsCache[manga.url] = result
        }
        return result
    }

    private suspend fun fetchChapters(slug: String, detailsJson: JSONObject? = null): List<MangaChapter> {
        try {
            val url = "https://$domain/api/series/$slug/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("order", "desc")
                .addQueryParameter("limit", "9999")
                .addQueryParameter("offset", "0")
                .build()
                .toString()

            val response = webClient.httpGet(url).parseJson()
            response.optJSONArray("items")?.let { items ->
                return parseChapterArray(items, slug)
            }
        } catch (_: Exception) {
        }

        if (detailsJson != null) {
            val allChapters = mutableListOf<MangaChapter>()
            detailsJson.optJSONArray("chapters_head")?.let { allChapters.addAll(parseChapterArray(it, slug)) }
            detailsJson.optJSONArray("chapters_tail")?.let { allChapters.addAll(parseChapterArray(it, slug)) }
            return allChapters.distinctBy { it.number }.sortedBy { it.number }
        }
        return emptyList()
    }

    private fun parseChapterArray(array: JSONArray, slug: String): List<MangaChapter> {
        return (0 until array.length()).mapNotNull { index ->
            val ch = array.optJSONObject(index) ?: return@mapNotNull null
            val number = ch.optDouble("number", 0.0).toFloat()
            val title = ch.optString("title").takeIf { it.isNotEmpty() } ?: "Chapter $number"
            val createdAt = parseDate(ch.optString("created_at"))
            val chapterUrl = "/reader/$slug/$number".toAbsoluteUrl(domain)

            MangaChapter(
                id = generateUid("$slug-$number"),
                url = chapterUrl,
                title = title,
                number = number,
                volume = 0,
                uploadDate = createdAt,
                scanlator = null,
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }
    }

    private fun parseDate(dateStr: String): Long =
        try {
            Instant.parse(dateStr).toEpochMilli()
        } catch (_: Exception) {
            0L
        }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        try {
            val segments = chapter.url.trimEnd('/').split('/')
            if (segments.size < 2) return emptyList()
            val slug = segments[segments.size - 2]
            val number = segments[segments.size - 1]
            val response = webClient.httpGet("https://$domain/api/series/$slug/chapters/$number").parseJson()
            val pagesArray = response.optJSONArray("pages") ?: return emptyList()

            return (0 until pagesArray.length()).map { i ->
                val url = pagesArray.getString(i)
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        try {
            val slug = seed.url.substringAfterLast("/")
            val response = webClient.httpGet("https://$domain/api/series/$slug/similar")
            val array = response.toJSONArray()
            return (0 until array.length()).mapNotNull { index ->
                parseMangaItem(array.getJSONObject(index))
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun okhttp3.Response.toJSONArray(): JSONArray {
        val body = body?.string() ?: throw tsuki.exception.ParseException("Empty response body", request.url.toString())
        return JSONArray(body)
    }

    private fun ContentType.toQueryParam(): String = when (this) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        ContentType.OTHER -> "oel"
        else -> "manga"
    }

    private fun MangaState.toQueryParam(): String = when (this) {
        MangaState.ONGOING -> "releasing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        MangaState.ABANDONED -> "cancelled"
        else -> "upcoming"
    }

    private fun String.toAbsoluteUrl(domain: String): String =
        if (startsWith("http")) this else "https://$domain$this"

    companion object {
        private val GENRES = mapOf(
            "action" to "Action",
            "adult" to "Adult",
            "adventure" to "Adventure",
            "award-winning" to "Award Winning",
            "boys-love" to "Boys Love",
            "comedy" to "Comedy",
            "doujinshi" to "Doujinshi",
            "drama" to "Drama",
            "ecchi" to "Ecchi",
            "erotica" to "Erotica",
            "fantasy" to "Fantasy",
            "gender-bender" to "Gender Bender",
            "girls-love" to "Girls Love",
            "gourmet" to "Gourmet",
            "harem" to "Harem",
            "historical" to "Historical",
            "horror" to "Horror",
            "josei" to "Josei",
            "mahou-shoujo" to "Mahou Shoujo",
            "martial-arts" to "Martial Arts",
            "mature" to "Mature",
            "mecha" to "Mecha",
            "music" to "Music",
            "mystery" to "Mystery",
            "psychological" to "Psychological",
            "romance" to "Romance",
            "school-life" to "School Life",
            "sci-fi" to "Sci-Fi",
            "seinen" to "Seinen",
            "shotacon" to "Shotacon",
            "shoujo" to "Shoujo",
            "shoujo-ai" to "Shoujo Ai",
            "shounen" to "Shounen",
            "shounen-ai" to "Shounen Ai",
            "slice-of-life" to "Slice of Life",
            "smut" to "Smut",
            "sports" to "Sports",
            "supernatural" to "Supernatural",
            "suspense" to "Suspense",
            "thriller" to "Thriller",
            "tragedy" to "Tragedy",
            "yaoi" to "Yaoi",
            "yuri" to "Yuri",
        )
    }
}
