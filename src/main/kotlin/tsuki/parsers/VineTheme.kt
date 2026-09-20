package tsuki.parsers

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import tsuki.MangaLoaderContext
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
import tsuki.network.OkHttpWebClient
import tsuki.util.generateUid
import tsuki.util.json.extractNextJs
import tsuki.util.json.extractNextJsRsc
import java.time.Instant
import java.util.EnumSet
import java.util.LinkedHashSet

abstract class VineTheme(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    pageSize: Int = 24,
): PagedMangaParser(context, source, pageSize) {

    override val configKeyDomain = ConfigKey.Domain(domain)
    protected val baseUrl = "https://$domain"

    override val webClient = OkHttpWebClient(context.httpClient, source)

    override fun getRequestHeaders() = super.getRequestHeaders()
        .newBuilder()
        .add("Referer", "$baseUrl/")
        .build()

    private val rscHeaders
        get() = getRequestHeaders().newBuilder().add("rsc", "1").build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING,
        SortOrder.RELEVANCE
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = runCatching { fetchGenres() }.getOrDefault(emptyList())
        return MangaListFilterOptions(
            availableTags = genres.mapTo(LinkedHashSet()) {
                MangaTag(title = it.name, key = it.slug, source = source)
            },
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED,
                MangaState.UPCOMING,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
        )
    }

    private suspend fun fetchGenres(): List<Genre> {
        val body = webClient.httpGet("$baseUrl/api/genres").body?.string() ?: return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("genres") ?: return emptyList()

        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val slug = obj.optString("slug").ifBlank {
                    obj.optJSONObject("genre")?.optString("slug").orEmpty()
                }.takeIf { it.isNotBlank() } ?: continue
                val name = obj.optString("name").ifBlank { slug }
                add(Genre(name = name.stripEmoji(), slug = slug))
            }
        }
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val sort = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.RELEVANCE -> "trending"
            SortOrder.NEWEST -> "newest"
            SortOrder.RATING -> "rating"
            SortOrder.UPDATED -> "updated"
            else -> "updated"
        }

        val url = "$baseUrl/api/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", pageSize.toString())
            addQueryParameter("contentMode", "comics")
            addQueryParameter("sort", sort)

            filter.query?.takeIf { it.isNotBlank() }?.let {
                addQueryParameter("q", it)
            }

            val statuses = filter.states
                .mapNotNull { it.toApiStatus() }
                .filter { it.isNotBlank() }
            if (statuses.isNotEmpty()) {
                addQueryParameter("status", statuses.joinToString(","))
            }

            val types = filter.types
                .mapNotNull { it.toApiType() }
                .filter { it.isNotBlank() }
            if (types.isNotEmpty()) {
                addQueryParameter("type", types.joinToString(","))
            }

            val genres = filter.tags
                .map { it.key.ifBlank { it.title } }
                .filter { it.isNotBlank() }
            if (genres.isNotEmpty()) {
                addQueryParameter("genre", genres.joinToString(","))
            }

            addQueryParameter("page", page.toString())
        }.build()

        val body = webClient.httpGet(url.toString()).body?.string() ?: return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()

        val excluded = filter.tagsExclude
            .flatMap { listOf(it.title, it.key) }
            .map { it.lowercase().trim() }
            .filter { it.isNotBlank() }
            .toSet()

        return buildList(data.length()) {
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                if (excluded.isNotEmpty() && hasExcludedCategory(obj, excluded)) continue
                add(parseMangaSummary(obj))
            }
        }
    }

    private fun hasExcludedCategory(obj: JSONObject, excludedLowercase: Set<String>): Boolean {
        val arr = obj.optJSONArray("categories")
            ?: obj.optJSONArray("genres")
            ?: return false

        for (i in 0 until arr.length()) {
            val entry = arr.opt(i) ?: continue
            val candidates: List<String> = when (entry) {
                is String -> listOf(entry)
                is JSONObject -> listOfNotNull(
                    entry.optString("name").takeIf { it.isNotBlank() },
                    entry.optString("slug").takeIf { it.isNotBlank() },
                    entry.optJSONObject("genre")?.optString("slug")?.takeIf { it.isNotBlank() },
                )
                else -> emptyList()
            }
            for (raw in candidates) {
                if (raw.lowercase().trim() in excludedLowercase) return true
            }
        }
        return false
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val slug = manga.url
        val url = "$baseUrl/series/comic/$slug?sort=desc"

        val relatedDeferred = async {
            runCatching { fetchRelatedInternal(slug) }.getOrDefault(emptyList())
        }

        val body = webClient.httpGet(url, rscHeaders).body?.string()
            ?: throw ParseException("Empty response", url)

        val detailObj = extractDetailObject(body)
            ?: throw ParseException("Cannot extract manga details", url)

        val seriesObj = detailObj.optJSONObject("series")
            ?: throw ParseException("Missing series object", url)

        val updated = manga.copy(
            title = seriesObj.optString("title", manga.title),
            coverUrl = resolveCover(seriesObj, manga.coverUrl),
            description = buildDescription(seriesObj) ?: manga.description,
            authors = parseTeam(seriesObj),
            state = parseState(seriesObj.optString("status")) ?: manga.state,
            rating = normalizeRating(seriesObj.optDouble("rating", 0.0)),
            tags = parseTags(seriesObj),
            contentRating = null,
            chapters = collectChapters(detailObj, slug),
        )
        relatedDeferred.await()
        updated
    }

    /**
     * Reads the chapter list from the detail object and — if the API paginates
     * and the first page returned nothing — walks the remaining pages.
     */
    private suspend fun collectChapters(detailObj: JSONObject, slug: String): List<MangaChapter> {
        val firstPage = detailObj.optJSONArray("chapters").asObjectList()
        val totalPages = detailObj.optInt("totalPages", 1)

        val allChapters = if (firstPage.isEmpty() && totalPages > 1) {
            buildList {
                for (p in 2..totalPages) {
                    val pageUrl = "$baseUrl/series/comic/$slug?sort=desc&page=$p"
                    val pageBody = runCatching {
                        webClient.httpGet(pageUrl, rscHeaders).body?.string()
                    }.getOrNull() ?: continue
                    val pageObj = extractDetailObject(pageBody) ?: continue
                    addAll(pageObj.optJSONArray("chapters").asObjectList())
                }
            }
        } else {
            firstPage
        }

        return buildChapterList(allChapters, slug)
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "$baseUrl${chapter.url}"
        val body = webClient.httpGet(url, rscHeaders).body?.string()
            ?: throw ParseException("Empty response", url)

        val images = extractImages(body)
            ?: throw ParseException("Cannot extract page list", url)

        return images.mapIndexed { idx, imgUrl ->
            MangaPage(
                id = generateUid("${chapter.id}-$idx"),
                url = imgUrl.toAbsoluteUrl(baseUrl),
                preview = null,
                source = source,
            )
        }
    }

    private fun extractImages(body: String): List<String>? {
        // Strategy 1: { chapter: { pages: [ … ] } }
        extractJsonObject(body) { obj ->
            obj.optJSONObject("chapter")?.optJSONArray("pages").hasPageEntries()
        }?.optJSONObject("chapter")
            ?.optJSONArray("pages")
            ?.let(::readPageUrls)
            ?.let { return it }

        // Strategy 2: { pages: [ … ] }
        extractJsonObject(body) { obj ->
            obj.optJSONArray("pages").hasPageEntries()
        }?.optJSONArray("pages")
            ?.let(::readPageUrls)
            ?.let { return it }

        // Strategy 3: raw [ { imageUrl | url | link } ]
        val rscArrayPredicate: (Any) -> Boolean = { value ->
            value is JSONArray && value.hasPageEntries()
        }
        val rawArray = runCatching {
            body.extractNextJsRsc(rscArrayPredicate) as? JSONArray
        }.getOrNull() ?: runCatching {
            Jsoup.parse(body).extractNextJs(rscArrayPredicate) as? JSONArray
        }.getOrNull()
        rawArray?.let(::readPageUrls)?.let { return it }

        // Strategy 4: regex fallback
        val hits = LinkedHashSet<String>()
        for (m in IMAGE_URL_REGEX.findAll(body)) {
            hits.add(m.value.replace("\\", ""))
        }
        return hits.takeIf { it.isNotEmpty() }?.toList()
    }

    /** True if the array is non-empty and its first element is an object with a page URL field. */
    private fun JSONArray?.hasPageEntries(): Boolean {
        if (this == null || length() == 0) return false
        val first = opt(0) as? JSONObject ?: return false
        return PAGE_URL_FIELDS.any { first.has(it) }
    }

    /** Reads the first non-blank URL from each object in the array, using PAGE_URL_FIELDS priority. */
    private fun readPageUrls(arr: JSONArray?): List<String>? {
        if (arr == null || arr.length() == 0) return null
        val result = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val value = PAGE_URL_FIELDS
                .asSequence()
                .map { item.optString(it) }
                .firstOrNull { it.isNotBlank() && it != "null" }
                ?: continue
            result.add(value)
        }
        return result.takeIf { it.isNotEmpty() }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = fetchRelatedInternal(seed.url)

    private suspend fun fetchRelatedInternal(slug: String): List<Manga> {
        val url = "$baseUrl/series/comic/$slug?sort=desc"
        val body = runCatching {
            webClient.httpGet(url, rscHeaders).body?.string()
        }.getOrNull() ?: return emptyList()

        val relatedArr = extractSimilarSeries(body) ?: return emptyList()
        return buildList(relatedArr.length()) {
            for (i in 0 until relatedArr.length()) {
                relatedArr.optJSONObject(i)?.let { add(parseMangaSummary(it)) }
            }
        }
    }

    private fun extractDetailObject(body: String): JSONObject? = extractJsonObject(body) { it.has("series") && it.has("chapters") }

    private fun extractSimilarSeries(body: String): JSONArray? = extractJsonObject(body) { it.has("similarSeries") }?.optJSONArray("similarSeries")

    private inline fun extractJsonObject(
        body: String,
        crossinline predicate: (JSONObject) -> Boolean,
    ): JSONObject? {
        val rscPredicate: (Any) -> Boolean = { value ->
            value is JSONObject && predicate(value)
        }
        return body.extractNextJsRsc(rscPredicate) as? JSONObject
            ?: runCatching {
                Jsoup.parse(body).extractNextJs(rscPredicate) as? JSONObject
            }.getOrNull()
    }

    private fun parseMangaSummary(obj: JSONObject): Manga {
        val slug = obj.optString("slug").ifBlank { obj.optString("id") }
        return Manga(
            id = generateUid(slug),
            url = slug,
            publicUrl = "$baseUrl/series/comic/$slug",
            title = obj.optString("title"),
            altTitles = emptySet(),
            coverUrl = coverFromObject(obj) ?: "",
            largeCoverUrl = null,
            description = null,
            tags = emptySet(),
            authors = emptySet(),
            state = parseState(obj.optString("status")),
            rating = normalizeRating(obj.optDouble("rating", 0.0)),
            contentRating = null,
            source = source,
        )
    }

    private fun resolveCover(seriesObj: JSONObject, fallback: String?): String? =
        coverFromObject(seriesObj) ?: fallback

    private fun coverFromObject(obj: JSONObject): String? {
        val raw = obj.optString("coverImage").takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        return raw.toAbsoluteUrl(baseUrl)
    }

    private fun parseTeam(obj: JSONObject): Set<String> {
        val name = obj.optJSONObject("team")
            ?.optString("name")
            ?.takeIf { it.isNotBlank() && it != "null" }
        return setOfNotNull(name)
    }

    private fun parseTags(obj: JSONObject): Set<MangaTag> {
        val result = LinkedHashSet<MangaTag>()

        obj.optString("type").takeIf { it.isNotBlank() }?.let {
            result.add(MangaTag(title = it, key = it, source = source))
        }
        obj.optString("origin").takeIf { it.isNotBlank() }?.let {
            result.add(MangaTag(title = it, key = it, source = source))
        }
        if (obj.optBoolean("isMature", false)) {
            result.add(MangaTag(title = "Mature", key = "Mature", source = source))
        }
        obj.optJSONArray("genres").asObjectList().forEach { g ->
            val name = g.optString("name").ifBlank {
                g.optJSONObject("genre")?.optString("slug").orEmpty()
            }
            if (name.isNotBlank()) {
                result.add(MangaTag(title = name.stripEmoji(), key = name, source = source))
            }
        }
        return result
    }

    private fun buildDescription(obj: JSONObject): String? {
        val parts = mutableListOf<String>()

        obj.optString("description").takeIf { it.isNotBlank() && it != "null" }?.let {
            parts.add(htmlToText(it))
        }

        val altTitles = buildList {
            obj.optString("originalTitle").takeIf { it.isNotBlank() && it != "null" }?.let(::add)
            obj.optJSONArray("aliases").asStringList()
                .filter { it.isNotBlank() }
                .forEach(::add)
        }.distinct()

        if (altTitles.isNotEmpty()) {
            parts.add("Alternative titles:\n" + altTitles.joinToString("\n") { "- $it" })
        }

        return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    /**
     * Builds the chapter list, skipping locked chapters and de-duplicating by
     * chapter ID. Sorted newest-first.
     */
    private fun buildChapterList(chapters: List<JSONObject>, slug: String): List<MangaChapter> {
        val seen = HashSet<String>()
        return chapters
            .asSequence()
            .mapNotNull { ch ->
                if (ch.optBoolean("isLocked", false)) return@mapNotNull null

                val id = ch.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null

                val number = ch.optDouble("number", 0.0).toFloat()
                val numberStr = number.toString().removeSuffix(".0")
                val rawTitle = ch.optString("title").takeIf { it.isNotBlank() && it != "null" }
                val titleText = if (rawTitle.isNullOrBlank() || rawTitle == numberStr) {
                    "Chapter $numberStr"
                } else {
                    rawTitle
                }

                MangaChapter(
                    id = generateUid(id),
                    title = titleText,
                    number = number,
                    volume = 0,
                    url = "/series/comic/$slug/chapter/$numberStr",
                    uploadDate = parseDate(ch.optString("publishedAt")),
                    scanlator = null,
                    branch = null,
                    source = source,
                )
            }
            .sortedBy { it.number }
            .toList()
    }

    private fun MangaState.toApiStatus(): String? = when (this) {
        MangaState.ONGOING -> "Ongoing"
        MangaState.FINISHED -> "Completed"
        MangaState.PAUSED -> "Hiatus"
        MangaState.ABANDONED -> "Dropped"
        MangaState.UPCOMING -> "Upcoming"
        else -> null
    }

    private fun ContentType.toApiType(): String? = when (this) {
        ContentType.MANHWA -> "MANHWA"
        ContentType.MANHUA -> "MANHUA"
        ContentType.MANGA -> "MANGA"
        else -> null
    }

    private fun parseState(status: String?): MangaState? = when (status?.uppercase()) {
        "ONGOING" -> MangaState.ONGOING
        "COMPLETED" -> MangaState.FINISHED
        "HIATUS" -> MangaState.PAUSED
        "CANCELLED", "DROPPED", "DISCONTINUED" -> MangaState.ABANDONED
        else -> null
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank() || raw == "null") return 0L
        val cleaned = raw.removePrefix($$"$D")
        return runCatching { Instant.parse(cleaned).toEpochMilli() }
            .recoverCatching { Instant.parse(cleaned.take(19) + "Z").toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun normalizeRating(raw: Double): Float =
        if (raw > 0.0) (raw / 5.0).toFloat().coerceIn(0f, 1f) else RATING_UNKNOWN

    private data class Genre(val name: String, val slug: String)
}

private val PAGE_URL_FIELDS = listOf("imageUrl", "url", "link")

private val IMAGE_URL_REGEX = Regex("""https?://[^\s"'<>\\]+\.(?:jpg|jpeg|png|webp|gif)[^\s"'<>\\]*""")

private val NON_ASCII_REGEX = Regex("[^\\p{ASCII}\\p{L}0-9\\- ]+")

private fun JSONArray?.asObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (i in 0 until length()) {
            optJSONObject(i)?.let(::add)
        }
    }
}

private fun JSONArray?.asStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (i in 0 until length()) {
            optString(i).let(::add)
        }
    }
}

private fun String.stripEmoji(): String = replace(NON_ASCII_REGEX, "").trim()

private fun String.toAbsoluteUrl(baseUrl: String): String = if (startsWith("http")) this else "$baseUrl$this"

private fun htmlToText(html: String): String {
    val doc = Jsoup.parseBodyFragment(html)
    doc.select("a[href]").forEach { link ->
        val text = link.text()
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        link.replaceWith(TextNode(if (text.isBlank()) href else "$text ($href)"))
    }
    doc.select("br").forEach { it.replaceWith(TextNode("\n")) }
    doc.select("p").forEach { it.after("\n\n") }
    return doc.wholeText().trim()
}
