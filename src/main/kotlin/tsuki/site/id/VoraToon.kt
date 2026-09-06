package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

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
import tsuki.util.oneOrThrowIfMany

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("VORATOON", "VoraToon", "id")
internal class VoraToon(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.VORATOON, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("v1.voratoon.com")

    private val apiBase = "https://$domain/backend"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .add("Accept", "application/json")
        .add("Accept-language", "en-US,en;q=0.9,id;q=0.8")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_ASC,
        SortOrder.UPDATED,
        SortOrder.UPDATED_ASC,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.RATING,
        SortOrder.RATING_ASC,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
        isSearchSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genreMap = fetchGenreMap()
        return MangaListFilterOptions(
            availableTags = genreMap.values.toSet(),
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

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.POPULARITY -> "popularity" to "desc"
            SortOrder.POPULARITY_ASC -> "popularity" to "asc"
            SortOrder.UPDATED -> "latest" to "desc"
            SortOrder.UPDATED_ASC -> "updated" to "asc"
            SortOrder.ALPHABETICAL -> "alphabetical" to "asc"
            SortOrder.ALPHABETICAL_DESC -> "alphabetical" to "desc"
            SortOrder.RATING -> "rating" to "desc"
            SortOrder.RATING_ASC -> "rating" to "asc"
            else -> null
        }

        val url = buildString {
            append(apiBase)
            append("/series?includeMeta=true")
            append("&take=").append(pageSize)
            append("&page=").append(page)

            sortParam?.let { (field, dir) ->
                append("&sort=").append(field)
                append("&sortOrder=").append(dir)
            }

            if (!filter.query.isNullOrEmpty()) {
                val q = URLEncoder.encode(filter.query, "UTF-8")
                append("&filter=title=like=\"$q\",nativeTitle=like=\"$q\"")
            }

            filter.states.oneOrThrowIfMany()?.let { state ->
                append("&status=")
                append(
                    when (state) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.PAUSED -> "hiatus"
                        MangaState.ABANDONED -> "canceled"
                        else -> ""
                    },
                )
            }

            filter.types.oneOrThrowIfMany()?.let { type ->
                append("&format=")
                append(
                    when (type) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        else -> ""
                    },
                )
            }

            if (filter.tags.isNotEmpty()) {
                append("&genreIds=")
                append(filter.tags.joinToString(",") { it.key })
            }
        }

        val json = webClient.httpGet(url).body?.string().orEmpty()
        return parseSeriesList(json)
    }

    private fun parseSeriesList(json: String): List<Manga> {
        val result = mutableListOf<Manga>()
        try {
            val root = JSONObject(json)
            val dataArray = root.getJSONArray("data")
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val data = item.getJSONObject("data")
                val slug = data.getString("slug")
                val relativeUrl = "/series/$slug"

                result.add(
                    Manga(
                        id = generateUid(relativeUrl),
                        url = relativeUrl,
                        publicUrl = "https://$domain$relativeUrl",
                        title = data.getString("title"),
                        altTitles = emptySet(),
                        rating = data.toRating(),
                        contentRating = null,
                        coverUrl = data.nonNullString("coverImage"),
                        tags = emptySet(),
                        state = data.toMangaState(),
                        authors = setOfNotNull(data.nonNullString("author")),
                        source = source,
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")

        val seriesUrl = "$apiBase/series/$slug"
        val json = webClient.httpGet(seriesUrl).body?.string().orEmpty()

        return try {
            val root = JSONObject(json)
            val data = root.getJSONObject("data")
            val seriesData = data.getJSONObject("data")

            val title = seriesData.getString("title")
            val nativeTitle = seriesData.nonNullString("nativeTitle")
            val synopsis = seriesData.optString("synopsis", "")
            val author = seriesData.nonNullString("author")
            val coverImage = seriesData.optString("coverImage", "")
            val backgroundImage = seriesData.nonNullString("backgroundImage")
            val format = seriesData.nonNullString("format")?.lowercase(Locale.ROOT)

            val genres = mutableSetOf<MangaTag>()
            seriesData.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.toGenreTagOrNull()?.let(genres::add)
                }
            }

            val releaseYear = seriesData.nonNullString("releaseDate")
            val extraTags = buildSet {
                releaseYear?.let { add(MangaTag(it, it, source)) }
                format?.let {
                    add(MangaTag(it.replaceFirstChar { c -> c.uppercase() }, it, source))
                }
            }

            val chaptersUrl = "$apiBase/series/$slug/chapters"
            val chaptersJson = webClient.httpGet(chaptersUrl).body?.string().orEmpty()
            val chapters = parseChapters(chaptersJson, slug)

            manga.copy(
                title = title,
                altTitles = setOfNotNull(nativeTitle),
                description = synopsis,
                state = seriesData.toMangaState(),
                authors = setOfNotNull(author),
                tags = genres + extraTags,
                coverUrl = coverImage,
                largeCoverUrl = backgroundImage ?: coverImage,
                rating = seriesData.toRating(),
                chapters = chapters,
            )
        } catch (_: Exception) {
            manga
        }
    }

    private fun parseChapters(json: String, slug: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        try {
            val root = JSONObject(json)
            val chaptersArray = root.getJSONArray("data") ?: return emptyList()

            for (i in 0 until chaptersArray.length()) {
                val ch = chaptersArray.getJSONObject(i)
                val chapterData = ch.optJSONObject("data")

                val chapterIndex = chapterData?.optDouble("index", 0.0) ?: 0.0
                val formattedIndex = chapterIndex.toString().removeSuffix(".0")

                val chapterUrl = "/series/$slug/chapter/$formattedIndex"

                val title = chapterData?.nonNullString("title")
                val titleText = if (title != null) {
                    "Chapter $formattedIndex: $title"
                } else {
                    "Chapter $formattedIndex"
                }

                val uploadDate = parseChapterDate(ch.optString("createdAt", ""))

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
                    )
                )
            }
        } catch (index: Exception) {
            throw Exception("Error parsing chapters.", index)
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("/")
        if (parts.size < 5) return emptyList()
        val slug = parts[2]
        val chapterIndex = parts[4]
        val url = "$apiBase/series/$slug/chapters/$chapterIndex"

        val json = webClient.httpGet(url).body?.string().orEmpty()
        try {
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: return emptyList()

            val imagesArray = data.optJSONObject("data")?.optJSONArray("images")
            if (imagesArray != null) {
                return (0 until imagesArray.length()).mapNotNull { i ->
                    val imgUrl = imagesArray.getString(i)
                    if (imgUrl.isBlank()) {
                        null
                    } else {
                        MangaPage(
                            id = generateUid(imgUrl),
                            url = imgUrl,
                            preview = null,
                            source = source,
                        )
                    }
                }
            }

            val dataImages = data.optJSONObject("dataImages")
            if (dataImages != null) {
                val pages = mutableListOf<MangaPage>()
                val sortedKeys = dataImages.keys().asSequence().toList()
                    .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }

                for (key in sortedKeys) {
                    val imgUrl = dataImages.getString(key)
                    if (imgUrl.isNotBlank()) {
                        pages.add(
                            MangaPage(
                                id = generateUid(imgUrl),
                                url = imgUrl,
                                preview = null,
                                source = source,
                            ),
                        )
                    }
                }
                return pages
            }

            return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
    }

    @Volatile
    private var genreCache: Map<String, MangaTag>? = null
    private val genreMutex = Mutex()

    private suspend fun fetchGenreMap(): Map<String, MangaTag> {
        return genreMutex.withLock {
            genreCache ?: fetchGenreMapInternal().also { genreCache = it }
        }
    }

    private suspend fun fetchGenreMapInternal(): Map<String, MangaTag> {
        val url = "$apiBase/genres"
        val jsonStr = webClient.httpGet(url).body?.string().orEmpty()
        val map = mutableMapOf<String, MangaTag>()

        try {
            val dataArray = JSONObject(jsonStr).getJSONArray("data")
            for (i in 0 until dataArray.length()) {
                dataArray.getJSONObject(i).toGenreTagOrNull()?.let {
                    map[it.title] = it
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun parseChapterDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        return try {
            isoDateFormat.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun JSONObject.nonNullString(key: String): String? {
        return optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.toMangaState(): MangaState? {
        val status = nonNullString("status")?.lowercase(Locale.ROOT) ?: return null
        return when (status) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "canceled" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun JSONObject.toRating(): Float {
        val raw = optDouble("rating", 0.0)
        return if (raw > 0.0) (raw / 10.0).toFloat() else RATING_UNKNOWN
    }

    private fun JSONObject.toGenreTagOrNull(): MangaTag? {
        val name = optJSONObject("data")?.optString("name").orEmpty().trim()
        val id = optInt("id", -1)
        if (name.isEmpty() || id == -1) return null
        return MangaTag(title = name, key = id.toString(), source = source)
    }

    companion object {
        private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
    }
}
