package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaParserAuthProvider
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
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
import tsuki.util.getCookies
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

import tsuki.Broken
@Broken
@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SOFTKOMIK, pageSize = 24), MangaParserAuthProvider {

    override val authUrl: String get() = "https://$domain/akun/login"
    override suspend fun isAuthorized(): Boolean {
        val cookies = context.cookieJar.getCookies(domain)
        return cookies.any { it.name == "tokkey" }
    }
    override suspend fun getUsername(): String = ""

    override val configKeyDomain = ConfigKey.Domain("softkomik.co")

    private val apiUrl = "https://v2.softdevices.my.id"
    private val coverUrl = "https://cover.softdevices.my.id/softkomik-cover"

    private val cdnUrls = listOf(
        "https://psy1.komik.im",
        "https://image.komik.im/softkomik",
        "https://cdn1.softkomik.online/softkomik",
        "https://cd1.softkomik.online/softkomik",
        "https://f1.softkomik.com/file/softkomik-image",
        "https://img.softdevices.my.id/softkomik-image",
        "https://image.softkomik.com/softkomik",
    )

    private val loginRequiredGenres = setOf("ecchi", "mature")
    private val requiredLoginSuffix = "login-required"
    private val requiredLoginFragment = "#$requiredLoginSuffix"

    override val availableSortOrders = setOf(SortOrder.NEWEST, SortOrder.POPULARITY)
    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = false,
    )

    private val customClient: OkHttpClient by lazy {
        context.httpClient.newBuilder()
            .addInterceptor(::apiAuthInterceptor)
            .addInterceptor(::imageInterceptor)
            .build()
    }

    override val webClient by lazy {
        tsuki.network.OkHttpWebClient(customClient, source)
    }

    private fun pageHeaders() = getRequestHeaders().newBuilder()
        .add("rsc", "1")
        .build()

    private fun apiHeaders(session: SessionDto): Headers {
        val builder = Headers.Builder()
            .add("Accept", "application/json")
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
        if (session.sign.isNotEmpty()) {
            builder.add("X-Token", session.token.cleanB64())
            builder.add("X-Sign", session.sign.take(64))
        } else {
            builder.add("Authorization", "Bearer ${session.token}")
        }
        return builder.build()
    }

    private data class SessionDto(val ex: Long, val token: String, val sign: String)
    private val sessionsByKey = ConcurrentHashMap<String, SessionDto>()
    private val sessionKeyChapterList = "chapter-list"
    private val sessionKeyChapterImage = "chapter-image"

    private suspend fun getSessionAsync(route: SessionRoute): SessionDto {
        sessionsByKey[route.key]?.takeIf { it.ex > System.currentTimeMillis() + 30_000L }?.let { return it }

        try {
            val apiHeaders = Headers.Builder()
                .add("Accept", "application/json")
                .add("Referer", "https://$domain/")
                .add("Origin", "https://$domain")
                .add("X-Requested-With", "XMLHttpRequest")
                .build()
            val res = webClient.httpGet(route.sessionApiUrl, apiHeaders).parseJson()
            val rawSign = res.optString("sign", "")
            val session = SessionDto(
                ex = res.optLong("ex", System.currentTimeMillis() + 60_000L),
                token = res.optString("token", ""),
                sign = rawSign.substringBefore('|'),
            )
            sessionsByKey[route.key] = session
            return session
        } catch (_: Exception) {
            val token = getBearerTokenFromCookie()
            if (token != null) {
                val session = SessionDto(
                    ex = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2),
                    token = token,
                    sign = "",
                )
                sessionsByKey[route.key] = session
                return session
            }
            throw Exception("Gagal mendapatkan session. Coba lagi.")
        }
    }

    private fun getSessionBlocking(route: SessionRoute): SessionDto = runBlocking {
        getSessionAsync(route)
    }

    private fun getBearerTokenFromCookie(): String? {
        val cookies = context.cookieJar.getCookies(domain)
        val tokkey = cookies.firstOrNull { it.name == "tokkey" }?.value ?: return null
        val decoded = runCatching { URLDecoder.decode(tokkey, Charsets.UTF_8.name()) }.getOrDefault(tokkey)
        return decoded.cleanB64()
    }

    private fun String.cleanB64(): String =
        substringBefore('=').let { it + "=".repeat((4 - (it.length % 4)) % 4) }

    private fun apiAuthInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.toString().startsWith(apiUrl)) {
            return chain.proceed(request)
        }
        val route = resolveSessionRoute(request.url)
        val session = getSessionBlocking(route)
        val newRequest = request.newBuilder()
            .apply {
                if (session.sign.isNotEmpty()) {
                    header("X-Token", session.token.cleanB64())
                    header("X-Sign", session.sign.take(64))
                } else {
                    header("Authorization", "Bearer ${session.token}")
                }
            }
            .build()
        return chain.proceed(newRequest)
    }

    private fun imageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val userAgent = request.header("User-Agent")
        val normalizedUserAgent = userAgent?.replace(
            Regex("""\s*Mobile Safari/\d+(?:\.\d+)*""", RegexOption.IGNORE_CASE),
            "",
        )?.trim()?.ifEmpty { null }

        val modRequest = if (normalizedUserAgent != userAgent) {
            request.newBuilder().header("User-Agent", normalizedUserAgent ?: "").build()
        } else request

        val response: Response? = try {
            chain.proceed(modRequest)
        } catch (_: java.net.UnknownHostException) {
            null
        }

        if (response?.isSuccessful == true) return response

        val currentHost = cdnUrls.firstOrNull { request.url.toString().startsWith(it) }
        if (currentHost == null) return response ?: throw java.net.UnknownHostException(request.url.host)

        response?.close()
        val imagePath = request.url.toString().removePrefix(currentHost).removePrefix("/")
        for (newHost in cdnUrls) {
            if (newHost == currentHost) continue
            val newUrl = "$newHost/$imagePath".toHttpUrl()
            try {
                val newResp = chain.proceed(request.newBuilder().url(newUrl).build())
                if (newResp.isSuccessful) return newResp
                newResp.close()
            } catch (_: Exception) {}
        }
        throw java.net.UnknownHostException("All CDN hosts failed for $imagePath")
    }

    private data class SessionRoute(
        val key: String,
        val sessionApiUrl: String,
        val slug: String?,
        val isChapterListRequest: Boolean,
        val isChapterImageRequest: Boolean,
    )

    private fun resolveSessionRoute(url: okhttp3.HttpUrl): SessionRoute {
        val segments = url.pathSegments
        val komikIndex = segments.indexOf("komik")
        val slug = if (komikIndex != -1) segments.getOrNull(komikIndex + 1) else null
        val isChapterListRequest = komikIndex != -1 && segments.getOrNull(komikIndex + 2) == "chapter"
        val isChapterImageRequest = isChapterListRequest && (segments.contains("imgs") || segments.contains("img"))

        val key = if (isChapterImageRequest) sessionKeyChapterImage else sessionKeyChapterList
        val sessionApiUrl = if (isChapterImageRequest) {
            "https://$domain/api/session/chapter/oioa"
        } else {
            "https://$domain/api/session/iuiuiwqw"
        }
        return SessionRoute(
            key = key,
            sessionApiUrl = sessionApiUrl,
            slug = slug,
            isChapterListRequest = isChapterListRequest,
            isChapterImageRequest = isChapterImageRequest,
        )
    }

    @Volatile
    private var genresCache: Set<MangaTag>? = null
    private val genresMutex = Mutex()

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getOrFetchGenres(),
    )

    private suspend fun getOrFetchGenres(): Set<MangaTag> {
        genresCache?.let { return it }
        return genresMutex.withLock {
            genresCache ?: fetchGenres().also { genresCache = it }
        }
    }

    private suspend fun fetchGenres(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/komik/library", pageHeaders()).parseHtml()
        val pageProps = extractNextJs(doc) ?: return emptySet()
        val arr = pageProps.optJSONArray("genre") ?: return emptySet()
        return LinkedHashSet<MangaTag>(arr.length()).apply {
            for (i in 0 until arr.length()) {
                val jo = arr.optJSONObject(i) ?: continue
                val value = jo.optString("value", "").ifEmpty { jo.optString("label", "") }
                val label = jo.optString("label", "").ifEmpty { value }
                if (value.isNotEmpty() && label.isNotEmpty()) {
                    add(MangaTag(title = label, key = value, source = source))
                }
            }
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            return searchByQuery(query, page)
        }
        val url = buildString {
            append("https://$domain/komik/library")
            append("?page=").append(page)
            append("&sortBy=").append(if (order == SortOrder.POPULARITY) "popular" else "newKomik")
            filter.tags.firstOrNull()?.key?.let {
                append("&genre=").append(it.urlEncoded())
            }
        }
        val doc = webClient.httpGet(url, pageHeaders()).parseHtml()
        val pageProps = extractNextJs(doc) ?: return emptyList()
        val libData = pageProps.optJSONObject("libData") ?: pageProps
        val dataArray = libData.optJSONArray("data") ?: return emptyList()
        return parseListData(dataArray)
    }

    private suspend fun searchByQuery(query: String, page: Int): List<Manga> {
        val urlPath = "$apiUrl/komik?name=${query.urlEncoded()}&search=true&limit=24&page=$page"
        val session = getSessionAsync(resolveSessionRoute(urlPath.toHttpUrl()))
        val headers = apiHeaders(session)
        val json = webClient.httpGet(urlPath, headers).parseJson()
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        return parseListData(dataArray)
    }

    private fun parseListData(dataArray: JSONArray): List<Manga> {
        val result = ArrayList<Manga>(dataArray.length())
        for (i in 0 until dataArray.length()) {
            val jo = dataArray.optJSONObject(i) ?: continue
            val slug = jo.optString("title_slug", "").ifEmpty { jo.optString("id", "") }
            if (slug.isEmpty()) continue
            val gambar = jo.optString("gambar", "").removePrefix("/")
            result.add(
                Manga(
                    id = generateUid(slug),
                    title = jo.optString("title", "Untitled"),
                    altTitles = emptySet(),
                    url = "/$slug",
                    publicUrl = "https://$domain/$slug",
                    rating = RATING_UNKNOWN,
                    contentRating = ContentRating.SAFE,
                    coverUrl = if (gambar.isNotEmpty()) "$coverUrl/$gambar" else "",
                    tags = emptySet(),
                    state = parseStatus(jo.optString("status")),
                    authors = emptySet(),
                    source = source,
                ),
            )
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, pageHeaders()).parseHtml()
        val pageProps = extractNextJs(doc)
        val detail = pageProps?.optJSONObject("data")

        val title = detail?.optString("title")?.takeIf { it.isNotBlank() } ?: manga.title
        val gambar = detail?.optString("gambar", "")?.removePrefix("/") ?: ""
        val coverImg = if (gambar.isNotEmpty()) "$coverUrl/$gambar" else manga.coverUrl
        val description = detail?.optString("sinopsis")?.takeIf { it.isNotBlank() }
        val author = detail?.optString("author")?.takeIf { it.isNotBlank() }
        val state = parseStatus(detail?.optString("status"))

        val tags = LinkedHashSet<MangaTag>()
        detail?.optJSONArray("Genre")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "").trim()
                if (name.isNotEmpty()) tags.add(MangaTag(name, name.lowercase(), source))
            }
        }

        val isLoginRequired = tags.any { it.title.lowercase() in loginRequiredGenres }
        val slug = manga.url.trim('/').substringBefore('/')
        val chapters = fetchChapterList(slug, isLoginRequired)

        return manga.copy(
            title = title,
            coverUrl = coverImg,
            description = description,
            tags = tags,
            authors = setOfNotNull(author),
            state = state,
            chapters = chapters,
        )
    }

    private suspend fun fetchChapterList(slug: String, isLoginRequired: Boolean): List<MangaChapter> {
        val apiPath = "$apiUrl/komik/$slug/chapter?limit=9999999"
        val headers = if (isLoginRequired) {
            val token = getBearerTokenFromCookie() ?: throw Exception("Login diperlukan untuk mengakses chapter")
            Headers.Builder()
                .add("Authorization", "Bearer $token")
                .add("Referer", "https://$domain/$slug")
                .add("Origin", "https://$domain")
                .build()
        } else {
            val session = getSessionAsync(resolveSessionRoute(apiPath.toHttpUrl()))
            apiHeaders(session)
        }

        val json = webClient.httpGet(apiPath, headers).parseJson()
        val chapterArray = json.optJSONArray("chapter") ?: return emptyList()
        val chapters = ArrayList<MangaChapter>(chapterArray.length())
        for (i in 0 until chapterArray.length()) {
            val ch = chapterArray.optJSONObject(i) ?: continue
            val chStr = ch.optString("chapter", "")
            if (chStr.isEmpty()) continue
            val number = chStr.substringBefore(".").toFloatOrNull() ?: continue
            var chapterUrl = "/$slug/chapter/$chStr"
            if (isLoginRequired) chapterUrl += requiredLoginFragment
            chapters.add(
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = "Chapter ${formatChapterDisplay(chStr)}",
                    url = chapterUrl,
                    number = number,
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                ),
            )
        }
        chapters.sortBy { it.number }
        return chapters
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val isLoginRequired = chapter.url.contains(requiredLoginFragment)
        val cleanUrl = chapter.url.substringBefore('#')

        val doc = webClient.httpGet(cleanUrl.toAbsoluteUrl(domain), pageHeaders()).parseHtml()
        val pageProps = extractNextJs(doc)
        val data = pageProps?.optJSONObject("data")
        val komik = data?.optJSONObject("komik")
        val chapterData = data?.optJSONObject("data")

        val slug = cleanUrl.trim('/').substringBefore("/chapter/")
        val chNum = cleanUrl.substringAfterLast("/chapter/").trim('/')

        var imageSrc = chapterData?.optJSONArray("imageSrc") ?: JSONArray()
        val storageInter2 = chapterData?.optBoolean("storageInter2", false) ?: false

        if (imageSrc.length() == 0) {
            val id = chapterData?.optString("_id") ?: komik?.optString("_id") ?: return emptyList()
            imageSrc = fetchChapterImages(slug, chNum, id, isLoginRequired)
        }

        if (imageSrc.length() == 0) return emptyList()

        val imageBaseUrl = if (storageInter2) cdnUrls[2] else cdnUrls[0]
        return (0 until imageSrc.length()).mapNotNull { i ->
            val path = imageSrc.optString(i, "").removePrefix("/")
            if (path.isEmpty()) return@mapNotNull null
            MangaPage(
                id = generateUid(path),
                url = "$imageBaseUrl/$path",
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchChapterImages(
        slug: String,
        chapter: String,
        id: String,
        isLoginRequired: Boolean,
    ): JSONArray {
        val url = "$apiUrl/komik/$slug/chapter/$chapter/imgs/$id"
        val headers = if (isLoginRequired) {
            val token = getBearerTokenFromCookie() ?: throw Exception("Login diperlukan")
            Headers.Builder()
                .add("Authorization", "Bearer $token")
                .add("Referer", "https://$domain/$slug/chapter/$chapter")
                .add("Origin", "https://$domain")
                .build()
        } else {
            val session = getSessionAsync(resolveSessionRoute(url.toHttpUrl()))
            apiHeaders(session)
        }
        val json = webClient.httpGet(url, headers).parseJson()
        return json.optJSONArray("imageSrc") ?: JSONArray()
    }

    private fun extractNextJs(doc: org.jsoup.nodes.Document): JSONObject? {
        val raw = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        return runCatching {
            JSONObject(raw).optJSONObject("props")?.optJSONObject("pageProps")
        }.getOrNull()
    }

    private fun parseStatus(text: String?): MangaState? = when {
        text == null -> null
        text.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
        text.contains("tamat", ignoreCase = true) || text.contains("completed", ignoreCase = true) -> MangaState.FINISHED
        else -> null
    }

    private fun formatChapterDisplay(chStr: String): String {
        val parts = chStr.split(".")
        val numPart = parts[0].toFloatOrNull() ?: return chStr
        val formatted = if (numPart == numPart.toLong().toFloat()) numPart.toLong().toString()
        else numPart.toString().trimEnd('0').trimEnd('.')
        val suffix = parts.drop(1).joinToString(".")
        return if (suffix.isNotEmpty()) "$formatted.$suffix" else formatted
    }
}
