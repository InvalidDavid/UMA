package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

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

import tsuki.network.CloudFlareHelper
import tsuki.network.OkHttpWebClient
import tsuki.network.UserAgents
import tsuki.network.WebClient

import tsuki.util.LinkResolver
import tsuki.util.generateUid
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseJson
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlBuilder

import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object DoujinDesuDns : Dns {
    private val blockedPrefixes = listOf(
        "36.86.", "118.98.", "180.250.", "103.229.", "114.124.", "202.152.", "202.73.", "10.", "127.",
    )

    internal val cloudflareDesuIps = listOf(
        InetAddress.getByName("104.26.1.249"),
        InetAddress.getByName("104.26.0.249"),
        InetAddress.getByName("172.67.72.203"),
    )

    internal val cloudflarePicsIps = listOf(
        InetAddress.getByName("104.21.1.241"),
        InetAddress.getByName("172.67.152.153"),
    )

    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        val isTarget = hostname.endsWith("desu.xxx") || hostname == "desu.xxx" ||
                hostname.endsWith("desu.pics") || hostname == "desu.pics"
        if (!isTarget) {
            return Dns.SYSTEM.lookup(hostname)
        }

        dnsCache[hostname]?.let { return it }

        val fallback = if (hostname.endsWith("desu.pics") || hostname == "desu.pics") {
            cloudflarePicsIps
        } else {
            cloudflareDesuIps
        }

        val dohAddresses = resolveDoH(hostname)
        val validDoh = dohAddresses?.filterNot { addr ->
            val hostAddress = addr.hostAddress ?: ""
            blockedPrefixes.any { hostAddress.startsWith(it) }
        }.orEmpty()

        val result = if (validDoh.isNotEmpty()) {
            (validDoh + fallback).distinct()
        } else {
            fallback
        }

        dnsCache[hostname] = result
        return result
    }

    internal fun resolveDoH(hostname: String): List<InetAddress>? {
        val dohEndpoints = listOf(
            "https://1.1.1.1/dns-query?name=$hostname&type=A",
            "https://8.8.8.8/resolve?name=$hostname&type=A",
        )
        for (endpoint in dohEndpoints) {
            val addresses = queryDoHEndpoint(endpoint)
            if (!addresses.isNullOrEmpty()) return addresses
        }
        return null
    }

    private fun queryDoHEndpoint(urlStr: String): List<InetAddress>? = runCatching {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/dns-json")
            connectTimeout = 3000
            readTimeout = 3000
        }
        if (conn.responseCode == 200) {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val answers = json.optJSONArray("Answer")
            if (answers != null && answers.length() > 0) {
                val list = mutableListOf<InetAddress>()
                for (i in 0 until answers.length()) {
                    val ans = answers.getJSONObject(i)
                    if (ans.optInt("type") == 1) { // Type A
                        val ip = ans.getString("data")
                        if (blockedPrefixes.none { ip.startsWith(it) }) {
                            list.add(InetAddress.getByName(ip))
                        }
                    }
                }
                if (list.isNotEmpty()) return@runCatching list
            }
        }
        null
    }.getOrNull()
}

internal abstract class DoujinDesuParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
): PagedMangaParser(context, source, pageSize = 18) {

    override val configKeyDomain = ConfigKey.Domain("doujin.desu.xxx")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private val client: OkHttpClient by lazy {
        context.httpClient.newBuilder()
            .dns(DoujinDesuDns)
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                if (req.header(INTERNAL_HEADER) != null) {
                    chain.proceed(req.newBuilder().removeHeader(INTERNAL_HEADER).build())
                } else {
                    chain.proceed(req)
                }
            }
            .build()
    }

    override val webClient: WebClient by lazy {
        OkHttpWebClient(client, source)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (request.header(INTERNAL_HEADER) != null) {
            val cleaned = request.newBuilder().removeHeader(INTERNAL_HEADER).build()
            return chain.proceed(cleaned)
        }

        val isTarget = host == domain || host.endsWith(".desu.xxx") || host.endsWith(".desu.pics")
        if (isTarget) {
            val reqBuilder = request.newBuilder()
                .header(INTERNAL_HEADER, "1")
                .header("Referer", "https://$domain/")

            if (host != domain) {
                reqBuilder.removeHeader("x-app-secret").removeHeader("X-App-Secret")
            }
            if (request.header("User-Agent") == null) {
                reqBuilder.header("User-Agent", UserAgents.CHROME_DESKTOP)
            }
            return client.newCall(reqBuilder.build()).execute()
        }

        return chain.proceed(request)
    }

    @Volatile
    private var genresCache: Set<MangaTag>? = null
    private val genresMutex = Mutex()

    private val detailsCacheLock = Any()

    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.POPULARITY,
        SortOrder.NEWEST_ASC,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isAuthorSearchSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getOrFetchGenres() + excludeTypeTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.DOUJINSHI,
            ContentType.MANHWA,
        ),
    )

    private suspend fun getOrFetchGenres(): Set<MangaTag> {
        genresCache?.let { return it }
        return genresMutex.withLock {
            genresCache ?: run {
                genresCache = fetchAvailableTags()
                genresCache!!
            }
        }
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val url = "/api/terms?taxonomy=genre".toAbsoluteUrl(domain)
        val jsonResponse = executeWithCloudflareRetry(url, getRequestHeaders()).parseJson()
        val decrypted = decrypt(jsonResponse.getString("_enc_resp_"))
        val array = JSONArray(decrypted)
        return (0 until array.length()).mapTo(mutableSetOf()) { i ->
            val obj = array.getJSONObject(i)
            MangaTag(key = obj.getString("slug"), title = obj.getString("name"), source = source)
        }
    }

    override fun getRequestHeaders() = Headers.Builder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", "https://$domain/")
        .add("X-App-Secret", APP_SECRET)
        .build()

    private suspend fun executeWithCloudflareRetry(url: String, headers: Headers): Response {
        var response = webClient.httpGet(url, headers)
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            response.close()
            refreshCloudflare()
            response = webClient.httpGet(url, headers)
            val secondProtection = CloudFlareHelper.checkResponseForProtection(response)
            if (secondProtection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
                response.close()
                val actionException = runCatching {
                    context.requestBrowserAction(this, "https://$domain/")
                }.exceptionOrNull()
                if (actionException != null && actionException !is UnsupportedOperationException) {
                    throw actionException
                }
                throw ParseException("Cloudflare challenge encountered. Please complete verification in browser.", url)
            }
        }

        if (response.code == 403) {
            val peek = runCatching { response.peekBody(2048L).string() }.getOrNull().orEmpty()
            if ("Unauthorized" in peek || "Access denied" in peek) {
                response.close()
                refreshCloudflare()
                response = webClient.httpGet(url, headers)
            }
        }

        if (response.code in 500..599) {
            response.close()
            delay(1000L.milliseconds)
            response = webClient.httpGet(url, headers)
        }
        return response
    }

    private suspend fun refreshCloudflare() {
        runCatching {
            webClient.httpGet("https://$domain/", getRequestHeaders()).close()
        }
    }

    private val excludePrefix = "xtype:"
    private val allTypes = listOf(ContentType.MANGA, ContentType.DOUJINSHI, ContentType.MANHWA)

    private fun excludeTypeTags(): Set<MangaTag> = allTypes.mapTo(LinkedHashSet()) { type ->
        MangaTag(
            key = excludePrefix + type.name,
            title = "Exclude: " + type.name.lowercase().replaceFirstChar { it.uppercase() },
            source = source,
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val excluded = filter.tags
            .filter { it.key.startsWith(excludePrefix) }
            .mapNotNull { runCatching { ContentType.valueOf(it.key.removePrefix(excludePrefix)) }.getOrNull() }
            .toSet()
        val realTags = filter.tags.filterNot { it.key.startsWith(excludePrefix) }.toSet()

        if (excluded.isEmpty() && filter.types.size <= 1) {
            return fetchListPage(page, order, filter.copy(tags = realTags))
        }

        val wanted = (filter.types.ifEmpty { allTypes.toSet() }) - excluded
        if (wanted.isEmpty()) return emptyList()

        val results = coroutineScope {
            wanted.map { type ->
                async { fetchListPage(page, order, filter.copy(types = setOf(type), tags = realTags)) }
            }.awaitAll()
        }

        val merged = LinkedHashMap<Long, Manga>()
        val longest = results.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (list in results) list.getOrNull(i)?.let { merged.putIfAbsent(it.id, it) }
        }
        val list = merged.values.toList()
        return when (order) {
            SortOrder.ALPHABETICAL -> list.sortedBy { it.title.lowercase() }
            SortOrder.POPULARITY -> list.sortedByDescending { it.rating }
            else -> list
        }
    }

    private suspend fun fetchListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val limit = pageSize
        val offset = (page - 1) * limit

        val url = urlBuilder().apply {
            addPathSegment("api")
            addPathSegment("manga")
            addQueryParameter("limit", limit.toString())
            addQueryParameter("offset", offset.toString())

            filter.query?.let { addQueryParameter("search", it) }

            val sortParam = when (order) {
                SortOrder.POPULARITY -> "rating"
                SortOrder.ALPHABETICAL -> "title_asc"
                SortOrder.NEWEST -> "newest"
                SortOrder.NEWEST_ASC -> "oldest"
                SortOrder.UPDATED -> "latest_chapter"
                else -> "latest_chapter"
            }
            addQueryParameter("sort", sortParam)

            filter.types.oneOrThrowIfMany()?.let {
                when (it) {
                    ContentType.MANGA -> "manga"
                    ContentType.MANHWA -> "manhwa"
                    ContentType.DOUJINSHI -> "doujinshi"
                    else -> null
                }?.let { type -> addQueryParameter("type", type) }
            }

            filter.states.singleOrNull()?.let {
                when (it) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    else -> null
                }?.let { status -> addQueryParameter("status", status) }
            }

            if (filter.tags.isNotEmpty()) {
                addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
            }
        }.build()

        val jsonResponse = executeWithCloudflareRetry(url.toString(), getRequestHeaders()).parseJson()
        val decrypted = decrypt(jsonResponse.getString("_enc_resp_"))
        val array = JSONArray(decrypted)

        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val slug = obj.getString("slug")
            val href = "/manga/$slug"
            val state = when (obj.optString("status").lowercase()) {
                "completed", "finished" -> MangaState.FINISHED
                "ongoing", "publishing" -> MangaState.ONGOING
                else -> null
            }
            val tags = mutableSetOf<MangaTag>()
            val genresArray = obj.optJSONArray("manga_genres")
            if (genresArray != null) {
                for (j in 0 until genresArray.length()) {
                    val genreObj = genresArray.getJSONObject(j).optJSONObject("genres") ?: continue
                    tags.add(
                        MangaTag(
                            key = genreObj.getString("slug"),
                            title = genreObj.getString("name"),
                            source = source,
                        )
                    )
                }
            }
            if (tags.isEmpty()) {
                val termsStr = obj.optString("terms")
                if (termsStr.isNotEmpty()) {
                    termsStr.split(',').forEach { term ->
                        val parts = term.split(':')
                        if (parts.size >= 2 && parts[1] == "genre") {
                            val name = parts[0].trim()
                            val tagSlug = name.lowercase().replace(" ", "-")
                            tags.add(MangaTag(key = tagSlug, title = name, source = source))
                        }
                    }
                }
            }
            val rawRating = obj.optDouble("rating", 0.0)
            val rating = if (rawRating > 0.0) (rawRating / 10.0).toFloat() else RATING_UNKNOWN

            Manga(
                id = generateUid(href),
                title = obj.getString("title"),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = rating,
                contentRating = ContentRating.ADULT,
                coverUrl = obj.optString("cover_url").takeIf { it.isNotEmpty() && it != "null" }?.let { cover ->
                    when {
                        cover.startsWith("//") -> "https:$cover"
                        cover.startsWith("http") -> cover
                        else -> cover.toAbsoluteUrl(domain)
                    }
                },
                tags = tags,
                state = state,
                authors = emptySet(),
                largeCoverUrl = null,
                description = null,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        synchronized(detailsCacheLock) {
            detailsCache[manga.url]?.let { return it }
        }

        val rawSlug = if ("/manga/" in manga.url) {
            manga.url.substringAfter("/manga/")
        } else {
            manga.url
        }
        val slug = rawSlug.substringBefore('?').substringBefore('#').trim('/')
        val url = "/api/manga/$slug".toAbsoluteUrl(domain)

        val jsonResponse = executeWithCloudflareRetry(url, getRequestHeaders()).parseJson()
        val decrypted = decrypt(jsonResponse.getString("_enc_resp_"))
        val obj = JSONObject(decrypted)

        val state = when (obj.optString("status").lowercase()) {
            "completed", "finished" -> MangaState.FINISHED
            "ongoing", "publishing" -> MangaState.ONGOING
            else -> null
        }

        val termMap = mutableMapOf<String, MutableList<String>>()
        val termList = obj.optString("term_list")
        if (termList.isNotEmpty()) {
            termList.split('|').forEach { term ->
                val parts = term.split(':')
                if (parts.size == 3) {
                    termMap.getOrPut(parts[1]) { mutableListOf() }.add(parts[0])
                }
            }
        }

        val authorsList = mutableSetOf<String>()
        val rawAuthor = obj.optString("author").takeIf { it.isNotBlank() && it != "null" && !it.equals("N/A", true) }
        val rawArtist = obj.optString("artist").takeIf { it.isNotBlank() && it != "null" && !it.equals("N/A", true) }
        if (rawAuthor != null) {
            authorsList.addAll(rawAuthor.split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("N/A", true) })
        }
        if (rawArtist != null) {
            authorsList.addAll(rawArtist.split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("N/A", true) })
        }
        if (authorsList.isEmpty()) {
            listOf("author", "artist", "creator", "group").forEach { key ->
                termMap[key]?.forEach { name ->
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty() && !trimmed.equals("N/A", true)) {
                        authorsList.add(trimmed)
                    }
                }
            }
        }

        val tags = mutableSetOf<MangaTag>()
        val genresArray = obj.optJSONArray("manga_genres")
        if (genresArray != null) {
            for (i in 0 until genresArray.length()) {
                val genreObj = genresArray.getJSONObject(i).optJSONObject("genres") ?: continue
                tags.add(
                    MangaTag(
                        key = genreObj.getString("slug"),
                        title = genreObj.getString("name"),
                        source = source,
                    )
                )
            }
        }
        if (tags.isEmpty() && termList.isNotEmpty()) {
            termList.split('|').forEach { term ->
                val parts = term.split(':')
                if (parts.size == 3 && parts[1] == "genre") {
                    tags.add(MangaTag(key = parts[2], title = parts[0], source = source))
                }
            }
        }

        val chaptersArray = obj.optJSONArray("chapters") ?: JSONArray()
        val chapters = ArrayList<MangaChapter>(chaptersArray.length())
        for (i in 0 until chaptersArray.length()) {
            val chapObj = chaptersArray.getJSONObject(i)
            val chNum = chapObj.optDouble("chapter_number", 0.0).toFloat()
            val rawTitle = chapObj.optString("title").takeIf { it.isNotBlank() && it != "null" }
            val chTitle = rawTitle ?: if (chNum == chNum.toLong().toFloat()) {
                "Chapter ${chNum.toLong()}"
            } else {
                "Chapter $chNum"
            }
            val chapId = chapObj.getString("id")
            chapters.add(
                MangaChapter(
                    id = generateUid("/reader/$chapId"),
                    title = chTitle,
                    number = chNum,
                    volume = 0,
                    url = "/reader/$chapId",
                    scanlator = null,
                    uploadDate = runCatching { Instant.parse(chapObj.optString("created_at")).toEpochMilli() }.getOrDefault(0L),
                    branch = null,
                    source = source,
                )
            )
        }

        val rawDesc = obj.optString("description").takeIf { it.isNotEmpty() && it != "null" }
        val cleanDesc = rawDesc?.let { html ->
            val document = Jsoup.parseBodyFragment(Parser.unescapeEntities(html, false))
            val fullText = document.text()
            val cutoffRegex = Regex("""download\s+(batch|volume)""", RegexOption.IGNORE_CASE)
            val cutoffIndex = cutoffRegex.find(fullText)?.range?.first
            val textBeforeDownload = if (cutoffIndex != null) fullText.substring(0, cutoffIndex) else fullText
            textBeforeDownload
                .replace(Regex("""^Sinopsis:\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""https?://\S+"""), "")
                .replace(Regex("""\[url=.*?]|\[/url]"""), "")
                .trim()
        }

        val altTitles = obj.optString("alt_titles").takeIf { it.isNotEmpty() && it != "null" }
            ?.split('|', ',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

        val coverUrl = obj.optString("cover_url").takeIf { it.isNotEmpty() && it != "null" }?.let { cover ->
            when {
                cover.startsWith("//") -> "https:$cover"
                cover.startsWith("http") -> cover
                else -> cover.toAbsoluteUrl(domain)
            }
        }

        val rawRating = obj.optDouble("rating", 0.0)
        val rating = if (rawRating > 0.0) (rawRating / 10.0).toFloat() else manga.rating

        val result = manga.copy(
            authors = authorsList,
            altTitles = altTitles,
            description = cleanDesc,
            state = state,
            rating = rating,
            tags = tags,
            coverUrl = coverUrl ?: manga.coverUrl,
            chapters = chapters.sortedWith(compareBy<MangaChapter> { it.number }.thenBy { it.uploadDate }),
        )

        synchronized(detailsCacheLock) {
            detailsCache[manga.url] = result
        }
        return result
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val rawChId = if ("/reader/" in chapter.url) {
            chapter.url.substringAfter("/reader/")
        } else {
            chapter.url
        }
        val chId = rawChId.substringBefore('?').substringBefore('#').trim('/')
        val url = "/api/chapters/$chId".toAbsoluteUrl(domain)

        val jsonResponse = executeWithCloudflareRetry(url, getRequestHeaders()).parseJson()
        val decrypted = decrypt(jsonResponse.getString("_enc_resp_"))
        val obj = JSONObject(decrypted)

        val rawUrls = mutableListOf<String>()
        val contentUrls = obj.optJSONArray("content_urls")
        if (contentUrls != null && contentUrls.length() > 0) {
            for (i in 0 until contentUrls.length()) {
                rawUrls.add(contentUrls.getString(i))
            }
        } else {
            val signedUrlsObj = obj.opt("signed_content_urls")
            if (signedUrlsObj is JSONArray) {
                for (i in 0 until signedUrlsObj.length()) {
                    rawUrls.add(signedUrlsObj.getString(i))
                }
            } else if (signedUrlsObj is String && signedUrlsObj.isNotEmpty()) {
                runCatching {
                    val arr = JSONArray(signedUrlsObj)
                    for (i in 0 until arr.length()) {
                        rawUrls.add(arr.getString(i))
                    }
                }
            }
        }

        return rawUrls.map { rawUrl ->
            val unescaped = Parser.unescapeEntities(rawUrl, false)
            val fullUrl = when {
                unescaped.startsWith("//") -> "https:$unescaped"
                unescaped.startsWith("http") -> unescaped
                else -> unescaped.toAbsoluteUrl(domain)
            }
            MangaPage(
                id = generateUid(fullUrl.substringBefore('?')),
                url = fullUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        val segments = link.pathSegments.filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        val slug = when (segments.firstOrNull()) {
            "manga" -> segments.getOrNull(1)?.substringBefore('?')
            "reader" -> {
                val chapterId = segments.getOrNull(1)?.substringBefore('?') ?: return null
                val chUrl = "/api/chapters/$chapterId".toAbsoluteUrl(domain)
                runCatching {
                    val resp = executeWithCloudflareRetry(chUrl, getRequestHeaders()).parseJson()
                    val dec = decrypt(resp.getString("_enc_resp_"))
                    JSONObject(dec).optString("manga_slug").takeIf { it.isNotBlank() }
                }.getOrNull()
            }
            else -> null
        }?.substringBefore('#')?.trim('/') ?: return null

        val href = "/manga/$slug"
        return getDetails(
            Manga(
                id = generateUid(href),
                title = slug,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                largeCoverUrl = null,
                description = null,
                source = source,
            )
        )
    }

    private fun toSigned32(x: Long): Int {
        var y = x and 0xFFFFFFFFL
        if (y >= 0x80000000L) y -= 0x100000000L
        return y.toInt()
    }

    private fun wH(e: Int): String {
        val t = "doujindesu-scrapers-cannot-read-this-super-secret-salt-2026-v2_$e"
        var a = 0
        for (ch in t) {
            a = (a shl 5) - a + ch.code
            a = toSigned32(a.toLong())
        }
        var l = if (a != 0) abs(a).toLong() else 123456789L
        return buildString {
            repeat(32) {
                l = (l * 1664525L + 1013904223L) % 4294967296L
                append((33 + (l % 93)).toInt().toChar())
            }
        }
    }

    private fun lU(): List<String> {
        val now = System.currentTimeMillis()
        val t = now / 3600000L
        return listOf(wH(t.toInt()), wH((t - 1).toInt()), wH((t + 1).toInt()))
    }

    private fun yre(encryptedHex: String, key: String): String {
        val bytes = encryptedHex.chunked(2).mapNotNull { it.toIntOrNull(16) }
        var d = 42
        val keyLen = key.length
        return buildString {
            for ((idx, byteVal) in bytes.withIndex()) {
                val keyChar = key[idx % keyLen].code
                val k = byteVal xor keyChar xor (idx * 13) xor d
                append((k and 0xFF).toChar())
                d = (d + byteVal) % 256
            }
        }
    }

    internal fun decrypt(encryptedHex: String): String {
        for (key in lU()) {
            try {
                val decoded = yre(encryptedHex, key)
                return URLDecoder.decode(decoded, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
            }
        }
        throw ParseException("Decryption failed", "")
    }

    companion object {
        private const val INTERNAL_HEADER = "X-DoujinDesu-Internal"
        private const val APP_SECRET = "dfdf72051dbfdc7d76889ebd31324e74"
    }
}
