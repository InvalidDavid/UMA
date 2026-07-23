package tsuki.parsers

import tsuki.MangaLoaderContext
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
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseJson
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlBuilder

import okhttp3.Headers
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.time.Instant
import java.util.EnumSet
import kotlin.math.abs
import kotlin.text.iterator
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


internal abstract class DoujinDesuParser(
    context: MangaLoaderContext,
    source: MangaParserSource
): PagedMangaParser(context, source, pageSize = 18) {

    protected abstract val defaultTypes: String
    protected abstract val availableContentTypes: Set<ContentType>

    override val configKeyDomain = ConfigKey.Domain("doujin.desu.xxx")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    @Volatile
    private var genresCache: Set<MangaTag>? = null
    private val genresMutex = Mutex()

    private val detailsCacheLock = Any()

    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    override val defaultSortOrder: SortOrder = SortOrder.UPDATED

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.ALPHABETICAL, SortOrder.POPULARITY
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isAuthorSearchSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getOrFetchGenres(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = availableContentTypes,
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
        .add("X-App-Secret", "dfdf72051dbfdc7d76889ebd31324e74")
        .build()

    private suspend fun executeWithCloudflareRetry(url: String, headers: Headers): Response {
        var response = webClient.httpGet(url, headers)
        if (response.code == 403) {
            val bodyString = response.body.string()
            if ("Unauthorized" in bodyString || "Access denied" in bodyString) {
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
        webClient.httpGet("https://$domain/", getRequestHeaders()).close()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
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

            filter.states.oneOrThrowIfMany()?.let {
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
            Manga(
                id = generateUid(href),
                title = obj.getString("title"),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = obj.optDouble("rating", 0.0).toFloat() / 10f,
                contentRating = ContentRating.ADULT,
                coverUrl = obj.optString("cover_url").takeIf { it.isNotEmpty() }?.let { cover ->
                    if (cover.startsWith("http")) cover else cover.toAbsoluteUrl(domain)
                },
                tags = emptySet(),
                state = null,
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

        val slug = manga.url.removePrefix("/manga/").removeSuffix("/")
        val url = "/api/manga/$slug".toAbsoluteUrl(domain)

        val jsonResponse = executeWithCloudflareRetry(url, getRequestHeaders()).parseJson()
        val decrypted = decrypt(jsonResponse.getString("_enc_resp_"))
        val obj = JSONObject(decrypted)

        val state = when (obj.optString("status")) {
            "completed" -> MangaState.FINISHED
            "ongoing" -> MangaState.ONGOING
            else -> null
        }

        val author = obj.optString("author").takeIf { it.isNotEmpty() && it != "null" }

        val tags = mutableSetOf<MangaTag>()
        val genresArray = obj.optJSONArray("manga_genres")
        if (genresArray != null) {
            for (i in 0 until genresArray.length()) {
                val genreObj = genresArray.getJSONObject(i).getJSONObject("genres")
                tags.add(
                    MangaTag(
                        key = genreObj.getString("slug"),
                        title = genreObj.getString("name"),
                        source = source,
                    )
                )
            }
        }

        val chaptersArray = obj.getJSONArray("chapters")
        val chapters = ArrayList<MangaChapter>(chaptersArray.length())
        for (i in 0 until chaptersArray.length()) {
            val chapObj = chaptersArray.getJSONObject(i)
            val chNum = chapObj.optDouble("chapter_number", 0.0).toFloat()
            val chTitle = if (chNum == chNum.toLong().toFloat()) {
                "Chapter ${chNum.toLong()}"
            } else {
                "Chapter $chNum"
            }
            chapters.add(
                MangaChapter(
                    id = generateUid("/reader/${chapObj.getString("id")}"),
                    title = chTitle,
                    number = chNum,
                    volume = 0,
                    url = "/reader/${chapObj.getString("id")}",
                    scanlator = null,
                    uploadDate = runCatching { Instant.parse(chapObj.optString("created_at")).toEpochMilli() }.getOrDefault(0L),
                    branch = null,
                    source = source,
                )
            )
        }

        val rawDesc = obj.optString("description").takeIf { it.isNotEmpty() && it != "null" }
        val cleanDesc = rawDesc?.let { html ->
            val document = Jsoup.parseBodyFragment(html)
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

        val coverUrl = obj.optString("cover_url").takeIf { it.isNotEmpty() }?.let { cover ->
            if (cover.startsWith("http")) cover else cover.toAbsoluteUrl(domain)
        }

        val result = manga.copy(
            authors = setOfNotNull(author),
            description = cleanDesc,
            state = state,
            rating = obj.optDouble("rating", 0.0).toFloat() / 10f,
            tags = tags,
            coverUrl = coverUrl ?: manga.coverUrl,
            chapters = chapters.reversed()
        )

        synchronized(detailsCacheLock) {
            detailsCache[manga.url] = result
        }
        return result
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chId = chapter.url.removePrefix("/reader/").removeSuffix("/")
        val url = "/api/chapters/$chId".toAbsoluteUrl(domain)

        val jsonResponse = executeWithCloudflareRetry(url, getRequestHeaders()).parseJson()
        val decrypted = decrypt(jsonResponse.getString("_enc_resp_"))
        val obj = JSONObject(decrypted)

        val pagesList = mutableListOf<MangaPage>()
        val contentUrls = obj.optJSONArray("content_urls")
        if (contentUrls != null && contentUrls.length() > 0) {
            for (i in 0 until contentUrls.length()) {
                pagesList.add(
                    MangaPage(
                        id = generateUid(contentUrls.getString(i)),
                        url = transformPageUrl(contentUrls.getString(i)),
                        preview = null,
                        source = source,
                    )
                )
            }
        } else {
            val signedUrlsStr = obj.optString("signed_content_urls")
            if (signedUrlsStr.isNotEmpty()) {
                val signedUrls = JSONArray(signedUrlsStr)
                for (i in 0 until signedUrls.length()) {
                    pagesList.add(
                        MangaPage(
                            id = generateUid(signedUrls.getString(i)),
                            url = transformPageUrl(signedUrls.getString(i)),
                            preview = null,
                            source = source,
                        )
                    )
                }
            }
        }

        return pagesList
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

    private fun decrypt(encryptedHex: String): String {
        for (key in lU()) {
            try {
                val decoded = yre(encryptedHex, key)
                return URLDecoder.decode(decoded, "UTF-8")
            } catch (_: Exception) {
            }
        }
        throw RuntimeException("Decryption failed")
    }

    private fun transformPageUrl(page: String): String = when {
        page.contains("/uploads/") && !page.contains("/storage/uploads/") ->
            page.replace("/uploads/", "/storage/uploads/")
        page.contains("/upload/") && !page.contains("/storage/upload/") ->
            page.replace("/upload/", "/storage/upload/")
        else -> page
    }
}
