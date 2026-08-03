package tsuki.site.de

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.network.OkHttpWebClient
import tsuki.util.generateUid
import tsuki.util.parseJson
import tsuki.util.urlEncoded
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.TimeUnit

@MangaSourceParser("MANGATUBE", "Manga Tube", "de")
internal class MangaTube(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGATUBE, 20) {

    override val configKeyDomain = ConfigKey.Domain("manga-tube.me")
    internal val baseUrl = "https://$domain"

    internal val baseClient: OkHttpClient = context.httpClient.newBuilder()
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .writeTimeout(1, TimeUnit.MINUTES)
        .build()

    override val webClient: OkHttpWebClient by lazy {
        val client = baseClient.newBuilder()
            .addInterceptor(ChallengeInterceptor(this))
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", getUserAgent())
                    .build()
                chain.proceed(req)
            }
            .build()
        OkHttpWebClient(client, source)
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    internal fun getUserAgent(): String = config[userAgentKey]

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY
    )
    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)
    override suspend fun getFilterOptions() = MangaListFilterOptions()

    private val apiHeaders: Headers
        get() = Headers.Builder()
            .add("Accept", "application/json")
            .add("Referer", baseUrl)
            .build()

    private fun mangaApiHeaders(slug: String) = apiHeaders.newBuilder()
        .add("Referer", "$baseUrl/series/$slug")
        .add("Use-Parameter", "manga_slug")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()?.takeIf(String::isNotEmpty)
        if (query != null) return searchManga(query)

        return when (order) {
            SortOrder.POPULARITY -> if (page == 1) popularManga() else emptyList()
            SortOrder.UPDATED -> latestUpdates(page)
            else -> emptyList()
        }
    }

    private suspend fun popularManga(): List<Manga> {
        val json = webClient.httpGet("$baseUrl/api/home/top-manga", apiHeaders).parseJson()
        return json?.getJSONObject("data")?.getJSONArray("manga")?.toMangaList().orEmpty()
    }

    private suspend fun latestUpdates(page: Int): List<Manga> {
        val offset = (page - 1) * LATEST_PAGE_SIZE
        val json = webClient.httpGet("$baseUrl/api/home/updates?offset=$offset", apiHeaders).parseJson()
        val published = json?.getJSONObject("data")?.getJSONArray("published") ?: return emptyList()
        val mangas = (0 until published.length()).map { i ->
            published.getJSONObject(i).getJSONObject("manga").toManga()
        }.distinctBy { it.url }
        return if (mangas.size < LATEST_PAGE_SIZE) mangas else mangas
    }

    private suspend fun searchManga(query: String): List<Manga> {
        val json = webClient.httpGet("$baseUrl/api/manga/quick-search?query=${query.urlEncoded()}", apiHeaders).parseJson()
        return json?.getJSONArray("data")?.toMangaList().orEmpty()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfterLast("/")
        val json = webClient.httpGet("$baseUrl/api/manga/$slug", mangaApiHeaders(slug)).parseJson()
        val data = json?.getJSONObject("data")?.getJSONObject("manga") ?: return manga

        val title = data.getString("title")
        val cover = data.optString("cover", manga.coverUrl ?: "")
        val description = data.optString("description", "").ifBlank { null }
        val state = when (data.optInt("status", 0)) { 1 -> MangaState.ONGOING; 2 -> MangaState.FINISHED; else -> null }
        val authors = data.optJSONArray("author")?.toNameList().orEmpty()
        val artists = data.optJSONArray("artist")?.toNameList().orEmpty()

        val chaptersJson = webClient.httpGet("$baseUrl/api/manga/$slug/chapters", mangaApiHeaders(slug)).parseJson()
        val chapters = chaptersJson?.getJSONObject("data")?.getJSONArray("chapters")?.toChapterList(slug).orEmpty()
            .sortedWith(compareBy({ it.volume }, { it.number }))

        return manga.copy(
            title = title,
            coverUrl = cover,
            largeCoverUrl = cover,
            description = description,
            state = state,
            authors = authors + artists,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val slug = chapter.url.substringAfter("/api/manga/").substringBefore("/chapter/")
        val json = webClient.httpGet("$baseUrl${chapter.url}", mangaApiHeaders(slug)).parseJson()
        val pagesArray = json?.getJSONObject("data")?.getJSONObject("chapter")?.getJSONArray("pages") ?: return emptyList()
        val pageList = (0 until pagesArray.length()).map { i ->
            val obj = pagesArray.getJSONObject(i)
            val url = obj.optString("url", "").ifEmpty { obj.optString("alt_source", "") }
            obj.optInt("page", 0) to url
        }.sortedBy { it.first }
        return pageList.mapIndexed { _, (_, url) ->
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private fun JSONObject.toManga(): Manga = Manga(
        id = generateUid(getString("url")),
        url = getString("url"),
        publicUrl = baseUrl + getString("url"),
        title = getString("title"),
        coverUrl = optString("cover", ""),
        altTitles = emptySet(),
        rating = RATING_UNKNOWN,
        contentRating = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
    )

    private fun JSONArray.toMangaList() = (0 until length()).map { getJSONObject(it).toManga() }
    private fun JSONArray.toNameList() = (0 until length()).map { getJSONObject(it).getString("name") }.toSet()

    private fun JSONArray.toChapterList(slug: String) = (0 until length()).map { i ->
        getJSONObject(i).toChapter(slug)
    }

    private fun JSONObject.toChapter(slug: String): MangaChapter {
        val id = getLong("id")
        val number = optDouble("number", 0.0).toFloat()
        val subNumber = optDouble("subNumber", 0.0).toFloat()
        val volume = optDouble("volume", 0.0).toFloat()
        val name = optString("name", "")
        val publishedAt = optString("publishedAt", "")

        val title = buildString {
            if (volume > 0) append("Vol. ${volume.toString().removeSuffix(".0")} ")
            append("Ch. ${number.toString().removeSuffix(".0")}")
            if (subNumber > 0) append(".${subNumber.toString().removeSuffix(".0")}")
            if (name.isNotEmpty()) append(" - $name")
        }

        return MangaChapter(
            id = generateUid("/api/manga/$slug/chapter/$id"),
            title = title,
            number = number + subNumber / 10f,
            volume = volume.toInt(),
            url = "/api/manga/$slug/chapter/$id",
            uploadDate = dateFormat.parseSafe(publishedAt),
            source = source,
            scanlator = null,
            branch = null,
        )
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private fun SimpleDateFormat.parseSafe(date: String): Long =
        runCatching { parse(date)?.time ?: 0L }.getOrDefault(0L)

    companion object {
        private const val LATEST_PAGE_SIZE = 40
    }
}

private class ChallengeInterceptor(
    private val parser: MangaTube,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (originalRequest.header(CHALLENGE_BYPASSED_HEADER) == "1") {
            return chain.proceed(originalRequest)
        }

        if (originalRequest.url.encodedPath.startsWith("/api/") && needsBootstrap(originalRequest.url)) {
            bootstrapSession()
        }

        val response = chain.proceed(originalRequest)
        val bodyString = response.peekBody(Long.MAX_VALUE).string()
        if (!isChallengePage(bodyString)) {
            return response
        }

        response.close()
        solveChallenge(bodyString)

        return chain.proceed(
            originalRequest.newBuilder()
                .header(CHALLENGE_BYPASSED_HEADER, "1")
                .build()
        )
    }

    private fun isChallengePage(body: String): Boolean =
        body.contains("window.__challange") || body.contains("_challange =")

    private fun needsBootstrap(url: HttpUrl): Boolean {
        val cookies = parser.context.cookieJar.loadForRequest(url)
        return REQUIRED_API_COOKIES.any { required -> cookies.none { it.name == required } }
    }

    private fun bootstrapSession() {
        val request = Request.Builder()
            .url(parser.baseUrl)
            .headers(sourceHeaders())
            .build()
        val response = parser.baseClient.newCall(request).execute()
        val bodyString = response.peekBody(Long.MAX_VALUE).string()
        if (isChallengePage(bodyString)) {
            response.close()
            solveChallenge(bodyString)
            parser.baseClient.newCall(request).execute().close()
            return
        }
        response.close()
    }

    private fun solveChallenge(body: String) {
        val regex = Regex("""_challange = (.+?);""")
        val match = regex.find(body)
            ?: throw IOException("Challenge payload not found")

        val json = JSONObject(match.groupValues[1])
        val tk = json.getString("tk")
        val arg1 = json.getString("arg1")
        val arg2 = json.getString("arg2")
        val arg3 = json.getString("arg3")
        val solution = solve(arg1, arg2, arg3)

        val challengeHeaders = sourceHeaders().newBuilder()
            .add("Content-Type", "application/x-www-form-urlencoded")
            .add("x-challange-token", tk)
            .add("x-challange-arg1", arg1)
            .add("x-challange-arg2", arg2)
            .add("x-challange-arg3", arg3)
            .add("x-challange-arg4", solution)
            .build()

        Thread.sleep(1000)

        val postBody = FormBody.Builder().build()
        val postRequest = Request.Builder()
            .url("${parser.baseUrl}/")
            .post(postBody)
            .headers(challengeHeaders)
            .build()

        parser.baseClient.newCall(postRequest).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Challenge validation failed: ${resp.code}")
        }
    }

    private fun solve(arg1: String, arg2: String, op: String): String {
        val left = arg1.toLong(16).toDouble()
        val right = arg2.toLong(16).toDouble()
        val result = when (op) {
            "a" -> left / right
            "b" -> left * right
            "c" -> left - right
            "d" -> left + right
            else -> throw IOException("Unknown challenge op: $op")
        }
        return if (result == result.toLong().toDouble()) result.toLong().toString() else result.toString()
    }

    private fun sourceHeaders(): Headers = Headers.Builder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("User-Agent", parser.getUserAgent())
        .build()

    companion object {
        private val REQUIRED_API_COOKIES = setOf("XSRF-TOKEN", "manga_tube_beta_session", "__mtbpass")
        private const val CHALLENGE_BYPASSED_HEADER = "X-MangaTube-Challenge-Bypassed"
    }
}
