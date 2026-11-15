package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

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

import tsuki.util.FlightPage
import tsuki.util.NextJsData
import tsuki.util.await
import tsuki.util.generateUid
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseRaw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import tsuki.util.getTurnstileToken
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.EnumSet
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@MangaSourceParser("VORATOON", "VoraToon", "id")
internal class VoraToon(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.VORATOON, 30) {

    override val configKeyDomain = ConfigKey.Domain("voratoon.co")

    private val siteDomain: String
        get() = when {
            domain == "v8.voratoon.com" || domain == "voratoon.id" || domain == "voratoon.com" -> configKeyDomain.defaultValue
            else -> domain
        }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val isAppHost = request.url.host.endsWith("voratoon.com") ||
                request.url.host.endsWith("voratoon.co") ||
                request.url.host.endsWith("voratoon.id")
        val isStatic = request.url.encodedPath.startsWith("/_next/")

        if (response.code == 403 && isAppHost && !isStatic) {
            response.close()
            runCatching {
                chain.proceed(
                    request.newBuilder()
                        .url("https://$siteDomain/")
                        .headers(request.headers.newBuilder().removeAll("Accept").build())
                        .build(),
                ).close()
            }
            return chain.proceed(request)
        }
        return response
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$siteDomain/")
        .set("Origin", "https://$siteDomain")
        .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("sec-fetch-site", "same-origin")
        .build()

    private fun rscRequestHeaders(): Headers = getRequestHeaders().newBuilder()
        .set("Accept", "text/x-component")
        .set("RSC", "1")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
        isSearchWithFiltersSupported = true,
        isYearSupported = false,
    )

    @Volatile
    private var tagsCache: Set<MangaTag>? = null
    private val tagsMutex = Mutex()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = fetchTags()
        return MangaListFilterOptions(
            availableTags = tags,
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
            ),
        )
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        return tagsMutex.withLock {
            tagsCache ?: fetchTagsInternal().also { tagsCache = it }
        }
    }

    private suspend fun fetchTagsInternal(): Set<MangaTag> {
        val url = "https://$siteDomain/browse"
        val html = runCatching {
            webClient.httpGet(url, getRequestHeaders()).parseRaw()
        }.getOrNull() ?: return emptySet()

        return try {
            val pageData = NextJsData.findPage(html, "series", "seriesMeta")
                ?: return emptySet()
            val genresArray = pageData.data.optJSONArray("genres") ?: return emptySet()

            val tags = mutableSetOf<MangaTag>()
            for (i in 0 until genresArray.length()) {
                genresArray.optJSONObject(i)?.toGenreTagOrNull()?.let(tags::add)
            }
            tags
        } catch (_: Exception) {
            emptySet()
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$siteDomain/browse".let { base ->
            buildString {
                append(base)
                val params = mutableListOf<String>()
                params.add("page=$page")
                params.add(
                    "sort=${when (order) {
                        SortOrder.POPULARITY -> "popular"
                        SortOrder.RATING -> "rating"
                        SortOrder.NEWEST -> "newseries"
                        SortOrder.ALPHABETICAL -> "az"
                        else -> "new"
                    }}"
                )

                filter.query?.takeIf { it.isNotBlank() }?.let {
                    params.add("q=${URLEncoder.encode(it.trim(), "UTF-8")}")
                }

                if (filter.tags.isNotEmpty()) {
                    val genreParam = filter.tags.joinToString(",") {
                        it.title.lowercase(Locale.ROOT)
                    }
                    params.add("genre=${URLEncoder.encode(genreParam, "UTF-8")}")
                }

                filter.states.oneOrThrowIfMany()?.let { state ->
                    val status = when (state) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.PAUSED -> "hiatus"
                        MangaState.ABANDONED -> "cancelled"
                        else -> null
                    }
                    status?.let { params.add("status=$it") }
                }

                filter.types.oneOrThrowIfMany()?.let { type ->
                    val format = when (type) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        else -> null
                    }
                    format?.let { params.add("format=$it") }
                }

                append("?").append(params.joinToString("&"))
            }
        }

        val html = runCatching {
            webClient.httpGet(url, getRequestHeaders()).parseRaw()
        }.getOrNull() ?: return emptyList()

        val pageData = NextJsData.findPage(html, "series", "seriesMeta")
            ?: return emptyList()
        val seriesArray = pageData.data.optJSONArray("series") ?: return emptyList()

        return parseSeriesList(seriesArray, pageData)
    }

    private fun parseSeriesList(seriesArray: JSONArray, pageData: FlightPage): List<Manga> {
        val result = mutableListOf<Manga>()
        for (i in 0 until seriesArray.length()) {
            val item = seriesArray.optJSONObject(i) ?: continue
            val data = item.optJSONObject("data") ?: item
            val slug = data.optString("slug").takeIf { it.isNotBlank() } ?: continue
            val relativeUrl = "/series/$slug"

            val genres = mutableSetOf<MangaTag>()
            data.optJSONArray("genres")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.toGenreTagOrNull()?.let(genres::add)
                }
            }

            val cover = data.nonNullString("coverImage")?.let {
                if (it.startsWith("http")) it else "https://$siteDomain$it"
            }
            val background = data.nonNullString("backgroundImage")?.let {
                if (it.startsWith("http")) it else "https://$siteDomain$it"
            }

            result.add(
                Manga(
                    id = generateUid(relativeUrl),
                    url = relativeUrl,
                    publicUrl = "https://$siteDomain$relativeUrl",
                    title = pageData.textOrNull(data, "title")
                        ?: data.optString("title").ifBlank { slug.replace('-', ' ') },
                    altTitles = setOfNotNull(
                        pageData.textOrNull(data, "nativeTitle") ?: data.nonNullString("nativeTitle"),
                    ),
                    rating = data.toRating(),
                    contentRating = null,
                    coverUrl = cover,
                    largeCoverUrl = background ?: cover,
                    tags = genres,
                    state = data.toMangaState(),
                    authors = setOfNotNull(
                        pageData.textOrNull(data, "author") ?: data.nonNullString("author"),
                    ),
                    source = source,
                ),
            )
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")
        val url = "https://$siteDomain/series/$slug"
        val html = runCatching {
            webClient.httpGet(url, getRequestHeaders()).parseRaw()
        }.getOrNull() ?: return manga

        return try {
            val pageData = NextJsData.findPage(html, "series", "chapters")
                ?: return manga

            val seriesData = pageData.data.optJSONArray("series")
                ?.optJSONObject(0)?.optJSONObject("data")
                ?: return manga

            val title = pageData.textOrNull(seriesData, "title")
                ?: seriesData.nonNullString("title")
                ?: manga.title
            val nativeTitle = pageData.textOrNull(seriesData, "nativeTitle")
                ?: seriesData.nonNullString("nativeTitle")
            val synopsis = pageData.textOrNull(seriesData, "synopsis")
                ?: seriesData.nonNullString("synopsis")
                ?: ""
            val author = pageData.textOrNull(seriesData, "author")
                ?: seriesData.nonNullString("author")
            val coverImage = pageData.textOrNull(seriesData, "coverImage")
                ?: seriesData.nonNullString("coverImage")?.let {
                    if (it.startsWith("http")) it else "https://$siteDomain$it"
                }
            val backgroundImage = pageData.textOrNull(seriesData, "backgroundImage")
                ?: seriesData.nonNullString("backgroundImage")?.let {
                    if (it.startsWith("http")) it else "https://$siteDomain$it"
                }
            val format = (pageData.textOrNull(seriesData, "format")
                ?: seriesData.nonNullString("format"))?.lowercase(Locale.ROOT)

            val genres = mutableSetOf<MangaTag>()
            seriesData.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.toGenreTagOrNull()?.let(genres::add)
                }
            }

            val releaseYear = pageData.textOrNull(seriesData, "releaseDate")
                ?: seriesData.nonNullString("releaseDate")
            val extraTags = buildSet {
                releaseYear?.let { add(MangaTag(it, it, source)) }
                format?.let {
                    add(MangaTag(it.replaceFirstChar { c -> c.uppercase() }, it, source))
                }
            }

            val chaptersArray = pageData.data.optJSONArray("chapters")
            val chapters = chaptersArray?.let { parseChapters(it, slug, pageData) }
                ?.ifEmpty { null }
                ?: manga.chapters

            manga.copy(
                title = title,
                altTitles = setOfNotNull(nativeTitle),
                description = synopsis,
                state = seriesData.toMangaState() ?: manga.state,
                authors = setOfNotNull(author).ifEmpty { manga.authors },
                tags = genres + extraTags,
                coverUrl = coverImage ?: manga.coverUrl,
                largeCoverUrl = backgroundImage ?: coverImage ?: manga.largeCoverUrl,
                rating = seriesData.toRating(),
                chapters = chapters,
            )
        } catch (_: Exception) {
            manga
        }
    }

    private fun parseChapters(
        chaptersArray: JSONArray,
        slug: String,
        pageData: FlightPage,
    ): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.optJSONObject(i) ?: continue
            val chapterData = ch.optJSONObject("data")

            val chapterIndex = ch.optDouble("chapterIndex", Double.NaN).let { top ->
                if (top.isNaN()) chapterData?.optDouble("index", 0.0) ?: 0.0 else top
            }

            val formattedIndex = if (chapterIndex % 1.0 == 0.0) {
                chapterIndex.toInt().toString()
            } else {
                chapterIndex.toString()
            }
            val chapterUrl = "/series/$slug/chapter/$formattedIndex"

            val rawTitle = chapterData?.let { pageData.textOrNull(it, "title") }
                ?: chapterData?.nonNullString("title")
            val titleText = if (!rawTitle.isNullOrBlank()) {
                "Chapter $formattedIndex: $rawTitle"
            } else {
                "Chapter $formattedIndex"
            }
            val uploadDate = parseChapterDate(
                ch.optString("createdAt", ch.optString("updatedAt", "")),
            )

            chapters.add(
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = titleText,
                    url = chapterUrl,
                    number = chapterIndex.toFloat(),
                    volume = 0,
                    scanlator = null,
                    uploadDate = uploadDate,
                    branch = null,
                    source = source,
                ),
            )
        }
        return chapters.sortedWith(
            compareBy<MangaChapter> { it.number }
                .thenBy { it.uploadDate }
                .thenBy { it.id },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (slug, storedChapter) = extractChapterUrlParts(chapter.url) ?: return emptyList()
        val chapterUrl = "https://$siteDomain/series/$slug/chapter/$storedChapter"

        try {
            val pages = fetchChapterPages(chapterUrl)
            if (pages.isNotEmpty()) return pages
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }

        val legacyId = storedChapter.toLongOrNull()
        if (legacyId != null && legacyId > 100_000L) {
            val seriesUrl = "https://$siteDomain/series/$slug"
            val seriesHtml = runCatching {
                webClient.httpGet(seriesUrl, getRequestHeaders()).parseRaw()
            }.getOrNull()
            if (seriesHtml != null) {
                val seriesPage = NextJsData.findPage(seriesHtml, "series", "chapters")
                val chaptersArray = seriesPage?.data?.optJSONArray("chapters")
                if (chaptersArray != null) {
                    var legacyNumber: String? = null
                    for (i in 0 until chaptersArray.length()) {
                        val ch = chaptersArray.optJSONObject(i) ?: continue
                        if (ch.optLong("id") == legacyId) {
                            val num = ch.optDouble("chapterIndex", Double.NaN).let { top ->
                                if (top.isNaN()) {
                                    ch.optJSONObject("data")?.optDouble("index", 0.0) ?: 0.0
                                } else top
                            }
                            legacyNumber = if (num % 1.0 == 0.0) {
                                num.toInt().toString()
                            } else num.toString()
                            break
                        }
                    }
                    if (legacyNumber != null) {
                        try {
                            val pages = fetchChapterPages(
                                "https://$siteDomain/series/$slug/chapter/$legacyNumber",
                            )
                            if (pages.isNotEmpty()) return pages
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }

        try {
            val rscPages = fetchPagesFromRsc(chapterUrl)
            if (rscPages.isNotEmpty()) return rscPages
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }

        return fetchPagesFromWebView(chapterUrl)
    }

    @Volatile
    private var actionIdCache: String? = null

    private suspend fun fetchChapterPages(chapterUrl: String): List<MangaPage> {
        val html = webClient.httpGet(chapterUrl, getRequestHeaders()).parseRaw()

        val pageData = NextJsData.findPage(html, "chapter", "chapters")
            ?: NextJsData.findPage(html, "chapter")
        if (pageData != null) {
            val chapterObj = pageData.data.optJSONObject("chapter")
            extractPagesFromChapterObj(chapterObj)?.takeIf { it.isNotEmpty() }?.let {
                return it
            }

            val ticket = (chapterObj?.let { pageData.textOrNull(it, "pagesTicket") }
                ?: pageData.text("pagesTicket"))
                ?.ifBlank { null }
                ?: ticketRegex.find(html)?.value?.trim('"', '\\', ' ')

            if (!ticket.isNullOrBlank()) {
                unlockPages(chapterUrl, ticket, html)?.takeIf { it.isNotEmpty() }?.let {
                    return it
                }
            }
        } else {
            val fallbackTicket = ticketRegex.find(html)?.value?.trim('"', '\\', ' ')
            if (!fallbackTicket.isNullOrBlank()) {
                unlockPages(chapterUrl, fallbackTicket, html)?.takeIf { it.isNotEmpty() }?.let {
                    return it
                }
            }
        }

        val doc = Jsoup.parse(html)
        val imgElements = doc.select("img[data-chapter-page], [data-reader-chapter-id] img")
        if (imgElements.isNotEmpty()) {
            val pages = imgElements.mapNotNull { el ->
                val imgUrl = el.attr("data-chapter-page").ifBlank {
                    el.attr("data-src").ifBlank { el.attr("src") }
                }.trim()
                if (imgUrl.isBlank() || !imgUrl.startsWith("http")) null else makePage(imgUrl)
            }
            if (pages.isNotEmpty()) return pages
        }

        return chapterImageRegex.findAll(html)
            .map { it.value }
            .distinct()
            .filter { url ->
                val lower = url.lowercase(Locale.ROOT)
                "/logo/" !in lower && "/ads/" !in lower && "/icon" !in lower && "cover" !in lower && "background" !in lower
            }
            .map(::makePage)
            .toList()
    }

    private suspend fun fetchPagesFromRsc(chapterUrl: String): List<MangaPage> {
        val body = runCatching {
            webClient.httpGet(chapterUrl, rscRequestHeaders()).body?.string().orEmpty()
        }.getOrNull() ?: return emptyList()
        if (body.isEmpty() || body.trimStart().startsWith("<")) return emptyList()

        val pageData = runCatching {
            NextJsData.findPageRsc(body, "chapter")
        }.getOrNull() ?: return emptyList()

        val chapterObj = pageData.data.optJSONObject("chapter")
        extractPagesFromChapterObj(chapterObj)?.takeIf { it.isNotEmpty() }?.let {
            return it
        }

        val ticket = (chapterObj?.let { pageData.textOrNull(it, "pagesTicket") }
            ?: pageData.text("pagesTicket"))
            ?.ifBlank { null }
            ?: ticketRegex.find(body)?.value?.trim('"', '\\', ' ')

        if (!ticket.isNullOrBlank()) {
            unlockPages(chapterUrl, ticket, null)?.takeIf { it.isNotEmpty() }?.let {
                return it
            }
        }

        return emptyList()
    }

    private suspend fun resolveUnlockActionId(html: String?): String {
        actionIdCache?.let { return it }

        if (!html.isNullOrBlank()) {
            val scriptPath = pageScriptRegex.find(html)?.value
            if (scriptPath != null) {
                val scriptUrl = if (scriptPath.startsWith("http")) scriptPath else "https://$siteDomain$scriptPath"
                val scriptContent = runCatching {
                    webClient.httpGet(scriptUrl, getRequestHeaders()).parseRaw()
                }.getOrNull()
                if (scriptContent != null) {
                    val id = actionIdRegex.find(scriptContent)?.groupValues?.get(1)
                    if (id != null) {
                        actionIdCache = id
                        return id
                    }
                }
            }
        }
        return DEFAULT_UNLOCK_ACTION_ID
    }

    private suspend fun unlockPages(
        chapterUrl: String,
        ticket: String,
        html: String?,
    ): List<MangaPage>? {
        val actionId = resolveUnlockActionId(html)

        callUnlockAction(chapterUrl, actionId, ticket, null)
            ?.let(::parseActionImages)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val turnstileToken = runCatching {
            getTurnstileToken(
                context = context,
                url = chapterUrl,
                siteKey = TURNSTILE_SITE_KEY,
                action = "reader",
                timeoutMs = 45_000L,
            )
        }.getOrNull() ?: return null

        return callUnlockAction(chapterUrl, actionId, ticket, turnstileToken)
            ?.let(::parseActionImages)
            ?.takeIf { it.isNotEmpty() }
    }

    private suspend fun callUnlockAction(
        chapterUrl: String,
        actionId: String,
        ticket: String,
        turnstileToken: String?,
    ): String? {
        val payload = JSONArray().apply {
            put(ticket)
            if (turnstileToken != null) {
                put(turnstileToken)
            }
        }.toString()

        val httpUrl = chapterUrl.toHttpUrl()
        val origin = "${httpUrl.scheme}://${httpUrl.host}"

        val request = Request.Builder()
            .url(chapterUrl)
            .post(payload.toRequestBody("text/plain;charset=UTF-8".toMediaType()))
            .headers(
                getRequestHeaders().newBuilder()
                    .set("Accept", "text/x-component")
                    .set("Next-Action", actionId)
                    .set("Referer", chapterUrl)
                    .set("Origin", origin)
                    .build(),
            )
            .build()

        return runCatching {
            context.httpClient.newCall(request).await().use { resp ->
                resp.body?.string().orEmpty()
            }
        }.getOrNull()
    }

    private fun parseActionImages(responseBody: String?): List<MangaPage>? {
        if (responseBody.isNullOrBlank()) return null

        for (line in responseBody.lineSequence()) {
            val trimmed = line.trim()
            val colon = trimmed.indexOf(':')
            if (colon in 0..5) {
                val jsonStr = trimmed.substring(colon + 1)
                val json = runCatching { JSONObject(jsonStr) }.getOrNull() ?: continue

                val images = json.optJSONArray("images")
                    ?: json.optJSONObject("data")?.optJSONArray("images")
                    ?: json.optJSONObject("chapter")?.optJSONObject("data")?.optJSONArray("images")

                if (images != null && images.length() > 0) {
                    val pages = (0 until images.length()).mapNotNull { i ->
                        images.optString(i).takeIf { it.isNotBlank() }?.let(::makePage)
                    }
                    if (pages.isNotEmpty()) return pages
                }

                val dataImages = json.optJSONObject("dataImages")
                    ?: json.optJSONObject("data")?.optJSONObject("dataImages")
                if (dataImages != null) {
                    val pages = dataImages.keys().asSequence()
                        .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                        .mapNotNull { key ->
                            dataImages.optString(key).takeIf { it.isNotBlank() }?.let(::makePage)
                        }
                        .toList()
                    if (pages.isNotEmpty()) return pages
                }
            }
        }

        val payloads = runCatching { NextJsData.extractRscPayloads(responseBody) }.getOrNull()
        if (!payloads.isNullOrEmpty()) {
            val obj = NextJsData.findObjectInPayloads(payloads, "images")
            val images = obj?.optJSONArray("images")
            if (images != null && images.length() > 0) {
                val pages = (0 until images.length()).mapNotNull { i ->
                    images.optString(i).takeIf { it.isNotBlank() }?.let(::makePage)
                }
                if (pages.isNotEmpty()) return pages
            }
        }

        for (line in responseBody.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon in 0..5) {
                val content = line.substring(colon + 1).trim()
                if (content.startsWith("[") && content.endsWith("]")) {
                    val arr = runCatching { JSONArray(content) }.getOrNull() ?: continue
                    if (arr.length() > 0 && (0 until arr.length()).any { arr.optString(it).startsWith("http") }) {
                        val pages = (0 until arr.length()).mapNotNull { i ->
                            arr.optString(i).takeIf { it.startsWith("http") }?.let(::makePage)
                        }
                        if (pages.isNotEmpty()) return pages
                    }
                }
            }
        }

        return null
    }

    private suspend fun fetchPagesFromWebView(chapterUrl: String): List<MangaPage> {
        val script = """
            (() => new Promise(resolve => {
                const started = Date.now();
                const check = () => {
                    const imgs = Array.from(document.querySelectorAll('img[data-chapter-page], [data-reader-chapter-id] img'))
                        .map(img => img.getAttribute('data-chapter-page') || img.src)
                        .filter(src => src && (src.startsWith('http://') || src.startsWith('https://')));
                    if (imgs.length > 0) {
                        resolve(JSON.stringify(Array.from(new Set(imgs))));
                    } else if (Date.now() - started > 25000) {
                        resolve("");
                    } else {
                        setTimeout(check, 250);
                    }
                };
                check();
            }))();
        """.trimIndent()

        val output = try {
            withTimeoutOrNull(35_000L.milliseconds) {
                context.evaluateJs(chapterUrl, script)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

        parseJsImageList(output)?.takeIf { it.isNotEmpty() }?.let { urls ->
            return urls.map(::makePage)
        }

        try {
            context.requestBrowserAction(this, chapterUrl)
        } catch (_: UnsupportedOperationException) {
        }
        throw ParseException(
            "Selesaikan verifikasi Cloudflare di browser, lalu coba lagi: $chapterUrl",
            chapterUrl,
        )
    }

    private fun parseJsImageList(output: String?): List<String>? {
        if (output == null) return null
        val trimmed = output.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null

        return runCatching {
            val value = JSONTokener(trimmed).nextValue()
            val arr = when (value) {
                is JSONArray -> value
                is String -> JSONArray(value)
                else -> return@runCatching null
            }
            (0 until arr.length())
                .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrNull()
    }

    private fun extractPagesFromChapterObj(chapterObj: JSONObject?): List<MangaPage>? {
        if (chapterObj == null) return null

        chapterObj.optJSONObject("data")?.optJSONArray("images")?.let { images ->
            if (images.length() > 0) {
                return (0 until images.length()).mapNotNull { i ->
                    images.optString(i).takeIf { it.isNotBlank() }?.let(::makePage)
                }
            }
        }

        chapterObj.optJSONObject("dataImages")?.let { dataImages ->
            val pages = dataImages.keys().asSequence()
                .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNull { key ->
                    dataImages.optString(key).takeIf { it.isNotBlank() }?.let(::makePage)
                }
                .toList()
            if (pages.isNotEmpty()) return pages
        }

        return null
    }

    private fun makePage(url: String) = MangaPage(
        id = generateUid(url),
        url = url,
        preview = null,
        source = source,
    )

    private fun extractChapterUrlParts(url: String): Pair<String, String>? {
        val normalized = when {
            url.startsWith("http") ->
                url.substringAfter("://").substringAfter('/', missingDelimiterValue = "")
            else -> url.removePrefix("/")
        }.substringBefore('?').substringBefore('#')

        val parts = normalized.split("/").filter { it.isNotBlank() }
        if (parts.size < 4) return null
        if (parts[0] != "series") return null
        if (parts[2] != "chapter" && parts[2] != "chapters") return null
        return parts[1] to parts[3]
    }

    private fun FlightPage.textOrNull(obj: JSONObject, key: String): String? =
        text(obj, key)?.takeUnless { it.equals("null", ignoreCase = true) || it.isBlank() }

    private fun parseChapterDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        return runCatching {
            OffsetDateTime.parse(dateStr).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching {
                isoDateFormat.parse(dateStr)?.time ?: 0L
            }.getOrDefault(0L)
        }
    }

    private fun JSONObject.nonNullString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun JSONObject.toMangaState(): MangaState? {
        val status = nonNullString("status")?.lowercase(Locale.ROOT) ?: return null
        return when (status) {
            "ongoing", "publishing" -> MangaState.ONGOING
            "completed", "complete", "finished" -> MangaState.FINISHED
            "hiatus", "on hiatus" -> MangaState.PAUSED
            "canceled", "cancelled", "dropped", "axed" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun JSONObject.toRating(): Float {
        val raw = optDouble("rating", 0.0)
        return if (raw > 0.0) (raw / 10.0).toFloat().coerceIn(0f, 1f) else RATING_UNKNOWN
    }

    private fun JSONObject.toGenreTagOrNull(): MangaTag? {
        val name = optJSONObject("data")?.optString("name").orEmpty()
            .ifBlank { optString("name") }
            .trim()
        val id = optInt("id", -1)
        if (name.isEmpty() || id == -1) return null
        return MangaTag(title = name, key = id.toString(), source = source)
    }

    companion object {
        private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

        private const val DEFAULT_UNLOCK_ACTION_ID = "605b45501da4fac116927b939594513ffb6f865309"
        private const val TURNSTILE_SITE_KEY = "0x4AAAAAAEOmIHqZQLQ6GPRc"

        private val ticketRegex = Regex("""p1\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)+""")
        private val pageScriptRegex = Regex("""/_next/static/chunks/app/series/[^"'\s]+/page-[^"'\s]+\.js(?:\?[^"'\s]*)?""")
        private val actionIdRegex = Regex("""createServerReference\)\("([0-9a-f]{20,})"[^)]*"unlockChapterPages"""")

        private val chapterImageRegex = Regex("""https?://[^"'\s\\<>]*(?:voratoon)[^"'\s\\<>]*/[^"'\s\\<>?]+\.(?:jpg|jpeg|png|webp|avif)""")
    }
}
