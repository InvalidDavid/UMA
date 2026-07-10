package org.koitharu.kotatsu.parsers.parsers

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.time.Instant
import java.util.*

internal abstract class DoujinDesuParser(
    context: MangaLoaderContext,
    source: MangaParserSource
) : PagedMangaParser(context, source, pageSize = 18) {

    protected abstract val defaultTypes: String
    protected abstract val availableContentTypes: Set<ContentType>

    override val configKeyDomain: ConfigKey.Domain
        get() = ConfigKey.Domain("doujin.desu.xxx")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    @get:Synchronized
    private val genresCache = object : LinkedHashMap<String, Set<MangaTag>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<MangaTag>>?): Boolean = size > 3
    }

    @get:Synchronized
    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 20
    }

    override val defaultSortOrder: SortOrder
        get() = SortOrder.UPDATED

    override val availableSortOrders: Set<SortOrder>
        get() = EnumSet.of(SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.ALPHABETICAL, SortOrder.POPULARITY)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
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
        val cacheKey = "all" // single key since genre list rarely changes
        genresCache[cacheKey]?.let { return it }
        val tags = fetchAvailableTags()
        genresCache[cacheKey] = tags
        return tags
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val url = "/api/terms?taxonomy=genre".toAbsoluteUrl(domain)
        val jsonResponse = webClient.httpGet(url, extraHeaders = getRequestHeaders()).parseJson()
        val encHex = jsonResponse.getString("_enc_resp_")
        val decrypted = decrypt(encHex)
        val array = JSONArray(decrypted)
        val tags = mutableSetOf<MangaTag>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getString("name")
            val slug = obj.getString("slug")
            tags.add(MangaTag(key = slug, title = name, source = source))
        }
        return tags
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", "https://$domain/")
        .add("X-App-Secret", "dfdf72051dbfdc7d76889ebd31324e74")
        .build()


    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val limit = pageSize
        val offset = (page - 1) * limit

        val url = urlBuilder().apply {
            addPathSegment("api")
            addPathSegment("manga")

            addQueryParameter("limit", limit.toString())
            addQueryParameter("offset", offset.toString())

            filter.query?.let {
                addQueryParameter("search", it)
            }

            val sortParam = when (order) {
                SortOrder.POPULARITY -> "rating"
                SortOrder.ALPHABETICAL -> "title_asc"
                SortOrder.NEWEST -> "newest"
                SortOrder.UPDATED -> "latest_chapter"
                else -> "latest_chapter"
            }
            addQueryParameter("sort", sortParam)

            filter.types.oneOrThrowIfMany()?.let {
                val typeValue = when (it) {
                    ContentType.MANGA -> "manga"
                    ContentType.MANHWA -> "manhwa"
                    ContentType.DOUJINSHI -> "doujinshi"
                    else -> null
                }
                if (!typeValue.isNullOrEmpty()) {
                    addQueryParameter("type", typeValue)
                }
            }

            filter.states.oneOrThrowIfMany()?.let {
                val stateParam = when (it) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    else -> ""
                }
                if (stateParam.isNotEmpty()) {
                    addQueryParameter("status", stateParam)
                }
            }

            if (filter.tags.isNotEmpty()) {
                addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
            }
        }.build()

        val jsonResponse = webClient.httpGet(url, extraHeaders = getRequestHeaders()).parseJson()
        val encHex = jsonResponse.getString("_enc_resp_")
        val decrypted = decrypt(encHex)

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
        // Return cached details if available
        detailsCache[manga.url]?.let { return it }

        val slug = manga.url.removePrefix("/manga/").removeSuffix("/")
        val url = "/api/manga/$slug".toAbsoluteUrl(domain)

        val jsonResponse = webClient.httpGet(url, extraHeaders = getRequestHeaders()).parseJson()
        val encHex = jsonResponse.getString("_enc_resp_")
        val decrypted = decrypt(encHex)
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
                        source = source
                    )
                )
            }
        }

        val chaptersArray = obj.getJSONArray("chapters")
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until chaptersArray.length()) {
            val chapObj = chaptersArray.getJSONObject(i)
            val chId = chapObj.getString("id")
            val chNum = chapObj.optDouble("chapter_number", 0.0).toFloat()
            val chTitle = "Chapter $chNum"
            val chUrl = "/reader/$chId"
            val createdAt = chapObj.optString("created_at")
            val uploadDate = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L)

            chapters.add(
                MangaChapter(
                    id = generateUid(chUrl),
                    title = chTitle,
                    number = chNum,
                    volume = 0,
                    url = chUrl,
                    scanlator = null,
                    uploadDate = uploadDate,
                    branch = null,
                    source = source
                )
            )
        }

        val rawDesc = obj.optString("description").takeIf { it.isNotEmpty() && it != "null" }
        val cleanDesc = rawDesc?.let { html ->
            org.jsoup.Jsoup.parseBodyFragment(html).text()
                .replace(Regex("^Sinopsis:\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
        }

        val coverUrl = obj.optString("cover_url").takeIf { it.isNotEmpty() }?.let { cover ->
            if (cover.startsWith("http")) cover else cover.toAbsoluteUrl(domain)
        }

        val fullManga = manga.copy(
            authors = setOfNotNull(author),
            description = cleanDesc,
            state = state,
            rating = obj.optDouble("rating", 0.0).toFloat() / 10f,
            tags = tags,
            coverUrl = coverUrl ?: manga.coverUrl,
            chapters = chapters.reversed()
        )

        detailsCache[manga.url] = fullManga
        return fullManga
    }


    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chId = chapter.url.removePrefix("/reader/").removeSuffix("/")
        val url = "/api/chapters/$chId".toAbsoluteUrl(domain)

        val jsonResponse = webClient.httpGet(url, extraHeaders = getRequestHeaders()).parseJson()
        val encHex = jsonResponse.getString("_enc_resp_")
        val decrypted = decrypt(encHex)
        val obj = JSONObject(decrypted)

        val pagesList = mutableListOf<MangaPage>()
        val contentUrls = obj.optJSONArray("content_urls")
        if (contentUrls != null && contentUrls.length() > 0) {
            for (i in 0 until contentUrls.length()) {
                val rawUrl = contentUrls.getString(i)
                pagesList.add(
                    MangaPage(
                        id = generateUid(rawUrl),
                        url = transformPageUrl(rawUrl),
                        preview = null,
                        source = source
                    )
                )
            }
        } else {
            val signedUrlsStr = obj.optString("signed_content_urls")
            if (signedUrlsStr.isNotEmpty()) {
                val signedUrls = JSONArray(signedUrlsStr)
                for (i in 0 until signedUrls.length()) {
                    val rawUrl = signedUrls.getString(i)
                    pagesList.add(
                        MangaPage(
                            id = generateUid(rawUrl),
                            url = transformPageUrl(rawUrl),
                            preview = null,
                            source = source
                        )
                    )
                }
            }
        }

        return pagesList
    }


    private fun generateKey(step: Long): String {
        val input = "doujindesu-scrapers-cannot-read-this-super-secret-salt-2026-v2_$step"
        var n = 0
        for (i in 0 until input.length) {
            n = (n shl 5) - n + input[i].code
        }
        var seed = if (n == 0) 123456789L else kotlin.math.abs(n.toLong())
        val keyBuilder = StringBuilder()
        for (i in 0 until 32) {
            seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
            val charCode = 33 + (seed % 93).toInt()
            keyBuilder.append(charCode.toChar())
        }
        return keyBuilder.toString()
    }

    private fun decrypt(encHex: String): String {
        val timeStep = 3600000L
        val now = System.currentTimeMillis()
        val currentStep = now / timeStep

        var lastError: Exception? = null
        for (offset in intArrayOf(0, -1, 1)) {
            val step = currentStep + offset
            val key = generateKey(step)
            try {
                val encBytes = ByteArray(encHex.length / 2) { i ->
                    encHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                val decBytes = ByteArray(encBytes.size)
                var c = 42
                for (i in encBytes.indices) {
                    val p = encBytes[i].toInt() and 0xFF
                    val f = key[i % key.length].code
                    val k = p xor f xor (i * 13) xor c
                    decBytes[i] = (k and 0xFF).toByte()
                    c = (c + p) % 256
                }
                val decoded = String(decBytes, Charsets.UTF_8)
                return java.net.URLDecoder.decode(decoded, "UTF-8")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: RuntimeException("Decryption failed")
    }

    private fun transformPageUrl(page: String): String = when {
        page.contains("/uploads/") && !page.contains("/storage/uploads/") ->
            page.replace("/uploads/", "/storage/uploads/")
        page.contains("/upload/") && !page.contains("/storage/upload/") ->
            page.replace("/upload/", "/storage/upload/")
        else -> page
    }
}
