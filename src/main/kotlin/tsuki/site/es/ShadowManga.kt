package tsuki.site.es

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

@MangaSourceParser("SHADOWMANGA", "Shadow Manga", "es")
internal class ShadowManga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SHADOWMANGA, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("shademanga.com")
    private val baseApi = "https://$domain/api/series-locales"

    private val showAdultKey = ConfigKey.ShowSuspiciousContent(false)

    private val cdnHosts = listOf("media.shademanga.com", "cdn.shademanga.com")
    private val fallbackPrefix = "/api/media/"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", sourceLocale)

    private val rateLimiter = Mutex()
    override val webClient by lazy {
        tsuki.network.OkHttpWebClient(
            context.httpClient.newBuilder()
                .addInterceptor { chain ->
                    if (chain.request().url.host == domain) {
                        runBlocking { rateLimiter.withLock { delay(333.milliseconds) } }
                    }
                    chain.proceed(chain.request())
                }
                .build(),
            source
        )
    }

    override fun getRequestHeaders() = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(showAdultKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
    )

    @Volatile private var genreCache: Set<MangaTag>? = null
    private val genreMutex = Mutex()

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchGenres(),
    )

    private suspend fun fetchGenres(): Set<MangaTag> {
        genreCache?.let { return it }
        return genreMutex.withLock {
            genreCache?.let { return it }
            try {
                val bodyString = webClient.httpGet("$baseApi/tags", getRequestHeaders()).body?.string()
                    ?: return@withLock emptySet()
                val arr = JSONArray(bodyString)
                val tags = LinkedHashSet<MangaTag>(arr.length())
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i, "").trim()
                    if (name.isNotEmpty()) tags.add(MangaTag(title = name, key = name, source = source))
                }
                genreCache = tags
                tags
            } catch (index: Exception) {
                throw Exception(index)
            }
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val adultParam = if (config[showAdultKey]) "true" else "false"
        if (page > 1) return emptyList()

        val query = filter.query?.trim().orEmpty()
        val includedTags = filter.tags.map { it.key }
        val excludedTags = filter.tagsExclude.map { it.key }

        val url: String
        val isSearch = query.isNotEmpty() || includedTags.isNotEmpty() || excludedTags.isNotEmpty()

        when {
            isSearch -> {
                val builder = "$baseApi/search-candidates".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("includeAdult", adultParam)
                    .addQueryParameter("showSinPortada", "false")
                    .addQueryParameter("take", "120")
                includedTags.forEach { builder.addQueryParameter("tags", it) }
                url = builder.build().toString()
            }
            order == SortOrder.POPULARITY -> url = "$baseApi/popular"
            else -> url = "$baseApi/novedades"
        }

        // Fetch and manually parse as JSONArray
        val bodyString = webClient.httpGet(url, getRequestHeaders()).body?.string()
            ?: return emptyList()
        val rawJson = runCatching { JSONArray(bodyString) }.getOrNull()
            ?: return emptyList()

        val allSeries = if (!isSearch) {
            // Auto‑detect format for popular/novedades
            val isGenreWrapped = rawJson.length() > 0 && rawJson.optJSONObject(0)?.has("genero") == true
            if (isGenreWrapped) {
                val seen = HashSet<Int>()
                val seriesList = mutableListOf<JSONObject>()
                for (i in 0 until rawJson.length()) {
                    val group = rawJson.optJSONObject(i) ?: continue
                    val seriesArr = group.optJSONArray("series") ?: continue
                    for (j in 0 until seriesArr.length()) {
                        seriesArr.optJSONObject(j)?.let { series ->
                            val id = series.optInt("id", -1)
                            if (id > 0 && seen.add(id)) seriesList.add(series)
                        }
                    }
                }
                seriesList
            } else {
                (0 until rawJson.length()).mapNotNull { rawJson.optJSONObject(it) }
            }
        } else {
            // Search always returns a flat array
            (0 until rawJson.length()).mapNotNull { rawJson.optJSONObject(it) }
        }

        val filtered = if (excludedTags.isNotEmpty() || includedTags.isNotEmpty()) {
            allSeries.filter { series ->
                val genres = series.optString("generos", "").split(",").map { it.trim() }
                val includeOk = includedTags.isEmpty() || genres.any { it in includedTags }
                val excludeOk = excludedTags.isEmpty() || genres.none { it in excludedTags }
                includeOk && excludeOk
            }
        } else allSeries

        val sorted = if (isSearch) {
            filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.optString("titulo", "") })
        } else filtered

        return sorted.map { parseMangaFromSeries(it) }
    }

    private fun parseMangaFromSeries(obj: JSONObject): Manga {
        val id = obj.optInt("id")
        val title = obj.optString("titulo", "")
        val cover = obj.optString("portadaUrl", "").takeIf { it.isNotBlank() }
        return Manga(
            id = generateUid(id.toString()),
            title = title,
            altTitles = emptySet(),
            url = id.toString(),
            publicUrl = "https://$domain/serie/local/$id",
            coverUrl = cover,
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url
        val json = webClient.httpGet("$baseApi/$id", getRequestHeaders()).parseJson()

        val title = json.optString("titulo", manga.title)
        val cover = json.optString("portadaUrl", "").takeIf { it.isNotBlank() } ?: manga.coverUrl
        val description = json.optString("descripcion", "").takeIf { it.isNotBlank() }
        val author = json.optString("autor", "").takeIf { it.isNotBlank() }
        val statusText = json.optString("estado", "")
        val state = when (statusText.lowercase()) {
            "en curso" -> MangaState.ONGOING
            "completado" -> MangaState.FINISHED
            "pausada" -> MangaState.PAUSED
            else -> null
        }
        val genres = json.optString("generos", "").split(",").map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { MangaTag(title = it, key = it, source = source) }
            .toSet()

        val chapters = json.optJSONArray("capitulos")?.let { parseChapters(it, id) } ?: emptyList()

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            authors = setOfNotNull(author),
            state = state,
            tags = genres,
            chapters = chapters,
        )
    }

    private fun parseChapters(arr: JSONArray, mangaId: String): List<MangaChapter> {
        val list = mutableListOf<MangaChapter>()
        for (i in 0 until arr.length()) {
            val ch = arr.optJSONObject(i) ?: continue
            val chId = ch.optInt("id")
            val number = ch.optDouble("numeroCapitulo", 0.0).toFloat()
            val title = ch.optString("titulo", "").trim().takeIf { it.isNotEmpty() }
            val dateStr = ch.optString("fechaSubida", "")
            val uploadDate = runCatching { dateFormat.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)

            val displayNumber = if (number == number.toInt().toFloat()) number.toInt().toString() else number.toString()
            val chapterTitle = buildString {
                append("Cap. ")
                append(displayNumber)
                if (title != null) append(" - $title")
            }

            list.add(
                MangaChapter(
                    id = generateUid("$mangaId/$chId"),
                    title = chapterTitle,
                    url = "$mangaId/$chId",
                    number = number,
                    volume = 0,
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
        val parts = chapter.url.split('/')
        if (parts.size != 2) return emptyList()
        val mangaId = parts[0]
        val chapterId = parts[1]

        val json = webClient.httpGet("$baseApi/$mangaId/capitulos/$chapterId/paginas", getRequestHeaders()).parseJson()
        val pagesArr = json.optJSONArray("paginas") ?: return emptyList()

        return (0 until pagesArr.length()).map { i ->
            val originalUrl = pagesArr.optString(i)
            MangaPage(
                id = generateUid(originalUrl),
                url = originalUrl,
                preview = null,
                source = source
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        val originalUrl = page.url
        val response = webClient.httpGet(originalUrl, getRequestHeaders())
        if (response.isSuccessful) {
            response.close()
            return originalUrl
        }
        response.close()
        // CDN fallback
        val url = originalUrl.toHttpUrl()
        val key = when {
            url.encodedPath.startsWith(fallbackPrefix) -> url.encodedPath.removePrefix(fallbackPrefix)
            else -> url.encodedPath.removePrefix("/")
        }
        // Try other CDN hosts
        for (host in cdnHosts) {
            if (host == url.host) continue
            val cdnUrl = "https://$host/$key"
            val cdnResponse = webClient.httpGet(cdnUrl, getRequestHeaders())
            if (cdnResponse.isSuccessful) {
                cdnResponse.close()
                return cdnUrl
            }
            cdnResponse.close()
        }
        // Fallback to main domain API
        val fallbackUrl = "https://$domain$fallbackPrefix$key"
        return fallbackUrl
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
