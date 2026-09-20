package tsuki.site.fr.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.network.OkHttpWebClient

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

import tsuki.util.generateUid
import tsuki.util.json.extractNextJsRsc

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.Instant
import java.util.EnumSet
import java.util.LinkedHashSet

@MangaSourceParser("ORTEGASCANS", "Ortega Scans", "fr", ContentType.HENTAI)
internal class OrtegaScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ORTEGASCANS, 18) {

    override val configKeyDomain = ConfigKey.Domain("ortegascans.fr")
    private val baseUrl = "https://$domain"

    override val webClient = OkHttpWebClient(context.httpClient, source)


    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GENRES.mapTo(LinkedHashSet()) {
            MangaTag(title = it, key = it, source = source)
        },
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
    )

    override fun getRequestHeaders() = super.getRequestHeaders()
        .newBuilder()
        .add("Referer", "$baseUrl/")
        .build()

    private val rscHeaders
        get() = getRequestHeaders().newBuilder().add("rsc", "1").build()


    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sort = when (order) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.ALPHABETICAL -> "alpha"
            SortOrder.UPDATED, SortOrder.NEWEST -> "recent"
            else -> "popular"
        }

        val url = "$baseUrl/api/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", pageSize.toString())
            addQueryParameter("page", page.toString())
            addQueryParameter("search", filter.query.orEmpty())
            addQueryParameter("tags", filter.tags.joinToString(",") { it.key })
            addQueryParameter("status", filter.states.joinToString(",") { it.toApiStatus() })
            addQueryParameter("sort", sort)
            addQueryParameter("minChapters", "0")
            addQueryParameter("isOrtegaOnly", "false")
            addQueryParameter("unreadOnly", "false")
            addQueryParameter("maxChapters", "9999")
        }.build()

        val body = webClient.httpGet(url.toString()).body?.string() ?: return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()

        val result = ArrayList<Manga>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            result.add(parseSeries(item))
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val slug = manga.url
        val url = "$baseUrl/serie/$slug"

        val relatedDeferred = async {
            runCatching { fetchRelatedMangaInternal(slug) }.getOrDefault(emptyList())
        }

        val body = webClient.httpGet(url, rscHeaders).body?.string()
            ?: throw ParseException("Empty response", url)

        val dto = extractMangaDto(body)
            ?: throw ParseException("Cannot extract manga details", url)

        val updated = manga.copy(
            title = dto.title,
            coverUrl = coverUrl(dto.slug),
            description = buildDescription(dto),
            authors = setOfNotNull(dto.author, dto.artist),
            state = parseState(dto.status),
            rating = dto.rating,
            tags = dto.categories.mapTo(LinkedHashSet()) {
                MangaTag(title = it, key = it, source = source)
            },
            contentRating = ContentRating.ADULT,
            chapters = dto.chapters
                .map { convertChapter(it, slug) }
                .sortedBy { it.number },
        )
        relatedDeferred.await()
        updated
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
                url = if (imgUrl.startsWith("http")) imgUrl else "$baseUrl$imgUrl",
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url


    override suspend fun getRelatedManga(seed: Manga): List<Manga> = fetchRelatedMangaInternal(seed.url)

    private suspend fun fetchRelatedMangaInternal(slug: String): List<Manga> {
        val url = "$baseUrl/serie/$slug"
        val body = webClient.httpGet(url).body?.string() ?: return emptyList()
        val doc = Jsoup.parse(body, url)
        val section = doc.selectFirst("section:has(h2:containsOwn(Vous aimeriez))")
            ?: return emptyList()

        return section.select("a[href^=\"/serie/\"]").mapNotNull { element ->
            val href = element.absUrl("href")
            val title = element.selectFirst("h3")?.text() ?: return@mapNotNull null
            val relatedSlug = href.toHttpUrl().pathSegments.getOrNull(1) ?: return@mapNotNull null
            Manga(
                id = generateUid(relatedSlug),
                url = relatedSlug,
                publicUrl = "$baseUrl/serie/$relatedSlug",
                title = title,
                altTitles = emptySet(),
                coverUrl = element.selectFirst("img")?.attr("abs:src").orEmpty(),
                largeCoverUrl = null,
                description = null,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                source = source,
            )
        }
    }

    private fun extractImages(body: String): List<String>? {
        val urlFields = listOf("url", "link")

        // array of { url | link } objects.
        fun readUrls(arr: JSONArray?): List<String>? {
            if (arr == null || arr.length() == 0) return null
            val result = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val value = urlFields
                    .asSequence()
                    .map { item.optString(it) }
                    .firstOrNull { it.isNotBlank() && it != "null" }
                    ?: continue
                result.add(value)
            }
            return result.takeIf { it.isNotEmpty() }
        }

        // { "images": [ { url | link } ] }
        val objectPredicate: (Any) -> Boolean = { value ->
            value is JSONObject && value.optJSONArray("images")?.let { arr ->
                arr.length() > 0 && (arr.opt(0) as? JSONObject)?.let { first ->
                    urlFields.any { first.has(it) }
                } == true
            } == true
        }
        (body.extractNextJsRsc(objectPredicate) as? JSONObject)
            ?.optJSONArray("images")
            ?.let(::readUrls)
            ?.let { return it }

        // raw [ { url | link } ]
        val arrayPredicate: (Any) -> Boolean = { value ->
            value is JSONArray && value.length() > 0 &&
                    (value.opt(0) as? JSONObject)?.let { first ->
                        urlFields.any { first.has(it) }
                    } == true
        }
        (body.extractNextJsRsc(arrayPredicate) as? JSONArray)
            ?.let(::readUrls)
            ?.let { return it }

        val regex = Regex("""https?://[^\s"'<>\\]+\.(?:jpg|jpeg|png|webp|gif)[^\s"'<>\\]*""")
        val hits = LinkedHashSet<String>()
        for (m in regex.findAll(body)) {
            hits.add(m.value.replace("\\", ""))
        }
        return hits.takeIf { it.isNotEmpty() }?.toList()
    }

    private fun extractMangaDto(body: String): MangaDto? {
        val predicate: (Any) -> Boolean = { value ->
            value is JSONObject &&
                    value.optJSONObject("manga")?.has("chapters") == true
        }
        val obj = body.extractNextJsRsc(predicate) as? JSONObject ?: return null
        val mangaObj = obj.optJSONObject("manga") ?: return null
        val rating = normalizeRating(
            obj.optJSONObject("stats")?.optDouble("averageRating", 0.0) ?: 0.0,
        )
        return parseMangaDto(mangaObj, rating)
    }

    private fun parseSeries(obj: JSONObject): Manga {
        val slug = obj.optString("slug")
        return Manga(
            id = generateUid(slug),
            url = slug,
            publicUrl = "$baseUrl/serie/$slug",
            title = obj.optString("title"),
            altTitles = emptySet(),
            coverUrl = coverUrl(slug),
            largeCoverUrl = null,
            description = null,
            tags = emptySet(),
            authors = emptySet(),
            state = null,
            rating = normalizeRating(obj.optDouble("rating", 0.0)),
            contentRating = ContentRating.ADULT,
            source = source,
        )
    }

    private fun parseMangaDto(obj: JSONObject, rating: Float): MangaDto {
        val categories = obj.optJSONArray("categories")?.let { arr ->
            List(arr.length()) { arr.optString(it) }
        }.orEmpty()

        val chapters = obj.optJSONArray("chapters")?.let { arr ->
            val list = ArrayList<ChapterDto>(arr.length())
            for (i in 0 until arr.length()) {
                val ch = arr.optJSONObject(i) ?: continue
                list.add(
                    ChapterDto(
                        id = ch.optString("id"),
                        number = ch.optDouble("number", 0.0).toFloat(),
                        title = ch.optCleanString("title"),
                        isPremium = ch.optBoolean("isPremium", false),
                        createdAt = ch.optString("createdAt"),
                    ),
                )
            }
            list
        }.orEmpty()

        return MangaDto(
            id = obj.optString("id"),
            title = obj.optString("title"),
            slug = obj.optString("slug"),
            description = obj.optCleanString("description"),
            coverImage = obj.optString("coverImage"),
            status = obj.optCleanString("status"),
            author = obj.optCleanString("author"),
            artist = obj.optCleanString("artist"),
            alternativeNames = obj.optCleanString("alternativeNames"),
            categories = categories,
            chapters = chapters,
            rating = rating,
        )
    }

    private fun convertChapter(dto: ChapterDto, mangaSlug: String): MangaChapter {
        val numberStr = dto.number.toString().removeSuffix(".0")
        val titleText = buildString {
            if (dto.isPremium) append("🔒 ")
            append("Chapitre ")
            append(numberStr)
            if (!dto.title.isNullOrBlank()) {
                append(" - ")
                append(dto.title)
            }
        }
        return MangaChapter(
            id = generateUid(dto.id),
            title = titleText,
            number = dto.number,
            volume = 0,
            url = "/serie/$mangaSlug/chapter/$numberStr",
            uploadDate = parseDate(dto.createdAt),
            scanlator = null,
            branch = null,
            source = source,
        )
    }

    /**
     * AI supported:
     * The site serves covers at /api/covers/<slug>.webp regardless of what
     * the `coverImage` field in the JSON says. The JSON value is unreliable:
     * it may carry a hash suffix (`slug_4edb3c70.webp`) or point at a totally
     * different filename (`i-report-regarding-gender.webp` for
     * `performance-sex-report`). The site's own HTML uses the slug-based URL.
     */
    private fun coverUrl(slug: String): String = "$baseUrl/api/covers/$slug.webp"

    private fun buildDescription(dto: MangaDto): String? {
        val parts = listOfNotNull(
            dto.description?.takeIf { it.isNotBlank() },
            dto.alternativeNames?.takeIf { it.isNotBlank() }
                ?.let { "Noms alternatifs : $it" },
        )
        return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun parseState(status: String?): MangaState? = when (status?.lowercase()) {
        "en cours", "ongoing" -> MangaState.ONGOING
        "terminé", "complete" -> MangaState.FINISHED
        "en pause", "on hold" -> MangaState.PAUSED
        "annulé", "canceled" -> MangaState.ABANDONED
        else -> null
    }

    private fun MangaState.toApiStatus(): String = when (this) {
        MangaState.ONGOING -> "en cours"
        MangaState.FINISHED -> "terminé"
        MangaState.PAUSED -> "en pause"
        MangaState.ABANDONED -> "annulé"
        else -> ""
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val cleaned = raw.removePrefix($$"$D")
        return runCatching { Instant.parse(cleaned).toEpochMilli() }
            .recoverCatching { Instant.parse(cleaned.take(19) + "Z").toEpochMilli() }
            .getOrDefault(0L)
    }

    private fun normalizeRating(raw: Double): Float =
        if (raw > 0.0) (raw / 5.0).toFloat().coerceIn(0f, 1f) else RATING_UNKNOWN

    private fun JSONObject.optCleanString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private data class ChapterDto(
        val id: String,
        val number: Float,
        val title: String?,
        val isPremium: Boolean,
        val createdAt: String,
    )

    private data class MangaDto(
        val id: String,
        val title: String,
        val slug: String,
        val description: String?,
        val coverImage: String,
        val status: String?,
        val author: String?,
        val artist: String?,
        val alternativeNames: String?,
        val categories: List<String>,
        val chapters: List<ChapterDto>,
        val rating: Float,
    )

    private companion object {
        private val GENRES: List<String> = listOf(
            "Action", "Aventure", "Comédie", "Drame", "Esclave", "Fantaisie", "Fétichisme",
            "Hardcore", "Harem", "Humiliation", "Hypnose", "Isekai", "Mature", "MILF",
            "Partenaire", "Pouvoir", "Revanche", "Romance", "Seinen", "Sport", "Surnaturel",
            "Système", "Tranche de Vie", "Vie Scolaire",
        )
    }
}
