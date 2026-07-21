package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import tsuki.util.json.*

import okhttp3.Headers
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("SHINIGAMI", "Shinigami", "id")
internal class Shinigami(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SHINIGAMI, 30) {

    override val configKeyDomain = ConfigKey.Domain("g.shinigami.asia")
    private val apiSuffix = "api.shngm.io/v1"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    @Volatile
    private var apiHeaders: Headers? = null

    private fun getApiHeaders(): Headers {
        if (apiHeaders == null) {
            apiHeaders = getRequestHeaders().newBuilder()
                .add("Accept", "application/json")
                .add("DNT", "1")
                .add("Origin", "https://$domain")
                .add("Sec-GPC", "1")
                .removeAll("X-Requested-With")
                .build()
        }
        return apiHeaders!!
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("referer", "https://$domain/")
        .add("sec-fetch-dest", "empty")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_ASC,
        SortOrder.NEWEST,
        SortOrder.NEWEST_ASC,
        SortOrder.RATING,
        SortOrder.RATING_ASC,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = fetchTags(),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
            availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
        )
    }

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(apiSuffix)
            append("/manga/list")
            append("?page=").append(page)
            append("&page_size=").append(pageSize)

            append("&sort=")
            append(
                when (order) {
                    SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "popularity"
                    SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "latest"
                    SortOrder.RATING, SortOrder.RATING_ASC -> "rating"
                    else -> "latest"
                }
            )
            append("&sort_order=")
            append(
                when (order) {
                    SortOrder.POPULARITY_ASC, SortOrder.NEWEST_ASC, SortOrder.RATING_ASC -> "asc"
                    else -> "desc"
                }
            )

            filter.states.oneOrThrowIfMany()?.let {
                append("&status=")
                append(
                    when (it) {
                        MangaState.FINISHED -> "completed"
                        MangaState.ONGOING -> "ongoing"
                        MangaState.PAUSED -> "hiatus"
                        else -> ""
                    }
                )
            }

            if (filter.types.isNotEmpty()) {
                append("&format=")
                append(
                    filter.types.joinToString(",") {
                        when (it) {
                            ContentType.MANGA -> "manga"
                            ContentType.MANHWA -> "manhwa"
                            ContentType.MANHUA -> "manhua"
                            else -> ""
                        }
                    }
                )
            }

            if (filter.tags.isNotEmpty()) {
                append("&genre_include=")
                filter.tags.joinTo(this, separator = ",") { it.key }
                append("&genre_include_mode=and")
            }

            if (filter.tagsExclude.isNotEmpty()) {
                append("&genre_exclude=")
                filter.tagsExclude.joinTo(this, separator = ",") { it.key }
                append("&genre_exclude_mode=and")
            }

            if (!filter.author.isNullOrEmpty()) {
                append("&author=").append(filter.author.urlEncoded())
            }

            if (!filter.query.isNullOrEmpty()) {
                append("&q=").append(filter.query.urlEncoded())
            }
        }

        val json = webClient.httpGet(url, getApiHeaders()).parseJson()
        val data = json.getJSONArray("data")

        return data.mapJSON { jo ->
            val id = jo.getString("manga_id")
            Manga(
                id = generateUid(id),
                url = "/manga/detail/$id",
                publicUrl = "https://$domain/series/$id",
                title = jo.getString("title"),
                altTitles = setOfNotNull(jo.getStringOrNull("alternative_title")),
                coverUrl = jo.getStringOrNull("cover_image_url"),
                largeCoverUrl = jo.getStringOrNull("cover_portrait_url"),
                authors = jo.optJSONObject("taxonomy")
                    ?.optJSONArray("Author")
                    ?.mapJSONToSet { it.getString("name") }
                    .orEmpty(),
                tags = jo.optJSONObject("taxonomy")
                    ?.optJSONArray("Genre")
                    ?.mapJSONToSet {
                        MangaTag(
                            key = it.getString("slug"),
                            title = it.getString("name").toTitleCase(sourceLocale),
                            source = source,
                        )
                    }.orEmpty(),
                state = when (jo.getIntOrDefault("status", 0)) {
                    1 -> MangaState.ONGOING
                    2 -> MangaState.FINISHED
                    3 -> MangaState.PAUSED
                    else -> null
                },
                description = jo.getStringOrNull("description"),
                contentRating = null,
                source = source,
                rating = RATING_UNKNOWN,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val json = webClient.httpGet("https://$apiSuffix${manga.url}", getApiHeaders()).parseJson()
        val jo = json.getJSONObject("data")

        return manga.copy(
            tags = jo.optJSONObject("taxonomy")
                ?.optJSONArray("Genre")
                ?.mapJSONToSet {
                    MangaTag(
                        key = it.getString("slug"),
                        title = it.getString("name"),
                        source = source,
                    )
                } ?: manga.tags,
            authors = jo.optJSONObject("taxonomy")
                ?.optJSONArray("Author")
                ?.mapJSONToSet { it.getString("name") }
                ?: manga.authors,
            state = when (jo.getIntOrDefault("status", 0)) {
                1 -> MangaState.ONGOING
                2 -> MangaState.FINISHED
                3 -> MangaState.PAUSED
                else -> null
            },
            description = jo.getStringOrNull("description"),
            largeCoverUrl = jo.getStringOrNull("cover_portrait_url"),
            chapters = getChapters(jo.getString("manga_id")),
        )
    }

    private suspend fun getChapters(mangaId: String): List<MangaChapter> {
        val url = "https://$apiSuffix/chapter/$mangaId/list?page_size=3000"
        val json = webClient.httpGet(url, getApiHeaders()).parseJson()
        val data = json.getJSONArray("data")

        return data.mapJSON { jo ->
            val chapterId = jo.getString("chapter_id")
            val number = jo.getDouble("chapter_number")

            MangaChapter(
                id = generateUid(chapterId),
                title = "Chapter ${number.toFloat().toString().removeSuffix(".0")}",
                number = number.toFloat(),
                url = "/chapter/detail/$chapterId",
                scanlator = null,
                uploadDate = jo.getString("release_date").parseDate(),
                branch = null,
                source = source,
                volume = 0,
            )
        }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val json = webClient.httpGet("https://$apiSuffix${chapter.url}", getApiHeaders()).parseJson()
        val data = json.getJSONObject("data")
        val chapterData = data.getJSONObject("chapter")
        val baseUrl = data.getString("base_url")
        val basePath = chapterData.getString("path")
        val pagesArray = chapterData.getJSONArray("data")

        return List(pagesArray.length()) { i ->
            val imgExt = pagesArray.getString(i)
            val imageUrl = "$baseUrl$basePath$imgExt"

            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val json = webClient.httpGet("https://$apiSuffix/genre/list", getApiHeaders()).parseJson()
        return json.getJSONArray("data").mapJSONToSet {
            MangaTag(
                key = it.getString("slug"),
                title = it.getString("name").toTitleCase(sourceLocale),
                source = source,
            )
        }
    }

    private fun String.parseDate(): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(this)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }
}
