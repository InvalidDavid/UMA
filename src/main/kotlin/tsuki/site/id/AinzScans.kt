package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.*

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.EnumSet
import java.util.TimeZone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup

@MangaSourceParser("AINZSCANS", "AinzScans", "id")
internal class AinzScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.AINZSCANS, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("v2.ainzscans01.com")

    private val apiBase = "https://api.ainzscans01.com/api"

    override val sourceLocale: Locale = Locale.US

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getOrFetchGenres().values.toSet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
        availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
    )

    @Volatile
    private var genresCache: Map<String, MangaTag>? = null
    private val genresMutex = Mutex()

    private suspend fun getOrFetchGenres(): Map<String, MangaTag> {
        genresCache?.let { return it }
        return genresMutex.withLock {
            genresCache ?: run {
                val jsonStr = webClient.httpGet("$apiBase/genres").body?.string().orEmpty()
                val map = mutableMapOf<String, MangaTag>()
                try {
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val name = obj.getString("name")
                        val slug = obj.getString("slug")
                        map[slug] = MangaTag(title = name, key = slug, source = source)
                    }
                } catch (_: Exception) { }
                genresCache = map
                map
            }
        }
    }

    private val detailsCacheLock = Any()

    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append(apiBase)
            append("/search?type=COMIC")
            append("&limit=").append(pageSize)
            append("&page=").append(page)

            filter.query?.takeIf { it.isNotBlank() }?.let {
                append("&q=").append(it.urlEncoded())
            }
            append("&sort=")
            append(
                when (order) {
                    SortOrder.UPDATED -> "latest"
                    SortOrder.POPULARITY -> "views"
                    SortOrder.NEWEST -> "new"
                    SortOrder.RATING -> "rate"
                    SortOrder.ALPHABETICAL -> "az"
                    else -> "latest"
                }
            )
            filter.states.oneOrThrowIfMany()?.let { state ->
                append("&status=")
                append(
                    when (state) {
                        MangaState.ONGOING -> "ONGOING"
                        MangaState.FINISHED -> "COMPLETED"
                        MangaState.PAUSED -> "HIATUS"
                        else -> ""
                    }
                )
            }
            filter.tags.firstOrNull()?.let { tag ->
                append("&genre=").append(tag.key)
            }
            filter.types.oneOrThrowIfMany()?.let { type ->
                append("&comic_type=")
                append(
                    when (type) {
                        ContentType.MANGA -> "MANGA"
                        ContentType.MANHWA -> "MANHWA"
                        ContentType.MANHUA -> "MANHUA"
                        else -> ""
                    }
                )
            }
        }

        val json = webClient.httpGet(url).body?.string().orEmpty()
        return parseListJson(json)
    }

    private fun parseListJson(json: String): List<Manga> {
        val result = mutableListOf<Manga>()
        try {
            val obj = JSONObject(json)
            val dataArray = obj.optJSONArray("data") ?: return emptyList()
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val slug = item.optString("slug").ifEmpty { continue }
                val relativeUrl = "/comic/$slug"
                val title = item.optString("title").ifEmpty { slug }
                val cover = item.optString("poster_image_url").nullIfEmpty()
                result.add(
                    Manga(
                        id = generateUid(relativeUrl),
                        url = relativeUrl,
                        publicUrl = "https://$domain$relativeUrl",
                        title = title,
                        coverUrl = cover,
                        rating = RATING_UNKNOWN,
                        contentRating = ContentRating.SAFE,
                        tags = emptySet(),
                        state = null,
                        authors = emptySet(),
                        altTitles = emptySet(),
                        source = source,
                    )
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        synchronized(detailsCacheLock) {
            detailsCache[manga.url]?.let { return it }
        }

        val slug = manga.url.substringAfter("/comic/")
        val json = webClient.httpGet("$apiBase/series/comic/$slug").body?.string().orEmpty()
        val result = parseDetailsJson(json, manga)

        synchronized(detailsCacheLock) {
            detailsCache[manga.url] = result
        }
        return result
    }

    private fun parseDetailsJson(json: String, manga: Manga): Manga {
        try {
            val obj = JSONObject(json)
            val slug = obj.optString("slug")
            val relativeUrl = "/comic/$slug"

            val title = obj.optString("title").ifEmpty { manga.title }
            val desc = obj.optString("synopsis").nullIfEmpty()?.let { Jsoup.parse(it).text() }
            val rating = obj.optString("rating_average").toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN
            val state = when (obj.optString("comic_status").uppercase()) {
                "ONGOING" -> MangaState.ONGOING
                "COMPLETED" -> MangaState.FINISHED
                "HIATUS" -> MangaState.PAUSED
                else -> null
            }
            val author = obj.optString("author_name").takeIf { it.isNotEmpty() }
            val artist = obj.optString("artist_name").takeIf { it.isNotEmpty() }
            val genres = obj.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val g = arr.getJSONObject(i)
                    val gSlug = g.optString("slug")
                    val gName = g.optString("name")
                    if (gSlug.isNotEmpty() && gName.isNotEmpty()) MangaTag(gName, gSlug, source) else null
                }.toSet()
            } ?: emptySet()

            val chapters = parseUnitsJson(obj.optJSONArray("units"), slug)

            return manga.copy(
                title = title,
                url = relativeUrl,
                publicUrl = "https://$domain$relativeUrl",
                description = desc,
                rating = rating,
                state = state,
                authors = setOfNotNull(author, artist),
                tags = genres,
                chapters = chapters,
            )
        } catch (_: Exception) {
            return manga
        }
    }

    private fun parseUnitsJson(array: JSONArray?, seriesSlug: String): List<MangaChapter> {
        if (array == null) return emptyList()
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val chSlug = obj.optString("slug").ifEmpty { continue }
            val number = obj.optString("number")
            val date = obj.optString("created_at")
            val uploadDate = parseDate(date)
            chapters.add(
                MangaChapter(
                    id = generateUid("/comic/$seriesSlug/chapter/$chSlug"),
                    url = "/comic/$seriesSlug/chapter/$chSlug",
                    title = "Chapter ${number.removeSuffix(".00")}",
                    number = number.toFloatOrNull() ?: (chapters.size + 1).toFloat(),
                    volume = 0,
                    uploadDate = uploadDate,
                    scanlator = null,
                    branch = null,
                    source = source,
                )
            )
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val slug = chapter.url.substringAfter("/comic/").substringBefore("/chapter/")
        val chSlug = chapter.url.substringAfterLast("/")
        val json = webClient.httpGet("$apiBase/series/comic/$slug/chapter/$chSlug").body?.string().orEmpty()
        try {
            val obj = JSONObject(json)
            val chapterObj = obj.optJSONObject("chapter") ?: return emptyList()
            val pagesArray = chapterObj.optJSONArray("pages") ?: return emptyList()
            return (0 until pagesArray.length()).mapNotNull { i ->
                val pageObj = pagesArray.getJSONObject(i)
                val rawUrl = pageObj.optString("image_url").nullIfEmpty() ?: return@mapNotNull null
                val fixedUrl = fixImageUrl(rawUrl)
                MangaPage(id = generateUid(fixedUrl), url = fixedUrl, preview = null, source = source)
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun fixImageUrl(url: String): String {
        var fixed = if (url.startsWith("http")) url else "https://api.ainzscans01.com$url"

        if (fixed.contains("googleusercontent.com") || fixed.contains("bp.blogspot.com")) {
            fixed = fixed.replace(Regex("""=[swh]\d+[^/?]*($|\?)""", RegexOption.IGNORE_CASE), "=s0$1")
                .replace(Regex("""/[swh]\d+[^/]*/""", RegexOption.IGNORE_CASE), "/s0/")
        }

        val httpUrl = fixed.toHttpUrlOrNull()
        if (httpUrl != null && httpUrl.queryParameterNames.any { it == "w" || it == "width" || it == "resize" }) {
            fixed = httpUrl.newBuilder()
                .removeAllQueryParameters("w")
                .removeAllQueryParameters("width")
                .removeAllQueryParameters("resize")
                .build()
                .toString()
        }
        return fixed
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            dateFormat.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun String?.nullIfEmpty(): String? = if (this.isNullOrEmpty()) null else this
}
