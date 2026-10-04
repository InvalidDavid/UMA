package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

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
import tsuki.util.urlEncoded

import java.time.Instant
import java.util.EnumSet
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

@MangaSourceParser("KAWIIMANGA", "Kawaii Manga", "ar")
internal class KawiiManga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAWIIMANGA, 20) {

    override val configKeyDomain = ConfigKey.Domain("kawaiimanga.org")

    private val apiBaseUrl = "https://manga-api.kawaii-anime.com/api/manga"
    private val apiUrl = "$apiBaseUrl/own"

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("x-app-key", "km_2026_live")
        .build()

    private var token: String? = null
    private var tokenExpiry = 0L
    private val tokenMutex = Mutex()

    private suspend fun getValidToken(): String = tokenMutex.withLock {
        val cached = token
        if (cached != null && System.currentTimeMillis() < tokenExpiry) {
            return@withLock cached
        }
        val response = webClient.httpGet("$apiBaseUrl/token", getRequestHeaders())
        val body = response.body?.string()
            ?: throw ParseException("Empty token response", "$apiBaseUrl/token")
        val json = JSONObject(body)
        val newToken = json.getString("token")
        val expiresIn = json.optInt("expiresIn", 3600)
        token = newToken
        /**
         * Refresh 2 minutes early; clamp to at least 1 minute in case server sends tiny TTLs.
         */
        tokenExpiry = System.currentTimeMillis() +
                (expiresIn - 120).coerceAtLeast(60) * 1000L
        newToken
    }

    private suspend fun authHeaders(): Headers = getRequestHeaders().newBuilder()
        .set("x-app-token", getValidToken())
        .build()

    /**
     * GET with auth headers. On 401, invalidates the cached token and retries once.
     */
    private suspend fun apiGet(url: String): Response {
        val response = webClient.httpGet(url, authHeaders())
        if (response.code != 401) return response

        response.close()
        tokenMutex.withLock {
            token = null
            tokenExpiry = 0L
        }
        return webClient.httpGet(url, authHeaders())
    }

    private fun Response.parseJsonObject(): JSONObject {
        val body = body?.string()
            ?: throw ParseException("Empty response body", request.url.toString())
        return JSONObject(body)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query.orEmpty()
        val url = if (query.isNotBlank()) {
            "$apiUrl?action=search&q=${query.urlEncoded()}"
        } else {
            when (order) {
                SortOrder.POPULARITY -> "$apiUrl?action=browse&page=$page&sort=views"
                else -> "$apiUrl?action=browse&page=$page"
            }
        }
        return parseMangaList(apiGet(url).parseJsonObject())
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val slug = manga.url
        val json = apiGet("$apiUrl?action=series&slug=$slug").parseJsonObject()
        val detailedManga = parseManga(json)
        val chapters = parseChapters(json.optJSONArray("chapters"), slug)
        detailedManga.copy(chapters = chapters)
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast('#', "")
        if (chapterId.isEmpty()) return emptyList()

        val json = apiGet("$apiUrl?action=pages&chapterId=$chapterId").parseJsonObject()
        val pagesArray = json.optJSONArray("pages") ?: return emptyList()

        return (0 until pagesArray.length()).map { i ->
            val url = pagesArray.getString(i)
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseMangaList(json: JSONObject): List<Manga> {
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            parseManga(results.getJSONObject(i))
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        optString(key).takeIf {
            it.isNotBlank() && !it.equals("unknown", ignoreCase = true)
        }

    private fun parseManga(obj: JSONObject): Manga {
        val slug = obj.getString("slug")
        val title = obj.getString("title")
        val cover = obj.optStringOrNull("coverUrl")
        val author = obj.optStringOrNull("author")
        val artist = obj.optStringOrNull("artist")
        val description = obj.optString("description").takeIf { it.isNotBlank() }
        val type = obj.optString("type")
        val status = obj.optString("status")
        val genresArray = obj.optJSONArray("genres")

        val tags = buildSet {
            when (type) {
                "manga" -> add(MangaTag("manga", "Manga", source))
                "manhua" -> add(MangaTag("manhua", "Manhua", source))
                "manhwa" -> add(MangaTag("manhwa", "Manhwa", source))
            }
            if (genresArray != null) {
                for (i in 0 until genresArray.length()) {
                    val genre = genresArray.getString(i)
                    add(MangaTag(genre.lowercase(), genre, source))
                }
            }
        }

        val state = when (status) {
            "ongoing", "coming_soon" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "cancelled", "dropped" -> MangaState.ABANDONED
            else -> null
        }

        return Manga(
            id = generateUid(slug),
            url = slug,
            publicUrl = "https://$domain/manga/$slug",
            title = title,
            altTitles = emptySet(),
            authors = setOfNotNull(author, artist),
            coverUrl = cover,
            rating = RATING_UNKNOWN,
            tags = tags,
            state = state,
            description = description,
            contentRating = null,
            source = source,
        )
    }

    private fun parseChapters(jsonArray: JSONArray?, slug: String): List<MangaChapter> {
        if (jsonArray == null) return emptyList()
        return (0 until jsonArray.length()).map { i ->
            val obj = jsonArray.getJSONObject(i)
            val id = obj.getString("id")
            val title = obj.optString("title")
            val number = obj.getInt("number")
            val createdAt = obj.optString("createdAt")

            val baseName = "الفصل $number"
            val chapterName = when {
                title.isBlank() -> baseName
                title == number.toString() -> baseName
                title.equals(baseName, ignoreCase = true) -> baseName
                else -> "$baseName - $title"
            }

            val date = runCatching { Instant.parse(createdAt).toEpochMilli() }
                .getOrDefault(0L)

            MangaChapter(
                id = generateUid("$slug/$number#$id"),
                url = "$slug/$number#$id",
                title = chapterName,
                number = number.toFloat(),
                volume = 0,
                uploadDate = date,
                scanlator = null,
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }
    }
}
