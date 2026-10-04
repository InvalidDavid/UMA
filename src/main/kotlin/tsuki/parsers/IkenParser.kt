package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.CommonHeaders
import tsuki.network.OkHttpWebClient

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
import tsuki.model.YEAR_UNKNOWN

import tsuki.util.generateUid
import tsuki.util.parseJson
import tsuki.util.parseJsonArray

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

internal abstract class IkenParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize) {

    override val configKeyDomain = ConfigKey.Domain(domain)
    protected val baseUrl = "https://$domain"
    protected val apiBaseUrl = "https://api.$domain"

    protected val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override val webClient by lazy {
        OkHttpWebClient(context.httpClient.newBuilder().build(), source)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set(CommonHeaders.REFERER, "$baseUrl/")
        .set(CommonHeaders.ACCEPT, "application/json")
        .build()

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isYearSupported = true,
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ADDED,
        SortOrder.ADDED_ASC,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    protected var cachedGenres: List<Pair<String, String>>? = null

    protected open suspend fun fetchGenres(): List<Pair<String, String>> {
        cachedGenres?.let { return it }
        val arr = webClient.httpGet("$apiBaseUrl/api/genres", getRequestHeaders()).parseJsonArray()
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(obj.optString("name") to obj.optString("id"))
        }
        cachedGenres = list
        return list
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = fetchGenres()
        val tags = mutableSetOf<MangaTag>()
        genres.forEach { (name, id) -> tags.add(MangaTag(name, "genre_$id", source)) }

        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.ABANDONED,
                MangaState.PAUSED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()

        val statusApi = filter.states.firstOrNull()?.let { stateToApi(it) }

        val typeApi = filter.types.firstOrNull()?.let { typeToApi(it) }

        val genreIds = filter.tags
            .filter { it.key.startsWith("genre_") }
            .map { it.key.removePrefix("genre_") }

        val (orderBy, orderDir) = orderFor(order)

        val baseHttpUrl = "$apiBaseUrl/api/query".toHttpUrlOrNull() ?: return emptyList()
        val builder = baseHttpUrl.newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", pageSize.toString())
            if (query.isNotEmpty()) addQueryParameter("searchTerm", query)
            if (!statusApi.isNullOrEmpty()) addQueryParameter("seriesStatus", statusApi)
            if (!typeApi.isNullOrEmpty()) addQueryParameter("seriesType", typeApi)
            if (genreIds.isNotEmpty()) addQueryParameter("genreIds", genreIds.joinToString(","))
            if (filter.year != YEAR_UNKNOWN) {
                addQueryParameter("createdAfter", "${filter.year}-01-01")
                addQueryParameter("createdBefore", "${filter.year}-12-31")
            }
            addQueryParameter("orderBy", orderBy)
            addQueryParameter("orderDirection", orderDir)
        }

        return fetchSearchPageWithFallback(builder, page)
    }

    protected open fun stateToApi(state: MangaState): String? = when (state) {
        MangaState.ONGOING -> "ONGOING"
        MangaState.FINISHED -> "COMPLETED"
        MangaState.ABANDONED -> "DROPPED"
        MangaState.PAUSED -> "HIATUS"
        else -> null
    }

    protected open fun typeToApi(type: ContentType): String? = when (type) {
        ContentType.MANGA -> "MANGA"
        ContentType.MANHWA -> "MANHWA"
        ContentType.MANHUA -> "MANHUA"
        else -> null
    }

    protected open fun orderFor(order: SortOrder): Pair<String, String> = when (order) {
        SortOrder.POPULARITY -> "totalViews" to "desc"
        SortOrder.UPDATED -> "lastChapterAddedAt" to "desc"
        SortOrder.ADDED -> "createdAt" to "desc"
        SortOrder.ADDED_ASC -> "createdAt" to "asc"
        SortOrder.ALPHABETICAL -> "postTitle" to "asc"
        SortOrder.ALPHABETICAL_DESC -> "postTitle" to "desc"
        else -> "totalViews" to "desc"
    }

    protected suspend fun fetchSearchPageWithFallback(initialBuilder: HttpUrl.Builder, startPage: Int): List<Manga> {
        var current = startPage
        while (true) {
            val url = initialBuilder
                .setQueryParameter("page", current.toString())
                .build()
                .toString()

            val json = webClient.httpGet(url, getRequestHeaders()).parseJson()
            val posts = json.optJSONArray("posts") ?: JSONArray()
            val totalCount = json.optInt("totalCount", 0)
            val mangas = posts.toMangaList()
            val hasNext = totalCount > (current * pageSize)

            if (mangas.isNotEmpty() || !hasNext) return mangas
            current++
        }
    }


    /**
     * Reads a 0..1 rating from an Iken post object.
     *
     * The site stores `averageRating` on a 0..10 scale and tracks the number of
     * votes in `totalRatings`. When nobody has voted yet, the API still returns
     * `averageRating: 10` as a placeholder — so gate on `totalRatings > 0` to
     * avoid showing a fake "perfect" score.
     */
    protected fun JSONObject.parseRating(): Float {
        val total = optInt("totalRatings", 0)
        if (total <= 0) return RATING_UNKNOWN
        val avg = optDouble("averageRating", 0.0)
        if (avg <= 0.0) return RATING_UNKNOWN
        return (avg / 10.0).toFloat().coerceIn(0f, 1f)
    }

    protected fun JSONArray.toMangaList(): List<Manga> {
        val list = mutableListOf<Manga>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            if (obj.optBoolean("isNovel", false)) continue
            list.add(obj.toManga())
        }
        return list
    }

    protected fun JSONObject.toManga(): Manga {
        val id = getInt("id")
        val slug = getString("slug")
        val title = getString("postTitle")
        val cover = optString("featuredImage", "")
        return Manga(
            id = generateUid("$slug#$id"),
            url = "$slug#$id",
            publicUrl = "$baseUrl/series/$slug",
            title = title,
            coverUrl = cover.ifEmpty { null },
            altTitles = emptySet(),
            rating = parseRating(),
            contentRating = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val parts = manga.url.split("#")
        if (parts.size != 2) return manga
        val (slug, idStr) = parts
        val postId = idStr.toIntOrNull() ?: return manga

        val json = webClient.httpGet(
            "$apiBaseUrl/api/post?postSlug=$slug",
            getRequestHeaders(),
        ).parseJson()
        val data = json.optJSONObject("post") ?: return manga
        if (data.optBoolean("isNovel", false)) throw Exception("Novels are unsupported")

        val title = data.optString("postTitle", manga.title)
        val cover = data.optString("featuredImage", "")
        val rawDesc = data.optString("postContent", "")
        val altTitlesRaw = data.optString("alternativeTitles", "")
        val author = data.optString("author", "").takeUnless { it.isEmpty() || it == "null" }
        val artist = data.optString("artist", "").takeUnless { it.isEmpty() || it == "null" }
        val seriesType = data.optString("seriesType", "")
        val seriesStatus = data.optString("seriesStatus", "")
        val genresArr = data.optJSONArray("genres") ?: JSONArray()
        val inlineChapters = data.optJSONArray("chapters") ?: JSONArray()
        val totalChapters = json.optInt("totalChapterCount", inlineChapters.length())

        val state = when (seriesStatus.uppercase()) {
            "ONGOING", "COMING_SOON", "MASS_RELEASED" -> MangaState.ONGOING
            "COMPLETED" -> MangaState.FINISHED
            "CANCELLED", "DROPPED" -> MangaState.ABANDONED
            "HIATUS" -> MangaState.PAUSED
            else -> null
        }
        val altTitles = altTitlesRaw
            .split(',', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val tags = mutableSetOf<MangaTag>()
        if (seriesType.isNotEmpty()) {
            tags.add(MangaTag(seriesType.lowercase(), seriesType, source))
        }
        for (i in 0 until genresArr.length()) {
            val g = genresArr.optJSONObject(i) ?: continue
            val name = g.optString("name", "")
            if (name.isNotEmpty()) tags.add(MangaTag(name.lowercase(), name, source))
        }

        val plainDesc = if (rawDesc.isNotEmpty()) {
            org.jsoup.Jsoup.parse(rawDesc.replace("\n", "<br>")).text()
        } else ""

        val totalViews = data.optInt("totalViews", 0)
        val ratingCount = data.optInt("totalRatings", 0)
        val avgRating = data.optDouble("averageRating", 0.0)

        val fullDesc = buildString {
            append(plainDesc)
            if (altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Names: ${altTitles.joinToString(" | ")}")
            }
            append("\n\n")
            append("Chapters: $totalChapters")
            if (totalViews > 0) append(" • Views: $totalViews")
            if (ratingCount > 0) {
                append(" • Rating: %.2f/10 (%d votes)".format(Locale.US, avgRating, ratingCount))
            }
        }.trim()

        val chapters = if (totalChapters > inlineChapters.length()) {
            fetchChaptersFromApi(postId, slug)
        } else {
            parseChaptersFromArray(inlineChapters, slug)
        }

        return manga.copy(
            title = title,
            altTitles = altTitles,
            coverUrl = cover.ifEmpty { manga.coverUrl },
            largeCoverUrl = cover.ifEmpty { manga.largeCoverUrl },
            description = fullDesc.takeIf { it.isNotEmpty() },
            authors = setOfNotNull(author, artist),
            tags = tags,
            state = state,
            rating = data.parseRating(),
            chapters = chapters,
        )
    }

    protected suspend fun fetchChaptersFromApi(postId: Int, slug: String): List<MangaChapter> {
        val json = webClient.httpGet(
            "$apiBaseUrl/api/chapters?postId=$postId",
            getRequestHeaders(),
        ).parseJson()
        val arr = json.optJSONObject("post")?.optJSONArray("chapters") ?: JSONArray()
        return parseChaptersFromArray(arr, slug)
    }

    protected fun parseChaptersFromArray(arr: JSONArray, mangaSlug: String): List<MangaChapter> {
        val list = mutableListOf<MangaChapter>()
        for (i in 0 until arr.length()) {
            val ch = arr.optJSONObject(i) ?: continue

            val isAccessible = ch.optBoolean("isAccessible", false)
            val isLocked = ch.optBoolean("isLocked", false)
            val isTimeLocked = ch.optBoolean("isTimeLocked", false)
            val price = ch.optInt("price", 0)
            val purchased = ch.optBoolean("chapterPurchased", false)

            val effectivelyLocked = isLocked || isTimeLocked || (!purchased && price != 0)
            if (!isAccessible && !effectivelyLocked) continue

            val id = ch.getInt("id")
            val number = ch.optString("number", "")
            val rawTitle = ch.optString("title", "")
            val slug = ch.optString("slug", "")
            val dateStr = ch.optString("createdAt", "")
            val uploadDate = runCatching { dateFormat.parse(dateStr)?.time ?: 0L }
                .getOrDefault(0L)

            val cleanTitle = rawTitle.takeUnless { it.isEmpty() || it == "null" }
            val prefix = if (effectivelyLocked) "🔒 " else ""
            val fullTitle = buildString {
                append(prefix)
                append("Chapter $number")
                if (cleanTitle != null) append(" - $cleanTitle")
            }

            list.add(
                MangaChapter(
                    id = generateUid("$mangaSlug#$id"),
                    title = fullTitle,
                    number = number.toFloatOrNull() ?: 0f,
                    volume = 0,
                    url = "/series/$mangaSlug/$slug#$id",
                    uploadDate = uploadDate,
                    scanlator = null,
                    branch = null,
                    source = source,
                ),
            )
        }
        return list.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("#")
        if (parts.size != 2) return emptyList()
        val chapterId = parts[1].toIntOrNull() ?: return emptyList()

        val json = webClient.httpGet(
            "$apiBaseUrl/api/chapter?chapterId=$chapterId",
            getRequestHeaders(),
        ).parseJson()
        val chData = json.optJSONObject("chapter") ?: return emptyList()

        if (chData.optBoolean("isShortLinkLocked")) throw Exception("Chapter locked (short link)")
        if (chData.optBoolean("isLockedByCoins")) throw Exception("Chapter locked (coins required)")
        if (chData.optBoolean("isPermanentlyLocked")) throw Exception("Chapter permanently locked")

        val imagesArr = chData.optJSONArray("images") ?: return emptyList()
        val images = mutableListOf<JSONObject>()
        for (i in 0 until imagesArr.length()) {
            imagesArr.optJSONObject(i)?.let { images.add(it) }
        }
        images.sortBy { it.optInt("order", Int.MAX_VALUE) }

        return images.map { img ->
            val imgUrl = img.optString("url", "").replace(" ", "%20")
            MangaPage(
                id = generateUid(imgUrl),
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val postId = seed.url.substringAfterLast('#', "").toIntOrNull() ?: return emptyList()
        val json = webClient.httpGet(
            "$apiBaseUrl/api/recommendations?postId=$postId&limit=25",
            getRequestHeaders(),
        ).parseJson()
        val recs = json.optJSONArray("recommendations") ?: return emptyList()
        return recs.toMangaList()
    }
}
