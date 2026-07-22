package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.generateUid
import tsuki.util.oneOrThrowIfMany

import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("KOMIKCAST", "KomikCast", "id")
internal class Komikcast(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KOMIKCAST, pageSize = 12) {

    override val configKeyDomain = ConfigKey.Domain("v3.komikcast.fit")

    private val apiBase = "https://be.komikcast.cc"

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
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
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
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append(apiBase)
            append("/series?includeMeta=true")
            append("&take=").append(pageSize)
            append("&page=").append(page)

            append("&sort=")
            append(
                when (order) {
                    SortOrder.POPULARITY -> "popularity"
                    SortOrder.UPDATED -> "latest"
                    SortOrder.ALPHABETICAL -> "alphabetical"
                    SortOrder.RATING -> "rating"
                    else -> "latest"
                }
            )
            append("&sortOrder=desc")

            if (!filter.query.isNullOrEmpty()) {
                val escaped = filter.query!!.replace("\"", "\\\"")
                append("&filter=title=like=\"$escaped\",nativeTitle=like=\"$escaped\"")
            }

            filter.states.oneOrThrowIfMany()?.let { state ->
                append("&status=")
                append(
                    when (state) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.PAUSED -> "hiatus"
                        else -> ""
                    }
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
                    }
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
                        rating = data.optDouble("rating", -1.0).let {
                            if (it >= 0.0) (it / 10.0).toFloat() else RATING_UNKNOWN
                        },
                        contentRating = null,
                        coverUrl = data.optString("coverImage", null),
                        tags = emptySet(),
                        state = when (data.optString("status").lowercase()) {
                            "ongoing" -> MangaState.ONGOING
                            "completed" -> MangaState.FINISHED
                            "hiatus" -> MangaState.PAUSED
                            else -> null
                        },
                        authors = setOfNotNull(data.optString("author").takeIf { it.isNotEmpty() }),
                        source = source,
                    )
                )
            }
        } catch (_: Exception) { }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")
        val url = "$apiBase/series/$slug"
        val json = webClient.httpGet(url).body?.string().orEmpty()
        val seriesData = parseSeriesDetail(json)

        val chapters = fetchChapters(slug)

        return manga.copy(
            title = seriesData.title,
            description = seriesData.synopsis,
            state = when (seriesData.status.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "hiatus" -> MangaState.PAUSED
                else -> null
            },
            authors = setOfNotNull(seriesData.author),
            tags = seriesData.genres.map {
                MangaTag(title = it.name, key = it.id.toString(), source = source)
            }.toSet(),
            coverUrl = seriesData.coverImage,
            rating = if (seriesData.rating > 0) seriesData.rating / 10f else RATING_UNKNOWN,
            chapters = chapters,
        )
    }

    private fun parseSeriesDetail(json: String): SeriesData {
        val root = JSONObject(json)
        val data = root.getJSONObject("data").getJSONObject("data")

        val genres = mutableListOf<GenreData>()
        val genresArray = data.optJSONArray("genres")
        if (genresArray != null) {
            for (i in 0 until genresArray.length()) {
                val g = genresArray.getJSONObject(i)
                genres.add(
                    GenreData(
                        id = g.getInt("id"),
                        name = g.getJSONObject("data").getString("name")
                    )
                )
            }
        }

        return SeriesData(
            title = data.getString("title"),
            synopsis = data.optString("synopsis", ""),
            status = data.getString("status"),
            author = data.optString("author").takeIf { it.isNotEmpty() },
            coverImage = data.optString("coverImage", ""),
            rating = data.optDouble("rating", 0.0).toFloat(),
            genres = genres,
        )
    }

    private suspend fun fetchChapters(slug: String): List<MangaChapter> {
        val url = "$apiBase/series/$slug/chapters"
        val json = webClient.httpGet(url).body?.string().orEmpty()
        return parseChaptersJson(json, slug)
    }

    private fun parseChaptersJson(json: String, slug: String): List<MangaChapter> {
        val result = mutableListOf<MangaChapter>()
        try {
            val root = JSONObject(json)
            val dataArray = root.getJSONArray("data")
            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val data = item.getJSONObject("data")
                val index = data.getDouble("index")
                val formattedIndex = chapterNumberFormatter.format(index)

                val chapterUrl = "/series/$slug/chapter/$formattedIndex"
                val title = data.optString("title").takeIf { it.isNotEmpty() && it != "null" }
                    ?: "Chapter $formattedIndex"

                result.add(
                    MangaChapter(
                        id = generateUid(chapterUrl),
                        title = title,
                        url = chapterUrl,
                        number = index.toFloat(),
                        volume = 0,
                        scanlator = null,
                        uploadDate = parseChapterDate(item.optString("createdAt", "")),
                        branch = null,
                        source = source,
                    )
                )
            }
        } catch (_: Exception) { }
        return result.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // chapter.url = /series/{slug}/chapter/{formattedIndex}
        val parts = chapter.url.split("/")
        val slug = parts[2]
        val chapterIndex = parts[4]
        val url = "$apiBase/series/$slug/chapters/$chapterIndex"

        val json = webClient.httpGet(url).body?.string().orEmpty()
        try {
            val root = JSONObject(json)
            val data = root.getJSONObject("data").getJSONObject("data")
            val imagesArray = data.getJSONArray("images")
            return (0 until imagesArray.length()).mapNotNull { i ->
                val imgUrl = imagesArray.getString(i)
                if (imgUrl.isBlank()) return@mapNotNull null
                MangaPage(
                    id = generateUid(imgUrl),
                    url = imgUrl,
                    preview = null,
                    source = source,
                )
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    @Volatile
    private var genreCache: Map<String, MangaTag>? = null

    private suspend fun fetchGenreMap(): Map<String, MangaTag> {
        genreCache?.let { return it }
        val url = "$apiBase/genres"
        val jsonStr = webClient.httpGet(url).body?.string().orEmpty()
        val map = mutableMapOf<String, MangaTag>()
        try {
            val dataArray = JSONObject(jsonStr).getJSONArray("data")
            for (i in 0 until dataArray.length()) {
                val genreObj = dataArray.getJSONObject(i)
                val name = genreObj.getJSONObject("data").getString("name")
                val id = genreObj.getInt("id")
                map[name] = MangaTag(title = name, key = id.toString(), source = source)
            }
        } catch (_: Exception) { }
        genreCache = map
        return map
    }

    private fun parseChapterDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        return try {
            isoDateFormat.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    private data class SeriesData(
        val title: String,
        val synopsis: String,
        val status: String,
        val author: String?,
        val coverImage: String,
        val rating: Float,
        val genres: List<GenreData>,
    )

    private data class GenreData(val id: Int, val name: String)

    companion object {
        private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
        private val chapterNumberFormatter = DecimalFormat(
            "#.##",
            DecimalFormatSymbols.getInstance(Locale.US)
        )
    }
}
