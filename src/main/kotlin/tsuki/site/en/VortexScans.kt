package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.OkHttpWebClient

import tsuki.model.*
import tsuki.util.*

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("VORTEXSCANS", "Vortex Scans", "en")
internal class VortexScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.VORTEXSCANS, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("vortexscans.org")
    private val baseUrl = "https://$domain"
    private val apiBaseUrl = "https://api.$domain"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override val webClient by lazy {
        OkHttpWebClient(context.httpClient.newBuilder().build(), source)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "application/json")
        .build()

    private var cachedGenres: List<Pair<String, String>>? = null

    private suspend fun fetchGenres(): List<Pair<String, String>> {
        if (cachedGenres != null) return cachedGenres!!
        val json = webClient.httpGet("$apiBaseUrl/api/genres", getRequestHeaders()).parseJson()
        val arr = json.optJSONArray("data") ?: json.optJSONArray("genres") ?: return emptyList()
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(obj.optString("name") to obj.optString("id"))
        }
        cachedGenres = list
        return list
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.ADDED,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = fetchGenres()
        val tags = mutableSetOf<MangaTag>()

        tags.add(MangaTag("Ongoing", "status_ongoing", source))
        tags.add(MangaTag("Completed", "status_completed", source))
        tags.add(MangaTag("Canceled", "status_canceled", source))
        tags.add(MangaTag("Dropped", "status_dropped", source))
        tags.add(MangaTag("Coming Soon", "status_coming_soon", source))
        tags.add(MangaTag("Mass Released", "status_mass_released", source))

        tags.add(MangaTag("Manga", "type_manga", source))
        tags.add(MangaTag("Manhua", "type_manhua", source))
        tags.add(MangaTag("Manhwa", "type_manhwa", source))
        tags.add(MangaTag("Russian", "type_russian", source))
        tags.add(MangaTag("Spanish", "type_spanish", source))

        genres.forEach { (name, id) ->
            tags.add(MangaTag(name, "genre_$id", source))
        }

        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = emptySet(),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val status = filter.tags.firstOrNull { it.key.startsWith("status_") }?.key?.removePrefix("status_")
        val type = filter.tags.firstOrNull { it.key.startsWith("type_") }?.key?.removePrefix("type_")
        val genreIds = filter.tags.filter { it.key.startsWith("genre_") }.map { it.key.removePrefix("genre_") }

        val (orderBy, orderDir) = when (order) {
            SortOrder.POPULARITY    -> "totalViews" to "desc"
            SortOrder.UPDATED       -> "lastChapterAddedAt" to "desc"
            SortOrder.ADDED         -> "createdAt" to "desc"
            SortOrder.ALPHABETICAL  -> "postTitle" to "asc"
            else                    -> "totalViews" to "desc"
        }

        val baseHttpUrl = "$apiBaseUrl/api/query".toHttpUrlOrNull() ?: return emptyList()
        val urlBuilder = baseHttpUrl.newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", pageSize.toString())
            if (query.isNotEmpty()) addQueryParameter("searchTerm", query)
            if (!status.isNullOrEmpty()) addQueryParameter("seriesStatus", status.uppercase())
            if (!type.isNullOrEmpty()) addQueryParameter("seriesType", type.uppercase())
            if (genreIds.isNotEmpty()) addQueryParameter("genreIds", genreIds.joinToString(","))
            addQueryParameter("orderBy", orderBy)
            addQueryParameter("orderDirection", orderDir)
        }

        val (mangas, _) = fetchSearchPageWithFallback(urlBuilder, page)
        return mangas
    }

    private suspend fun fetchSearchPageWithFallback(
        initialUrlBuilder: HttpUrl.Builder,
        startPage: Int
    ): Pair<List<Manga>, Boolean> {
        var currentPage = startPage
        while (true) {
            val url = initialUrlBuilder
                .setQueryParameter("page", currentPage.toString())
                .build()
                .toString()

            val json = webClient.httpGet(url, getRequestHeaders()).parseJson()
            val posts = json.optJSONArray("posts") ?: JSONArray()
            val totalCount = json.optInt("totalCount", 0)

            val mangas = mutableListOf<Manga>()
            for (i in 0 until posts.length()) {
                val obj = posts.optJSONObject(i) ?: continue
                if (obj.optBoolean("isNovel", false)) continue
                mangas.add(obj.toManga())
            }

            val hasNext = totalCount > (currentPage * pageSize)

            if (mangas.isNotEmpty() || !hasNext) {
                return mangas to hasNext
            }
            currentPage++
        }
    }

    private fun JSONObject.toManga(): Manga {
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
            rating = RATING_UNKNOWN,
            contentRating = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val (slug, id) = manga.url.split("#")
        val detailsUrl = "$apiBaseUrl/api/post?postSlug=$slug"
        val json = webClient.httpGet(detailsUrl, getRequestHeaders()).parseJson()
        val data = json.optJSONObject("post") ?: return manga
        if (data.optBoolean("isNovel", false)) throw Exception("Novels not supported")

        val title = data.optString("postTitle", manga.title)
        val cover = data.optString("featuredImage", "")
        val desc = data.optString("postContent", "")
        val altTitles = data.optString("alternativeTitles", "")
        val author = data.optString("author", "")
        val artist = data.optString("artist", "")
        val seriesType = data.optString("seriesType", "")
        val seriesStatus = data.optString("seriesStatus", "")
        val genresArr = data.optJSONArray("genres") ?: JSONArray()
        val chaptersArr = data.optJSONArray("chapters") ?: JSONArray()
        val totalChapters = json.optInt("totalChapterCount", chaptersArr.length())

        val state = when (seriesStatus.uppercase()) {
            "ONGOING", "COMING_SOON", "MASS_RELEASED" -> MangaState.ONGOING
            "COMPLETED" -> MangaState.FINISHED
            "CANCELLED", "DROPPED" -> MangaState.ABANDONED
            else -> null
        }

        val tags = mutableSetOf<MangaTag>()
        if (seriesType.isNotEmpty()) {
            tags.add(MangaTag(seriesType.lowercase(), seriesType, source))
        }
        for (i in 0 until genresArr.length()) {
            val g = genresArr.optJSONObject(i) ?: continue
            val name = g.optString("name", "")
            if (name.isNotEmpty()) tags.add(MangaTag(name.lowercase(), name, source))
        }

        val plainDesc = if (desc.isNotEmpty()) {
            org.jsoup.Jsoup.parse(desc.replace("\n", "<br>")).text()
        } else ""
        val fullDesc = buildString {
            append(plainDesc)
            if (altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Names: $altTitles")
            }
        }.trim()

        val chapters = if (totalChapters > chaptersArr.length()) {
            fetchChaptersFromApi(id.toInt(), slug)
        } else {
            parseChaptersFromArray(chaptersArr, slug)
        }

        return manga.copy(
            title = title,
            coverUrl = cover.ifEmpty { manga.coverUrl },
            description = fullDesc.takeIf { it.isNotEmpty() },
            authors = setOfNotNull(author.takeIf { it.isNotEmpty() }, artist.takeIf { it.isNotEmpty() }),
            tags = tags,
            state = state,
            chapters = chapters,
        )
    }

    private suspend fun fetchChaptersFromApi(postId: Int, slug: String): List<MangaChapter> {
        val url = "$apiBaseUrl/api/chapters?postId=$postId"
        val json = webClient.httpGet(url, getRequestHeaders()).parseJson()
        val chaptersArr = json.optJSONObject("post")?.optJSONArray("chapters") ?: JSONArray()
        return parseChaptersFromArray(chaptersArr, slug)
    }

    private fun parseChaptersFromArray(arr: JSONArray, mangaSlug: String): List<MangaChapter> {
        val list = mutableListOf<MangaChapter>()
        for (i in 0 until arr.length()) {
            val ch = arr.optJSONObject(i) ?: continue
            if (!ch.optBoolean("isAccessible", false)) continue
            val id = ch.getInt("id")
            val number = ch.optString("number", "")
            val title = ch.optString("title", "")
            val slug = ch.optString("slug", "")
            val dateStr = ch.optString("createdAt", "")
            val uploadDate = try {
                dateFormat.parse(dateStr)?.time ?: 0L
            } catch (_: Exception) { 0L }

            val cleanTitle = if (title == "null" || title.isEmpty()) null else title
            val fullTitle = buildString {
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
                )
            )
        }
        return list.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("#")
        if (parts.size != 2) return emptyList()
        val chapterId = parts[1].toIntOrNull() ?: return emptyList()
        val json = webClient.httpGet("$apiBaseUrl/api/chapter?chapterId=$chapterId", getRequestHeaders()).parseJson()
        val chData = json.optJSONObject("chapter") ?: return emptyList()
        if (chData.optBoolean("isShortLinkLocked")) throw Exception("Chapter locked (short link)")
        if (chData.optBoolean("isLockedByCoins")) throw Exception("Chapter locked (coins required)")
        if (chData.optBoolean("isPermanentlyLocked")) throw Exception("Chapter permanently locked")

        val imagesArr = chData.optJSONArray("images") ?: return emptyList()
        val pages = mutableListOf<JSONObject>()
        for (i in 0 until imagesArr.length()) {
            pages.add(imagesArr.optJSONObject(i) ?: continue)
        }
        pages.sortBy { it.optInt("order", Int.MAX_VALUE) }

        return pages.mapIndexed { _, imgObj ->
            val imgUrl = imgObj.optString("url", "").replace(" ", "%20")
            MangaPage(
                id = generateUid(imgUrl),
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val (_, id) = seed.url.split("#")
        val postId = id.toIntOrNull() ?: return emptyList()
        val json = webClient.httpGet("$apiBaseUrl/api/recommendations?postId=$postId&limit=25", getRequestHeaders()).parseJson()
        val recs = json.optJSONArray("recommendations") ?: return emptyList()
        val mangas = mutableListOf<Manga>()
        for (i in 0 until recs.length()) {
            val obj = recs.optJSONObject(i) ?: continue
            if (obj.optBoolean("isNovel", false)) continue
            mangas.add(obj.toManga())
        }
        return mangas
    }
}
