package tsuki.site.all

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

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
import tsuki.model.ContentType

import tsuki.util.generateUid
import tsuki.util.parseJson
import tsuki.util.parseRaw
import tsuki.util.parseSafe
import tsuki.util.urlBuilder

import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Created from: https://github.com/glitch-228
 * Modified from: InvalidDavid
 */

@MangaSourceParser("KAGANE", "Kagane")
internal class Kagane(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAGANE, pageSize = 35) {

    override val configKeyDomain = ConfigKey.Domain("kagane.to")
    private val apiUrl = "https://kagane.to"

    private val dataSaverKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            "false" to "Normal",
            "true" to "Data saving"
        ),
        defaultValue = "false",
    )

    private val isDataSaver: Boolean
        get() = config[dataSaverKey] == "true"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(dataSaverKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_ASC,
        SortOrder.RATING,
        SortOrder.RATING_ASC,
        SortOrder.UPDATED,
        SortOrder.UPDATED_ASC,
        SortOrder.NEWEST,
        SortOrder.NEWEST_ASC,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
    )

    private fun Locale.toKaganeLangCode(): String {
        if (language == "pt" && country.equals("br", ignoreCase = true)) return "pt-BR"
        if (language == "es" && country == "419") return "es-419"
        if (language == "zh" && country.equals("CN", ignoreCase = true)) return "zh-Hans"
        if (language == "zh" && country.equals("TW", ignoreCase = true)) return "zh-Hant"
        return language
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = genresMutex.withLock {
            genresCache ?: fetchGenres().also { genresCache = it }
        }
        return MangaListFilterOptions(
            availableTags = genres,
            availableContentRating = EnumSet.of(
                ContentRating.SAFE,
                ContentRating.SUGGESTIVE,
                ContentRating.ADULT,
            ),
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.COMICS,
                ContentType.OTHER,
            ),
            availableLocales = KAGANE_LANGS,
        )
    }

    @Volatile
    private var genresCache: Set<MangaTag>? = null
    private val genresMutex = Mutex()

    private suspend fun fetchGenres(): Set<MangaTag> {
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()
        return try {
            val raw = webClient.httpGet("$apiUrl/api/v2/genres/list", headers).parseRaw()
            val genres = runCatching { JSONArray(raw) }.getOrElse {
                val wrapper = runCatching { JSONObject(raw) }.getOrNull()
                wrapper?.optJSONArray("content")
                    ?: wrapper?.optJSONArray("genres")
                    ?: JSONArray()
            }
            buildSet {
                for (i in 0 until genres.length()) {
                    val item = genres.optJSONObject(i) ?: continue
                    val id = item.optGenreId()
                    val title = item.optGenreName()
                    if (id.isNotBlank() && title.isNotBlank() && UUID_REGEX.matches(id)) {
                        add(MangaTag(title, id, source))
                    }
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun parseContentRating(value: String?): ContentRating? {
        return when (value?.lowercase(Locale.ROOT)) {
            "safe" -> ContentRating.SAFE
            "suggestive" -> ContentRating.SUGGESTIVE
            "adult", "erotica", "pornographic" -> ContentRating.ADULT
            else -> null
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.RELEVANCE -> ""
            SortOrder.POPULARITY -> "total_views,desc"
            SortOrder.POPULARITY_ASC -> "total_views,asc"
            SortOrder.RATING -> "avg_views,desc"
            SortOrder.RATING_ASC -> "avg_views,asc"
            SortOrder.UPDATED -> "updated_at,desc"
            SortOrder.UPDATED_ASC -> "updated_at,asc"
            SortOrder.NEWEST -> "created_at,desc"
            SortOrder.NEWEST_ASC -> "created_at,asc"
            SortOrder.ALPHABETICAL -> "series_name,asc"
            SortOrder.ALPHABETICAL_DESC -> "series_name,desc"
            else -> "updated_at,desc"
        }

        val urlBuilder = "$apiUrl/api/v2/search/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("size", pageSize.toString())
        if (sortParam.isNotBlank()) {
            urlBuilder.addQueryParameter("sort", sortParam)
        }
        if (!filter.query.isNullOrEmpty()) {
            urlBuilder.addQueryParameter("exact", "1")
        }
        val url = urlBuilder.build().toString()
        val jsonBody = JSONObject()
        if (!filter.query.isNullOrEmpty()) {
            jsonBody.put("title", filter.query)
        }
        jsonBody.put("source_type", JSONArray().apply {
            put("Official")
            put("Unofficial")
            put("Mixed")
        })
        jsonBody.put("content_lang", JSONArray().apply {
            val selectedLocale = filter.locale
            if (selectedLocale != null) {
                put(selectedLocale.toKaganeLangCode())
            } else {
                KAGANE_LANGS.forEach(::put)
            }
        })

        val genreIds = filter.tags.map { it.key }.filter { UUID_REGEX.matches(it) }
        if (genreIds.isNotEmpty()) {
            val genresArr = JSONArray()
            genreIds.forEach { genresArr.put(it) }
            val genresObj = JSONObject()
            genresObj.put("values", genresArr)
            genresObj.put("match_all", false)
            jsonBody.put("genres", genresObj)
        }
        if (filter.tagsExclude.isNotEmpty()) {
            val excludedGenreIds = filter.tagsExclude.map { it.key }.filter { UUID_REGEX.matches(it) }
            if (excludedGenreIds.isNotEmpty()) {
                val genresObj = jsonBody.optJSONObject("genres") ?: JSONObject().also {
                    jsonBody.put("genres", it)
                }
                genresObj.put("exclude", JSONArray().apply {
                    excludedGenreIds.forEach(::put)
                })
            }
        }
        jsonBody.put("content_rating", JSONArray().apply {
            val ratings = filter.contentRating.ifEmpty {
                EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT)
            }
            if (ContentRating.SAFE in ratings) put("Safe")
            if (ContentRating.SUGGESTIVE in ratings) put("Suggestive")
            if (ContentRating.ADULT in ratings) {
                put("Erotica")
                put("Pornographic")
            }
        })

        if (filter.states.isNotEmpty()) {
            val statuses = JSONArray()
            filter.states.forEach { state ->
                when (state) {
                    MangaState.ONGOING -> statuses.put("Ongoing")
                    MangaState.FINISHED -> statuses.put("Completed")
                    MangaState.PAUSED -> statuses.put("Hiatus")
                    MangaState.ABANDONED -> statuses.put("Abandoned")
                    else -> Unit
                }
            }
            if (statuses.length() > 0) {
                jsonBody.put("upload_status", statuses)
            }
        }

        if (filter.types.isNotEmpty()) {
            val formats = JSONArray()
            filter.types.forEach { type ->
                when (type) {
                    ContentType.MANGA -> formats.put("Manga")
                    ContentType.MANHWA -> formats.put("Manhwa")
                    ContentType.MANHUA -> formats.put("Manhua")
                    ContentType.COMICS -> formats.put("Comic")
                    ContentType.OTHER -> formats.put("Other")
                    else -> Unit
                }
            }
            if (formats.length() > 0) {
                jsonBody.put("format", formats)
            }
        }

        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()

        val responseBody = try {
            requestWithCloudflareRetry(url) {
                webClient.httpPost(url.toHttpUrl(), jsonBody, headers).parseRaw()
            }
        } catch (e: HttpStatusException) {
            if (e.statusCode == 403 || e.statusCode == 429 || e.statusCode == 503) {
                requestCloudflareVerification(url, e)
            } else {
                throw e
            }
        } catch (e: ParseException) {
            val causeMessage = e.message.orEmpty() + " " + (e.cause?.message.orEmpty())
            if (causeMessage.contains("CloudFlare", ignoreCase = true) ||
                causeMessage.contains("cf-mitigated", ignoreCase = true) ||
                causeMessage.contains("cf-error-details", ignoreCase = true)
            ) {
                requestCloudflareVerification(url, e)
            } else {
                throw e
            }
        }

        if (responseBody.isCloudflareChallenge()) {
            requestCloudflareVerification(url)
        }

        val response = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            throw Exception("Invalid JSON search response: $responseBody")
        }

        val content = response.optJSONArray("content")
            ?: response.optJSONObject("result")?.optJSONArray("items")
            ?: return emptyList()

        return (0 until content.length()).mapNotNull { i ->
            val item = content.getJSONObject(i)
            val id = item.optString("id").ifBlank { item.optString("series_id") }
            if (id.isBlank()) return@mapNotNull null
            val name = item.optString("name").ifBlank { item.optString("title") }.ifBlank { return@mapNotNull null }
            val src = item.optString("source").ifBlank { item.optString("source_name") }
            val title = if (src.isNotEmpty()) "$name [$src]" else name
            val coverImageId = item.optString("cover_image_id").ifBlank { item.optString("coverImageId") }
            val coverUrl = if (coverImageId.isNotBlank()) {
                "$apiUrl/api/v2/image/$coverImageId"
            } else {
                "$apiUrl/api/v2/series/$id/thumbnail"
            }

            Manga(
                id = generateUid(id),
                url = id,
                publicUrl = "https://$domain/series/$id",
                coverUrl = coverUrl,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = parseContentRating(item.optString("content_rating")),
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val seriesId = manga.url
        val url = "$apiUrl/api/v2/series/$seriesId"
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()
        val resp = requestWithCloudflareRetry(url) {
            webClient.httpGet(url, headers)
        }
        val respBody = resp.body?.string() ?: ""
        if (!resp.isSuccessful) throw Exception("Details error ${resp.code}: $respBody")
        val json = try {
            JSONObject(respBody)
        } catch (_: Exception) {
            throw Exception("Invalid JSON details: $respBody")
        }

        val state = when (
            json.optString("publication_status")
                .ifBlank { json.optString("upload_status") }
                .ifBlank { json.optString("status") }
                .uppercase(Locale.ROOT)
        ) {
            "ONGOING" -> MangaState.ONGOING
            "COMPLETED", "ENDED" -> MangaState.FINISHED
            "HIATUS" -> MangaState.PAUSED
            "ABANDONED", "CANCELLED", "CANCELED", "DROPPED" -> MangaState.ABANDONED
            else -> null
        }

        val genres = json.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                when (val item = arr.opt(i)) {
                    is String -> {
                        if (UUID_REGEX.matches(item)) {
                            MangaTag(item, item, source)
                        } else {
                            null
                        }
                    }
                    is JSONObject -> {
                        val key = item.optGenreId()
                        val name = item.optGenreName()
                        if (key.isNotBlank() && name.isNotBlank()) {
                            MangaTag(name, key, source)
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }.toSet()
        } ?: emptySet()

        val authors = linkedSetOf<String>()
        json.optJSONArray("authors")?.let { arr ->
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is String -> item.takeIf { it.isNotBlank() }?.let(authors::add)
                    is JSONObject -> item.optString("name")
                        .ifBlank { item.optString("title") }
                        .takeIf { it.isNotBlank() }
                        ?.let(authors::add)
                }
            }
        }
        if (authors.isEmpty()) {
            json.optJSONArray("series_staff")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val staff = arr.optJSONObject(i) ?: continue
                    val role = staff.optString("role")
                    if (
                        role.contains("author", ignoreCase = true) ||
                        role.contains("story", ignoreCase = true) ||
                        role.contains("artist", ignoreCase = true) ||
                        role.contains("art", ignoreCase = true)
                    ) {
                        staff.optString("name").takeIf { it.isNotBlank() }?.let(authors::add)
                    }
                }
            }
        }

        val altTitles = json.optJSONArray("series_alternate_titles")?.let { arr ->
            buildSet {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    item.optString("title").takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        } ?: emptySet()

        val description = buildString {
            json.optString("description")
                .ifBlank { json.optString("summary") }
                .takeIf { it.isNotBlank() }
                ?.let {
                    append(it.trim())
                }
            if (altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Associated Name(s):\n")
                altTitles.forEach {
                    append(it)
                    append('\n')
                }
            }
        }.trim().ifBlank { null }

        val coverUrl = json.optJSONArray("series_covers")
            ?.optJSONObject(0)
            ?.optString("image_id")
            ?.takeIf { it.isNotBlank() }
            ?.let { "$apiUrl/api/v2/image/$it" }
            ?: manga.coverUrl

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        fun parseChapters(content: JSONArray): List<MangaChapter> {
            val chapters = ArrayList<MangaChapter>(content.length())
            for (i in 0 until content.length()) {
                val ch = content.optJSONObject(i) ?: continue
                val chId = ch.optString("book_id")
                    .ifBlank { ch.optString("id") }
                    .ifBlank { ch.optString("bookId") }
                if (chId.isBlank()) continue
                val chapterNo = ch.optString("chapter_no")
                val chapterNumber = chapterNo.toChapterNumberOrNull()
                    ?: ch.optDouble("number", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                val sortNumber = ch.optDouble("sort_no", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                    ?: ch.optDouble("number_sort", ch.optDouble("numberSort", Double.NaN)).takeIf { !it.isNaN() }?.toFloat()
                val number = when {
                    sortNumber != null && chapterNumber != null && sortNumber >= chapterNumber -> sortNumber
                    sortNumber != null && chapterNumber == null -> sortNumber
                    chapterNumber != null -> chapterNumber
                    else -> 0f
                }
                val rawTitle = ch.optString("title").ifBlank { ch.optString("name") }.trim()
                val chTitle = rawTitle.ifBlank {
                    chapterNo.takeIf { it.isNotBlank() }?.let { "Ch.$it" }.orEmpty()
                }.ifBlank { "Chapter $number" }
                val volume = ch.optString("volume_no")
                    .ifBlank { ch.optString("volume") }
                    .toIntOrNull() ?: 0
                val dateStr = ch.optString("published_on")
                    .ifBlank { ch.optString("release_date") }
                    .ifBlank { ch.optString("releaseDate") }
                    .ifBlank { ch.optString("created_at") }
                val groups = ch.optJSONArray("groups")
                chapters.add(
                    MangaChapter(
                        id = generateUid("$seriesId:$chId"),
                        title = chTitle,
                        number = number,
                        volume = volume,
                        url = "/series/$seriesId/reader/$chId",
                        uploadDate = dateFormat.parseSafe(dateStr),
                        source = source,
                        scanlator = groups?.let { arr ->
                            buildList {
                                for (j in 0 until arr.length()) {
                                    arr.optJSONObject(j)?.optString("title")?.takeIf { it.isNotBlank() }?.let(::add)
                                }
                            }.joinToString().ifBlank { null }
                        },
                        branch = null,
                    ),
                )
            }
            return chapters.sortedWith(
                compareBy<MangaChapter> { it.number <= 0f }
                    .thenBy { it.number }
                    .thenBy { it.volume }
                    .thenBy { it.title.orEmpty() },
            )
        }

        var chapters = parseChapters(
            json.optJSONArray("series_books")
                ?: json.optJSONArray("seriesBooks")
                ?: json.optJSONArray("books")
                ?: json.optJSONArray("content")
                ?: JSONArray(),
        )
        if (chapters.isEmpty()) {
            val chaptersUrl = "$apiUrl/api/v2/series/$seriesId/books/list"
            val chapterResp = webClient.httpGet(chaptersUrl, headers).parseJson()
            chapters = parseChapters(
                chapterResp.optJSONArray("series_books")
                    ?: chapterResp.optJSONArray("seriesBooks")
                    ?: chapterResp.optJSONArray("content")
                    ?: JSONArray(),
            )
        }

        return manga.copy(
            title = json.optString("title").ifBlank { manga.title },
            altTitles = altTitles,
            coverUrl = coverUrl,
            description = description,
            state = state,
            authors = authors,
            tags = genres,
            chapters = chapters,
            contentRating = parseContentRating(json.optString("content_rating")) ?: manga.contentRating,
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        return emptyList()
    }

    private var cacheUrl = "https://kstatic.to"
    private var accessToken: String = ""

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val uri = URI(chapter.url)
        val pathParts = uri.path.split("/").filter { it.isNotEmpty() }
        if (pathParts.size < 4) throw Exception("Invalid chapter URL format: ${chapter.url}")

        val chapterId = pathParts.last()
        val challenge = getChallengeResponse(chapterId)
        accessToken = challenge.optString("access_token").ifBlank {
            challenge.optString("accessToken")
        }.ifBlank {
            throw Exception("Invalid challenge response: missing access token")
        }
        cacheUrl = challenge.optString("cache_url").ifBlank {
            challenge.optString("cacheUrl")
        }.ifBlank {
            throw Exception("Invalid challenge response: missing cache url")
        }

        val pages = parseManifestPages(challenge)
        if (pages.isEmpty()) {
            throw Exception("Invalid challenge response: missing pages manifest")
        }

        return pages.sortedBy { it.pageNumber }.map { page ->
            val ext = page.ext?.takeIf { it.isNotBlank() } ?: "jxl"
            val imageUrl = "$cacheUrl/api/v2/books/page".toHttpUrl().newBuilder().apply {
                if (isDataSaver) addPathSegment("datasaver")
                addPathSegment(chapterId)
                addPathSegment("${page.pageUuid}.$ext")
                addQueryParameter("token", accessToken)
                addQueryParameter("is_datasaver", isDataSaver.toString())
            }.build().toString()

            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun <T> requestWithCloudflareRetry(url: String, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            if (!e.isCloudflareProtectionError()) {
                throw e
            }
            delay(CLOUDFLARE_RETRY_DELAY_MS.milliseconds)
            try {
                return block()
            } catch (retryError: Exception) {
                if (e.isCloudflareBlock() || retryError.isCloudflareBlock()) {
                    requestCloudflareVerification(url, retryError)
                }
                throw retryError
            }
        }
    }

    private fun Throwable.isCloudflareBlock(): Boolean {
        val name = javaClass.simpleName
        return name.contains("CloudflareBlocked", ignoreCase = true) ||
                name.contains("CloudFlareBlocked") ||
                (isCloudflareProtectionError() && message.orEmpty().contains("blocked", ignoreCase = true))
    }

    private fun Throwable.isCloudflareProtectionError(): Boolean {
        val name = javaClass.simpleName
        if (name.contains("Cloudflare", ignoreCase = true) || name.contains("CloudFlare")) {
            return true
        }
        val message = "${message.orEmpty()} ${cause?.message.orEmpty()}"
        return message.contains("cf-mitigated", ignoreCase = true) ||
                message.contains("Just a moment", ignoreCase = true) ||
                message.contains("challenges.cloudflare.com", ignoreCase = true) ||
                message.contains("cf-error-details", ignoreCase = true) ||
                message.contains("cf-chl-bypass", ignoreCase = true)
    }

    private fun requestCloudflareVerification(url: String, cause: Throwable? = null): Nothing {
        try {
            context.requestBrowserAction(this, "https://$domain/")
        } catch (e: UnsupportedOperationException) {
            throw ParseException(
                "Cloudflare verification required. Open Kagane in WebView and retry.",
                url,
                cause ?: e,
            )
        }
    }

    private fun String.isCloudflareChallenge(): Boolean {
        return contains("cf-mitigated", ignoreCase = true)
                || contains("Just a moment", ignoreCase = true)
                || contains("challenges.cloudflare.com", ignoreCase = true)
                || contains("/cdn-cgi/challenge-platform/", ignoreCase = true)
                || contains("Sorry, you have been blocked", ignoreCase = true)
                || contains("cf-error-details", ignoreCase = true)
                || contains("cf-chl-bypass", ignoreCase = true)
    }

    private data class ManifestPage(
        val pageNumber: Int,
        val pageUuid: String,
        val ext: String?,
    )

    private fun parseManifestPages(challenge: JSONObject): List<ManifestPage> {
        val pagesJson = challenge.optJSONObject("manifest")?.optJSONArray("pages")
            ?: challenge.optJSONArray("pages")
            ?: JSONArray()
        return buildList {
            for (i in 0 until pagesJson.length()) {
                val page = pagesJson.optJSONObject(i) ?: continue
                val pageUuid = page.optString("page_id")
                    .ifBlank { page.optString("pageId") }
                    .ifBlank { page.optString("page_uuid") }
                    .ifBlank { page.optString("pageUuid") }
                if (pageUuid.isBlank()) continue
                add(
                    ManifestPage(
                        pageNumber = page.optInt(
                            "page_no",
                            page.optInt("pageNo", page.optInt("page_number", i + 1)),
                        ),
                        pageUuid = pageUuid,
                        ext = page.optString("ext").ifBlank { null },
                    ),
                )
            }
        }
    }

    private var integrityToken: String = ""
    private var integrityTokenExp: Long = 0L

    private val isIntegrityTokenValid: Boolean
        get() = integrityToken.isNotBlank() && System.currentTimeMillis() < integrityTokenExp

    private suspend fun getIntegrityToken(): String {
        if (isIntegrityTokenValid) {
            return integrityToken
        }
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()

        val response = requestWithCloudflareRetry("$apiUrl/api/integrity") {
            webClient.httpPost(
                urlBuilder().addPathSegments("api/integrity").build(),
                JSONObject(),
                headers,
            ).parseJson()
        }

        val token = response.optString("token")
        if (token.isBlank()) {
            throw Exception("Failed to retrieve integrity token")
        }
        integrityToken = token
        integrityTokenExp = response.optLong("exp", 0L) * 1000L
        return integrityToken
    }

    private suspend fun getChallengeResponse(chapterId: String): JSONObject {
        val integrityToken = getIntegrityToken()
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .add("x-integrity-token", integrityToken)
            .build()
        val challengeUrl = "$apiUrl/api/v2/books/$chapterId?is_datasaver=$isDataSaver"
        return requestWithCloudflareRetry(challengeUrl) {
            webClient.httpPost(challengeUrl.toHttpUrl(), JSONObject(), headers).parseJson()
        }
    }

    private fun String.toChapterNumberOrNull(): Float? = trim()
        .replace(',', '.')
        .toFloatOrNull()

    private fun getIntegrityTokenBlocking(): String {
        if (isIntegrityTokenValid) {
            return integrityToken
        }
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .build()
        val request = Request.Builder()
            .url(urlBuilder().addPathSegments("api/integrity").build())
            .post(JSONObject().toString().toRequestBody("application/json".toMediaType()))
            .headers(headers)
            .build()
        context.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Integrity token request failed ${response.code}")
            }
            val json = JSONObject(response.body?.string() ?: "")
            val token = json.optString("token")
            if (token.isBlank()) {
                throw IOException("Failed to retrieve integrity token")
            }
            integrityToken = token
            integrityTokenExp = json.optLong("exp", 0L) * 1000L
        }
        return integrityToken
    }

    private fun refreshAccessTokenBlocking(chapterId: String) {
        val integrityToken = getIntegrityTokenBlocking()
        val headers = getRequestHeaders().newBuilder()
            .add("Origin", "https://$domain")
            .add("Referer", "https://$domain/")
            .add("x-integrity-token", integrityToken)
            .build()
        val challengeUrl = "$apiUrl/api/v2/books/$chapterId?is_datasaver=$isDataSaver".toHttpUrl()
        val request = Request.Builder()
            .url(challengeUrl)
            .post(JSONObject().toString().toRequestBody("application/json".toMediaType()))
            .headers(headers)
            .build()
        context.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Challenge refresh failed ${response.code}")
            }
            val json = JSONObject(response.body?.string() ?: "")
            accessToken = json.optString("access_token").ifBlank {
                json.optString("accessToken")
            }.ifBlank {
                throw IOException("Invalid challenge response: missing access token")
            }
            cacheUrl = json.optString("cache_url").ifBlank {
                json.optString("cacheUrl")
            }.ifBlank {
                throw IOException("Invalid challenge response: missing cache url")
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val host = url.host
        var requestBuilder = request.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("cf-connecting-ip")
        if (host == domain || host.endsWith(".$domain")) {
            requestBuilder = requestBuilder
                .header("Origin", "https://$domain")
                .header("Referer", "https://$domain/")
        }
        val newRequest = requestBuilder.build()

        var response = chain.proceed(newRequest)

        if (url.queryParameterNames.contains("token") &&
            (response.code == 401 || response.code == 403 || response.code == 507)
        ) {
            response.close()
            val segments = url.pathSegments
            val chapterId = segments.getOrNull(4)
            if (chapterId != null) {
                runCatching { refreshAccessTokenBlocking(chapterId) }.onSuccess {
                    val retryRequest = newRequest.newBuilder()
                        .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                        .build()
                    response = chain.proceed(retryRequest)
                }
            }
        }
        return response
    }

     companion object {
        private const val CLOUDFLARE_RETRY_DELAY_MS = 6_000L

        private val UUID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )

        private val KAGANE_LANGS: Set<Locale> = setOf(
            Locale("af"),
            Locale("ar"),
            Locale("az"),
            Locale("be"),
            Locale("bg"),
            Locale("bn"),
            Locale("ca"),
            Locale("cs"),
            Locale("cv"),
            Locale("da"),
            Locale.GERMAN,
            Locale("el"),
            Locale.ENGLISH,
            Locale("eo"),
            Locale("es"),
            Locale("es","419"),
            Locale("et"),
            Locale("eu"),
            Locale("fa"),
            Locale("fi"),
            Locale("fil"),
            Locale.FRENCH,
            Locale("ga"),
            Locale("he"),
            Locale("hi"),
            Locale("hr"),
            Locale("hu"),
            Locale("id"),
            Locale.ITALIAN,
            Locale.JAPANESE,
            Locale("jv"),
            Locale("ka"),
            Locale("kk"),
            Locale.KOREAN,
            Locale("la"),
            Locale("lt"),
            Locale("mn"),
            Locale("ms"),
            Locale("my"),
            Locale("ne"),
            Locale("nl"),
            Locale("no"),
            Locale("pl"),
            Locale("pt"),
            Locale("pt", "br"),
            Locale("ro"),
            Locale("ru"),
            Locale("sk"),
            Locale("sl"),
            Locale("sq"),
            Locale("sr"),
            Locale("sv"),
            Locale("ta"),
            Locale("te"),
            Locale("th"),
            Locale("tr"),
            Locale("uk"),
            Locale("ur"),
            Locale("uz"),
            Locale("vi"),
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
        )

        private fun JSONObject.optGenreId(): String = optString("genre_id").ifBlank { optString("id") }

        private fun JSONObject.optGenreName(): String =
            optString("genre_name")
                .ifBlank { optString("genreName") }
                .ifBlank { optString("name") }
                .ifBlank { optString("title") }
    }
}
