package tsuki.site.fr

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
import tsuki.util.json.mapJSON
import tsuki.util.json.mapJSONNotNull
import tsuki.util.json.mapJSONToSet
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseJson
import tsuki.util.parseSafe

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

private const val API_BASE = "https://api.phenix-scans.co/api"
private const val IMAGE_BASE = "https://api.phenix-scans.co"
private const val WEB_BASE = "https://phenix-scans.co"
private const val PAGE_SIZE = 18


@MangaSourceParser("PHENIXSCANS", "Phenix Scans (unoriginal)", "fr")
internal class PhenixScans(context: MangaLoaderContext):
    PagedMangaParser(context, MangaParserSource.PHENIXSCANS, PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("phenix-scans.co")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isSearchWithFiltersSupported = true,
        )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.FRENCH)
    private val tagMutex = Mutex()

    @Volatile
    private var cachedTags: Set<MangaTag>? = null

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = getOrCreateTagMap(),
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

    private suspend fun getOrCreateTagMap(): Set<MangaTag> {
        cachedTags?.let { return it }
        return tagMutex.withLock {
            cachedTags ?: fetchTags().also { cachedTags = it }
        }
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        return try {
            val json = webClient.httpGet("$API_BASE/front/manga?limit=0&page=0").parseJson()
            json.getJSONArray("genres").mapJSONToSet { jo ->
                MangaTag(
                    key = jo.getString("id"),
                    title = jo.getString("name"),
                    source = source,
                )
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrBlank()) {
            if (page > 1) return emptyList()
            "$API_BASE/front/manga/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", filter.query)
                .build()
                .toString()
        } else {
            val builder = "$API_BASE/front/manga".toHttpUrl().newBuilder()
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("page", page.toString())

            builder.addQueryParameter(
                "sort",
                when (order) {
                    SortOrder.ALPHABETICAL -> "title"
                    SortOrder.RATING -> "rating"
                    SortOrder.UPDATED -> "updatedAt"
                    SortOrder.NEWEST -> "updatedAt"
                    SortOrder.POPULARITY -> "rating"
                    else -> "title"
                },
            )

            if (filter.tags.isNotEmpty()) {
                builder.addQueryParameter(
                    "genre",
                    filter.tags.joinToString(",") { it.key },
                )
            }

            if (filter.types.isNotEmpty()) {
                val typeStr = filter.types.mapNotNull {
                    when (it) {
                        ContentType.MANGA -> "Manga"
                        ContentType.MANHWA -> "Manhwa"
                        ContentType.MANHUA -> "Manhua"
                        else -> null
                    }
                }.joinToString(",")
                if (typeStr.isNotEmpty()) {
                    builder.addQueryParameter("type", typeStr)
                }
            }

            filter.states.oneOrThrowIfMany()?.let { state ->
                builder.addQueryParameter(
                    "status",
                    when (state) {
                        MangaState.ONGOING -> "Ongoing"
                        MangaState.FINISHED -> "Completed"
                        MangaState.PAUSED -> "Hiatus"
                        MangaState.ABANDONED -> "Hiatus"
                        else -> ""
                    },
                )
            }
            builder.build().toString()
        }
        val json = webClient.httpGet(url).parseJson()
        return json.getJSONArray("mangas").mapJSON(::parseManga)
    }

    private fun parseManga(jo: JSONObject): Manga {
        val slug = jo.getString("slug")
        val cover = jo.getString("coverImage")
        return Manga(
            id = generateUid(slug),
            title = jo.getString("title"),
            altTitles = emptySet(),
            url = slug,
            publicUrl = "$WEB_BASE/manga/$slug",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = "$IMAGE_BASE/$cover",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            largeCoverUrl = null,
            description = null,
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val json = webClient.httpGet("$API_BASE/front/manga/${manga.url}").parseJson()
        val mangaObj = json.getJSONObject("manga")
        val chaptersArr = json.getJSONArray("chapters")

        val chapters = chaptersArr.mapJSONNotNull { jo ->
            val number = jo.get("number").toString().toFloatOrNull() ?: 0f
            val chapterUrl = "${manga.url}/$number"
            MangaChapter(
                id = generateUid(chapterUrl),
                title = "Chapter $number",
                number = number,
                volume = 0,
                url = chapterUrl,
                scanlator = null,
                uploadDate = dateFormat.parseSafe(jo.optString("createdAt", "")),
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }

        val cover = mangaObj.optString("coverImage").takeIf { it.isNotEmpty() }

        val tags: Set<MangaTag> = mangaObj.optJSONArray("genres")?.mapJSONToSet { jo ->
            MangaTag(
                key = jo.getString("id"),
                title = jo.getString("name"),
                source = source,
            )
        } ?: emptySet()

        val rating: Float = mangaObj.optDouble("averageRating", 0.0)
            .takeIf { it > 0.0 }
            ?.let { (it / 10.0).toFloat() }
            ?: RATING_UNKNOWN

        return manga.copy(
            title = mangaObj.getString("title"),
            coverUrl = cover?.let { "$IMAGE_BASE/$it" } ?: manga.coverUrl,
            description = mangaObj.optString("synopsis").takeIf { it.isNotEmpty() },
            state = when (mangaObj.optString("status").lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "hiatus" -> MangaState.PAUSED
                "completed" -> MangaState.FINISHED
                else -> null
            },
            tags = tags,
            rating = rating,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val slug = chapter.url.substringBeforeLast("/")
        val number = chapter.url.substringAfterLast("/")
        val json = webClient.httpGet("$API_BASE/front/manga/$slug/chapter/$number").parseJson()
        val images = json.getJSONObject("chapter").getJSONArray("images")
        return buildList(images.length()) {
            for (i in 0 until images.length()) {
                val path = images.getString(i)
                add(
                    MangaPage(
                        id = generateUid(path),
                        url = "$IMAGE_BASE/$path",
                        preview = null,
                        source = source,
                    ),
                )
            }
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
}
