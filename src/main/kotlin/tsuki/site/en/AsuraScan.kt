package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
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

import tsuki.util.generateUid
import tsuki.util.nullIfEmpty
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseJson
import tsuki.util.parseSafe
import tsuki.util.toAbsoluteUrl
import tsuki.util.LinkResolver
import tsuki.util.json.mapJSONNotNull
import tsuki.util.json.mapJSONNotNullToSet
import tsuki.util.json.toStringSet
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("ASURASCANS", "Asura Scans", "en")
internal class AsuraScansParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ASURASCANS, pageSize = 20),
    Interceptor {

    override val configKeyDomain = ConfigKey.Domain("asurascans.com")

    private val apiUrl get() = "https://api.$domain/api"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RATING,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    private var tagsCache: Set<MangaTag>? = null

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.ABANDONED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    private suspend fun fetchTags(): Set<MangaTag> {
        tagsCache?.let { return it }
        val tags = webClient.httpGet("$apiUrl/genres")
            .parseJson()
            .optJSONArray("data")
            ?.toTags()
            .orEmpty()
        tagsCache = tags
        return tags
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "$apiUrl/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("offset", ((page - 1) * pageSize).toString())
            addQueryParameter("limit", pageSize.toString())

            if (!filter.query.isNullOrBlank()) {
                addQueryParameter("search", filter.query)
            }

            if (filter.tags.isNotEmpty()) {
                addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
            }

            filter.states.oneOrThrowIfMany()?.let { state ->
                addQueryParameter(
                    "status",
                    when (state) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.ABANDONED -> "dropped"
                        MangaState.PAUSED -> "hiatus"
                        else -> throw IllegalArgumentException("$state not supported")
                    },
                )
            }

            filter.types.oneOrThrowIfMany()?.let { type ->
                addQueryParameter(
                    "type",
                    when (type) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        else -> throw IllegalArgumentException("$type not supported")
                    },
                )
            }

            val (sort, direction) = when (order) {
                SortOrder.RATING -> "rating" to "desc"
                SortOrder.POPULARITY -> "popular" to "desc"
                SortOrder.NEWEST -> "update" to "desc"
                SortOrder.ALPHABETICAL_DESC -> "title" to "desc"
                SortOrder.ALPHABETICAL -> "title" to "asc"
                else -> "latest" to "desc"
            }
            addQueryParameter("sort", sort)
            addQueryParameter("order", direction)
        }.build()

        val data = webClient.httpGet(url).parseJson().optJSONArray("data")
            ?: return emptyList()
        return data.mapJSONNotNull { it.toManga() }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val response = webClient.httpGet(
            "$apiUrl/series/${manga.url.toSeriesSlug()}",
        ).parseJson()

        val json = (response.optJSONObject("data") ?: response)
            .getJSONObject("series")

        val details = json.toManga()
            ?: throw ParseException("No series in the response", manga.url)

        return manga.copy(
            title = details.title,
            altTitles = details.altTitles,
            publicUrl = details.publicUrl,
            coverUrl = details.coverUrl ?: manga.coverUrl,
            rating = details.rating,
            tags = details.tags,
            authors = details.authors,
            state = details.state,
            description = details.description,
            chapters = loadChapters(json.getString("slug")),
        )
    }

    private suspend fun loadChapters(slug: String): List<MangaChapter> {
        val data = webClient.httpGet("$apiUrl/series/$slug/chapters")
            .parseJson()
            .optJSONArray("data")
            ?: return emptyList()

        val dateFormat = newDateFormat()
        val now = System.currentTimeMillis()

        return data.mapJSONNotNull { json ->
            val isLocked = json.optBoolean("is_premium") ||
                    json.optBoolean("is_locked") ||
                    dateFormat.parseSafe(
                        json.optString("early_access_until").nullIfEmpty(),
                    ) > now

            if (isLocked) {
                return@mapJSONNotNull null
            }

            val number = json.optString("number")
                .removeSuffix(".0")
                .nullIfEmpty()
                ?: return@mapJSONNotNull null

            val url = "/comics/$slug/chapter/$number"
            val title = json.optString("title").nullIfEmpty()

            MangaChapter(
                id = generateUid(url),
                title = if (title != null) "Chapter $number - $title" else "Chapter $number",
                number = number.toFloatOrNull() ?: 0f,
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = dateFormat.parseSafe(
                    json.optString("published_at").nullIfEmpty()
                        ?: json.optString("created_at").nullIfEmpty(),
                ),
                branch = null,
                source = source,
            )
        }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (slug, number) = CHAPTER_URL_REGEX.find(chapter.url)?.destructured
            ?: throw ParseException(
                "Unexpected chapter url: ${chapter.url}",
                chapter.url,
            )

        val response = webClient.httpGet("$apiUrl/series/$slug/chapters/$number")
            .parseJson()
        val data = response.optJSONObject("data") ?: response
        val pages = data.optJSONObject("chapter")?.optJSONArray("pages")

        if (pages == null || pages.length() == 0) {
            if (data.optBoolean("is_locked")) {
                throw ParseException(
                    "This chapter is in early access, it can only be read " +
                            "with a paid account for now",
                    chapter.url,
                )
            }
            return emptyList()
        }

        return pages.mapJSONNotNull { json ->
            val url = json.optString("url").nullIfEmpty()
                ?: return@mapJSONNotNull null
            val tiles = json.optJSONArray("tiles")

            MangaPage(
                id = generateUid(url),
                url = if (tiles != null && tiles.length() > 1) {
                    val columns = json.optInt("tile_cols", DEFAULT_TILE_COLUMNS)
                    val rows = json.optInt("tile_rows", DEFAULT_TILE_ROWS)
                    "$url#$TILES_FRAGMENT${columns}x$rows:${tiles.join(",")}"
                } else {
                    url
                },
                preview = null,
                source = source,
            )
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val fragment = request.url.fragment

        if (fragment == null || !fragment.startsWith(TILES_FRAGMENT) || !response.isSuccessful) {
            return response
        }

        val (grid, order) = fragment.removePrefix(TILES_FRAGMENT)
            .split(':')
            .takeIf { it.size == 2 }
            ?: return response

        val columns = grid.substringBefore('x').toIntOrNull() ?: return response
        val rows = grid.substringAfter('x').toIntOrNull() ?: return response
        val tiles = order.split(',').map { it.toIntOrNull() ?: return response }

        if (columns <= 0 || rows <= 0 || tiles.any { it !in 0 until columns * rows }) {
            return response
        }

        return context.redrawImageResponse(response) { bitmap ->
            unscramble(bitmap, tiles, columns, rows)
        }
    }

    private fun unscramble(
        source: Bitmap,
        tiles: List<Int>,
        columns: Int,
        rows: Int,
    ): Bitmap {
        val tileWidth = source.width / columns
        val tileHeight = source.height / rows
        val result = context.createBitmap(tileWidth * columns, tileHeight * rows)

        for ((from, to) in tiles.withIndex()) {
            val srcX = (from % columns) * tileWidth
            val srcY = (from / columns) * tileHeight
            val dstX = (to % columns) * tileWidth
            val dstY = (to / columns) * tileHeight

            result.drawBitmap(
                source,
                Rect(srcX, srcY, srcX + tileWidth, srcY + tileHeight),
                Rect(dstX, dstY, dstX + tileWidth, dstY + tileHeight),
            )
        }

        return result
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        val segments = link.pathSegments
        if (segments.firstOrNull() != "comics") return null

        val slug = segments.getOrNull(1)
            ?.replace(SLUG_TOKEN_REGEX, "")
            ?.nullIfEmpty()
            ?: return null

        return resolver.resolveManga(this, url = "/comics/$slug")
    }

    private fun JSONObject.toManga(): Manga? {
        val slug = optString("slug").nullIfEmpty() ?: return null
        val url = "/comics/$slug"
        val rating = optDouble("rating", 0.0).toFloat()

        return Manga(
            id = generateUid(url),
            url = url,
            publicUrl = url.toAbsoluteUrl(domain),
            coverUrl = optString("cover").nullIfEmpty(),
            title = optString("title").nullIfEmpty() ?: return null,
            altTitles = optJSONArray("alt_titles")?.toStringSet().orEmpty(),
            rating = if (rating > 0f) (rating / 10f).coerceAtMost(1f) else RATING_UNKNOWN,
            tags = optJSONArray("genres")?.toTags().orEmpty(),
            authors = setOfNotNull(
                optString("author").trim().nullIfEmpty(),
                optString("artist").trim().nullIfEmpty(),
            ),
            state = when (optString("status").lowercase(Locale.ENGLISH)) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "hiatus" -> MangaState.PAUSED
                "dropped", "axed" -> MangaState.ABANDONED
                else -> null
            },
            description = optString("description").nullIfEmpty(),
            source = source,
            contentRating = if (isNsfwSource) ContentRating.ADULT else null,
        )
    }

    private fun JSONArray.toTags(): Set<MangaTag> = mapJSONNotNullToSet { json ->
        MangaTag(
            key = json.optString("slug").nullIfEmpty()
                ?: return@mapJSONNotNullToSet null,
            title = json.optString("name").nullIfEmpty()
                ?: return@mapJSONNotNullToSet null,
            source = source,
        )
    }

    private fun String.toSeriesSlug(): String {
        val segments = substringBefore('?')
            .substringBefore('#')
            .split('/')
            .filter { it.isNotEmpty() }

        val index = segments.indexOfFirst { it == "comics" || it == "series" }
        return segments.getOrNull(index + 1)
            ?: throw ParseException("Unexpected series url: $this", this)
    }

    private fun newDateFormat() = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss",
        Locale.ROOT,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private companion object {
        private const val TILES_FRAGMENT = "tiles="
        private const val DEFAULT_TILE_COLUMNS = 4
        private const val DEFAULT_TILE_ROWS = 5

        private val SLUG_TOKEN_REGEX = Regex("""-[0-9a-f]{8}$""")
        private val CHAPTER_URL_REGEX = Regex(
            """/(?:comics|series)/([^/]+)/chapter/([^/?#]+)""",
        )
    }
}
