package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.OkHttpWebClient
import tsuki.model.*
import tsuki.util.*

import okhttp3.Headers
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

abstract class HiperParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    protected val domainName: String,
) : PagedMangaParser(context, source, pageSize = 30) {

    protected open val availableContentTypes: Set<ContentType> = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA)
    protected open val availableStates: Set<MangaState> = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED)
    protected open val availableContentRating: Set<ContentRating> = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT)
    protected open val mangaPath = "manga"

    override val configKeyDomain = ConfigKey.Domain(domainName)
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.NEWEST_ASC,
        SortOrder.ALPHABETICAL
    )
    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true,
    )

    val client: OkHttpWebClient by lazy {
        val httpClient = context.httpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val newRequest = request.newBuilder()
                    .header("Referer", "https://$domainName/")
                    .header("Origin", "https://$domainName")
                    .build()
                var response = chain.proceed(newRequest)
                if (response.code == 401) {
                    response.close()
                    val acceptHeaders = Headers.Builder()
                        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build()
                    val homeRequest = Request.Builder()
                        .url("https://$domainName/")
                        .headers(acceptHeaders)
                        .build()
                    chain.proceed(homeRequest).close()
                    response = chain.proceed(request)
                }
                response
            }
            .build()
        OkHttpWebClient(httpClient, source)
    }

    @Volatile
    private var genresListCache: List<MangaTag>? = null

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        if (genresListCache == null) {
            try {
                val input = "%7B%220%22%3A%7B%22json%22%3Anull%2C%22meta%22%3A%7B%22values%22%3A%5B%22undefined%22%5D%7D%7D%7D"
                val res = client.httpGet(apiUrl("search.genres", input), getRequestHeaders())
                val body = res.body?.string()
                if (body != null) {
                    val root = JSONArray(body)
                    val jsonArray = root.optJSONObject(0)
                        ?.optJSONObject("result")
                        ?.optJSONObject("data")
                        ?.optJSONArray("json")
                    if (jsonArray != null) {
                        val tags = mutableListOf<MangaTag>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.optJSONObject(i) ?: continue
                            val name = obj.optString("name", "")
                            if (name.isNotEmpty()) tags.add(MangaTag(name, name, source))
                        }
                        if (tags.isNotEmpty()) genresListCache = tags
                    }
                }
            } catch (_: Exception) {}
        }
        return MangaListFilterOptions(
            availableTags = genresListCache?.toSet() ?: emptySet(),
            availableContentTypes = availableContentTypes,
            availableStates = availableStates,
            availableContentRating = availableContentRating,
        )
    }

    private fun apiUrl(trpcPath: String, input: String) = "https://$domainName/api/trpc/$trpcPath?batch=1&input=${input.urlEncoded()}"

    private fun searchPayload(query: String, sort: String, limit: Int, offset: Int, filter: MangaListFilter): String {
        val genres = filter.tags.map { it.key }
        val typeValue = filter.types.firstOrNull()?.name?.lowercase()
        val statusValue = filter.states.firstOrNull()?.let {
            when (it) {
                MangaState.ONGOING -> "ongoing"
                MangaState.FINISHED -> "completed"
                MangaState.PAUSED -> "hiatus"
                MangaState.ABANDONED -> "cancelled"
                else -> null
            }
        }
        val ratingValue = filter.contentRating.firstOrNull()?.let {
            when (it) {
                ContentRating.SAFE -> "safe"
                ContentRating.SUGGESTIVE -> "suggestive"
                ContentRating.ADULT -> "pornographic"
            }
        }

        return JSONObject().apply {
            put("0", JSONObject().apply {
                put("json", JSONObject().apply {
                    put("q", query)
                    put("sort", sort)
                    put("filters", JSONObject().apply {
                        if (genres.isEmpty()) put("genres", JSONObject.NULL)
                        else put("genres", JSONArray(genres))
                        put("type", typeValue)
                        put("status", statusValue)
                        put("contentRating", ratingValue)
                        put("author", JSONObject.NULL)
                        put("artist", JSONObject.NULL)
                        put("year", JSONObject.NULL)
                    })
                    put("limit", limit)
                    put("offset", offset)
                    put("maxRating", "pornographic")
                })
                put("meta", JSONObject().apply {
                    put("values", JSONObject().apply {
                        if (genres.isEmpty()) put("filters.genres", JSONArray().put("undefined"))
                        if (typeValue == null) put("filters.type", JSONArray().put("undefined"))
                        if (statusValue == null) put("filters.status", JSONArray().put("undefined"))
                        if (ratingValue == null) put("filters.contentRating", JSONArray().put("undefined"))
                        put("filters.author", JSONArray().put("undefined"))
                        put("filters.artist", JSONArray().put("undefined"))
                        put("filters.year", JSONArray().put("undefined"))
                    })
                })
            })
        }.toString()
    }

    private fun detailsPayload(slug: String) = JSONObject().apply {
        put("0", JSONObject().apply {
            put("json", JSONObject.NULL)
            put("meta", JSONObject().apply { put("values", JSONArray().put("undefined")) })
        })
        put("1", JSONObject().apply {
            put("json", JSONObject().apply { put("slug", slug) })
        })
    }.toString()

    private fun chaptersPayload(mangaId: Long) = JSONObject().apply {
        put("0", JSONObject().apply {
            put("json", JSONObject().apply { put("values", JSONArray().put("undefined")) })
        })
        put("1", JSONObject().apply {
            put("json", JSONObject().apply {
                put("seriesId", mangaId)
                put("chapterId", JSONObject.NULL)
                put("sort", "best")
                put("page", 1)
                put("limit", 9999)
            })
            put("meta", JSONObject().apply {
                put("values", JSONObject().apply { put("chapterId", JSONArray().put("undefined")) })
            })
        })
        put("2", JSONObject().apply {
            put("json", JSONObject().apply { put("seriesId", mangaId) })
        })
    }.toString()

    private fun pagesPayload(slug: String, number: Float) = JSONObject().apply {
        put("0", JSONObject().apply {
            put("json", JSONObject.NULL)
            put("meta", JSONObject().apply { put("values", JSONArray().put("undefined")) })
        })
        put("1", JSONObject().apply {
            put("json", JSONObject().apply { put("slug", slug) })
        })
        put("2", JSONObject().apply {
            put("json", JSONObject().apply {
                put("seriesSlug", slug)
                put("chapterNumber", number.toDouble())
            })
        })
        put("3", JSONObject().apply {
            put("json", JSONObject().apply { put("position", "footer_bottom") })
        })
    }.toString()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val q = filter.query?.trim().orEmpty()
        val sort = when (order) {
            SortOrder.RELEVANCE -> "relevance"
            SortOrder.POPULARITY -> "popular"
            SortOrder.RATING -> "score"
            SortOrder.UPDATED -> "recent"
            SortOrder.NEWEST -> "newest"
            SortOrder.NEWEST_ASC -> "oldest"
            SortOrder.ALPHABETICAL -> "alphabetical"
            else -> "relevance"
        }
        val input = searchPayload(q, sort, pageSize, (page - 1) * pageSize, filter)
        val res = client.httpGet(apiUrl("search.query", input), getRequestHeaders())
        val body = res.body?.string() ?: return emptyList()
        val root = JSONArray(body)
        val first = root.optJSONObject(0) ?: return emptyList()
        val hits = first.optJSONObject("result")
            ?.optJSONObject("data")
            ?.optJSONObject("json")
            ?.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            hits.optJSONObject(i)?.let { obj ->
                val id = obj.optLong("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val slug = obj.optString("slug", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val title = obj.optString("title", "")
                val cover = obj.optString("coverUrl", "")
                Manga(
                    id = generateUid("/$mangaPath/$slug#$id"),
                    url = "/$mangaPath/$slug#$id",
                    publicUrl = "https://$domainName/$mangaPath/$slug#$id",
                    title = title,
                    coverUrl = cover,
                    altTitles = emptySet(),
                    authors = emptySet(),
                    tags = emptySet(),
                    rating = RATING_UNKNOWN,
                    state = null,
                    contentRating = ContentRating.ADULT,
                    source = source
                )
            }
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfterLast("$mangaPath/").substringBefore("#")
            .takeIf { it.isNotBlank() } ?: return manga
        val res = client.httpGet(apiUrl("auth.me,series.bySlugWithGenres", detailsPayload(slug)), getRequestHeaders())
        val body = res.body?.string() ?: return manga
        val detailJson = try {
            val arr = JSONArray(body)
            arr.getJSONObject(arr.length() - 1)
                .optJSONObject("result")?.optJSONObject("data")?.optJSONObject("json")
        } catch (_: Exception) { null } ?: return manga

        val title = detailJson.optString("title", manga.title)
        val desc = detailJson.optString("synopsis", manga.description)
        val cover = detailJson.optString("coverUrl", manga.coverUrl)
        val authors = detailJson.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        val genres = detailJson.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        val status = detailJson.optString("status", null)
        val chapters = loadChapters(manga)

        return manga.copy(
            title = title, description = desc, coverUrl = cover,
            authors = authors.toSet(),
            tags = genres.map { MangaTag(it.lowercase(), it, source) }.toSet(),
            state = when (status?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "hiatus" -> MangaState.PAUSED
                "cancelled" -> MangaState.ABANDONED
                else -> null
            },
            chapters = chapters,
        )
    }

    private suspend fun loadChapters(manga: Manga): List<MangaChapter> {
        val mangaId = manga.url.substringAfterLast("#").toLongOrNull() ?: return emptyList()
        val slug = manga.url.substringAfterLast("$mangaPath/").substringBefore("#").ifBlank { return emptyList() }
        val res = client.httpGet(apiUrl("auth.me,series.chapters", chaptersPayload(mangaId)), getRequestHeaders())
        val body = res.body?.string() ?: return emptyList()
        val chaptersArray = parseLastItemJsonArray(body) ?: return emptyList()
        return (0 until chaptersArray.length()).mapNotNull { i ->
            chaptersArray.optJSONObject(i)?.let { obj ->
                val number = obj.optDouble("number", 0.0).toFloat()
                val title = obj.optString("title", null)
                val createdAt = obj.optString("createdAt", null)
                val uploadDate = createdAt?.let { dateFormat.parse(it)?.time } ?: 0L
                val displayTitle = when {
                    !title.isNullOrBlank() && title != "null" && !Regex("""\d+""").containsMatchIn(title) ->
                        "Chapter ${number.formatForTitle()} $title"
                    else -> "Chapter ${number.formatForTitle()}"
                }
                MangaChapter(
                    id = generateUid("/$mangaPath/$slug/$number"),
                    url = "/$mangaPath/$slug/$number",
                    title = displayTitle,
                    number = number, volume = 0, uploadDate = uploadDate,
                    scanlator = null, branch = null, source = source
                )
            }
        }.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val path = chapter.url.removePrefix("/$mangaPath/")
        val slug = path.substringBefore("/")
        val num = path.substringAfter("/").toFloatOrNull() ?: return emptyList()

        val input = pagesPayload(slug, num)
        val res = client.httpGet(apiUrl("auth.me,series.bySlug,reader.chapterPages", input), getRequestHeaders())
        val body = res.body?.string() ?: return emptyList()
        val apiPages = parsePages(body)
        if (!apiPages.isNullOrEmpty()) return apiPages

        val chapterUrl = "https://$domainName/$mangaPath/$slug/$num"
        val doc = client.httpGet(chapterUrl).parseHtml()

        val script = doc.select("script").find { it.data().contains("\"webpUrl\"") }
            ?: doc.select("script").find { it.data().contains("\"avifUrl\"") }
        if (script != null) {
            val arrayRegex = Regex("""\[\s*\{.*?\"(?:webp|avif)Url\".*?\}\s*\]""", RegexOption.DOT_MATCHES_ALL)
            val match = arrayRegex.find(script.data())?.value
            if (match != null) {
                return try {
                    val jsonArray = JSONArray(match)
                    (0 until jsonArray.length()).mapNotNull { i ->
                        val page = jsonArray.optJSONObject(i) ?: return@mapNotNull null
                        val url = page.optString("webpUrl", "").ifEmpty {
                            page.optString("avifUrl", "")
                        }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        MangaPage(id = generateUid(url), url = url, preview = null, source = source)
                    }
                } catch (_: Exception) { emptyList() }
            }
        }

        return emptyList()
    }

    private fun parsePages(body: String): List<MangaPage>? {
        val elements = try { JSONArray(body) } catch (_: Exception) { return null }
        for (i in 0 until elements.length()) {
            if (elements.optJSONObject(i)?.has("error") == true) return null
        }
        val last = elements.optJSONObject(elements.length() - 1) ?: return null
        val items = last.optJSONObject("result")
            ?.optJSONObject("data")
            ?.optJSONArray("json") ?: return null
        return (0 until items.length()).mapNotNull { i ->
            val page = items.optJSONObject(i) ?: return@mapNotNull null
            val url = page.optString("webpUrl", "").ifEmpty {
                page.optString("avifUrl", "")
            }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    private fun parseLastItemJsonArray(body: String): JSONArray? = try {
        val root = JSONArray(body)
        root.optJSONObject(root.length() - 1)
            ?.optJSONObject("result")
            ?.optJSONObject("data")
            ?.optJSONArray("json")
    } catch (_: Exception) { null }

    override suspend fun getPageUrl(page: MangaPage) = page.url

    private fun Float.formatForTitle() = if (this == toLong().toFloat()) toLong().toString() else toString()

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    }
}
