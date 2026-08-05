package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

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
import tsuki.util.json.extractNextJs
import tsuki.util.parseSafe
import tsuki.util.toAbsoluteUrl
import tsuki.util.extractChapterNumber

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("TEMPLESCAN", "Temple Scan", "en", ContentType.HENTAI)
internal class TempleScan(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.TEMPLESCAN, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("templetoons.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): okhttp3.Headers {
        val builder = super.getRequestHeaders().newBuilder()
            .set("Referer", "https://$domain/")
            .set("Origin", "https://$domain")
            .set("User-Agent", config[userAgentKey])
        return builder.build()
    }

    private val rscHeaders get() = getRequestHeaders().newBuilder()
        .set("rsc", "1")
        .build()

    @Volatile
    private var seriesCache: List<SeriesItem>? = null

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableContentTypes = emptySet(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (seriesCache == null) {
            val response = webClient.httpGet("https://$domain/comics".toHttpUrl(), rscHeaders)
            seriesCache = parseBrowseSeries(response)
        }

        val allSeries = seriesCache ?: return emptyList()

        val statusFilter = filter.states.firstOrNull()
        val filtered = allSeries.filter { series ->
            val query = filter.query?.trim()?.takeIf(String::isNotBlank)
            val matchesQuery = if (query != null) {
                series.title.contains(query, ignoreCase = true) ||
                        (series.alternativeNames?.contains(query, ignoreCase = true) == true)
            } else true
            val matchesStatus = statusFilter == null || series.status == stateToApi(statusFilter)
            matchesQuery && matchesStatus
        }

        val sorted = when (order) {
            SortOrder.UPDATED -> filtered.sortedByDescending { it.updated }
            SortOrder.NEWEST -> filtered.sortedByDescending { it.created }
            SortOrder.POPULARITY -> filtered.sortedByDescending { it.views }
            SortOrder.ALPHABETICAL -> filtered.sortedBy { it.title }
            else -> filtered
        }

        val startIndex = (page - 1) * pageSize
        val endIndex = minOf(startIndex + pageSize, sorted.size)
        if (startIndex >= sorted.size) return emptyList()

        return sorted.subList(startIndex, endIndex).map { item ->
            Manga(
                id = generateUid("/comic/${item.slug}"),
                url = "/comic/${item.slug}",
                publicUrl = "https://$domain/comic/${item.slug}",
                title = item.title,
                altTitles = emptySet(),
                coverUrl = item.thumbnail?.toAbsoluteUrl(domain),
                authors = emptySet(),
                state = when (item.status) {
                    "Ongoing" -> MangaState.ONGOING
                    "Hiatus" -> MangaState.PAUSED
                    "Completed" -> MangaState.FINISHED
                    "Canceled", "Dropped" -> MangaState.ABANDONED
                    else -> null
                },
                contentRating = null,
                tags = emptySet(),
                rating = RATING_UNKNOWN,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removePrefix("/comic/")
        val response = webClient.httpGet("https://$domain/comic/$slug".toHttpUrl(), rscHeaders)
        val details = parseSeriesDetails(response)

        return manga.copy(
            title = details.title,
            altTitles = details.alternativeNames?.let { setOf(it) } ?: emptySet(),
            coverUrl = details.thumbnail?.toAbsoluteUrl(domain),
            description = details.description?.let { desc ->
                val cleanDesc = if (desc.contains("#")) desc.substringBefore("#")
                    .replace(TRAILING_WORD_REGEX, "").trim()
                else desc
                Jsoup.clean(cleanDesc, Safelist.none())
            }?.trim().orEmpty(),
            authors = details.author?.let { setOf(it) } ?: emptySet(),
            state = when (details.status) {
                "Ongoing" -> MangaState.ONGOING
                "Hiatus" -> MangaState.PAUSED
                "Completed" -> MangaState.FINISHED
                "Canceled", "Dropped" -> MangaState.ABANDONED
                else -> null
            },
            contentRating = if (details.adult) ContentRating.ADULT else ContentRating.SAFE,
            tags = buildSet {
                details.badge?.let { add(MangaTag(it.lowercase(), it, source)) }
                details.year?.let { add(MangaTag(it, it, source)) }
                if (details.adult) add(MangaTag("adult", "Adult", source))
                details.tags?.forEach { tag ->
                    add(MangaTag(tag.tag.name.lowercase(), tag.tag.name, source))
                }
                details.description?.let { desc ->
                    TAG_REGEX.findAll(desc).forEach { match ->
                        val tagName = match.groupValues[1]
                        add(MangaTag(tagName.lowercase(), tagName, source))
                    }
                }
            },
            chapters = getChapterList(slug),
        )
    }

    private suspend fun getChapterList(mangaSlug: String): List<MangaChapter> {
        val response = webClient.httpGet("https://$domain/comic/$mangaSlug".toHttpUrl(), rscHeaders)
        val chapterData = parseChapterList(response) ?: return emptyList()

        return chapterData.seasons.flatMap { season ->
            season.chapters.filter { it.price == 0 }.map { chapter ->
                MangaChapter(
                    id = generateUid("$mangaSlug/${chapter.slug}"),
                    title = chapter.name,
                    number = chapter.name.extractChapterNumber(),
                    url = "/comic/$mangaSlug/${chapter.slug}",
                    uploadDate = chapter.created,
                    source = source,
                    volume = 0,
                    scanlator = null,
                    branch = null,
                )
            }
        }.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet("https://$domain${chapter.url}".toHttpUrl(), rscHeaders)
        val pagesData = parsePagesList(response)
        return pagesData.pages.mapIndexed { _, url ->
            MangaPage(
                id = generateUid(url),
                url = url.toAbsoluteUrl(domain),
                preview = null,
                source = source,
            )
        }
    }

    private fun parseBrowseSeries(response: Response): List<SeriesItem> {
        val jsonArray: JSONArray = response.extractNextJs {
            it is JSONArray && it.length() > 0 && it.optJSONObject(0)?.has("series_slug") == true
        } as? JSONArray
            ?: throw RuntimeException("Browse series array not found")

        return (0 until jsonArray.length()).map { i ->
            jsonArray.getJSONObject(i).toSeriesItem()
        }
    }

    private fun parseSeriesDetails(response: Response): SeriesDetails {
        val obj: JSONObject = response.extractNextJs {
            it is JSONObject && it.has("series_slug") && it.has("title")
        } as? JSONObject
            ?: throw RuntimeException("Series details not found")
        return obj.toSeriesDetails()
    }

    private fun parseChapterList(response: Response): ChapterList? {
        val obj: JSONObject? = response.extractNextJs {
            it is JSONObject && it.has("Season")
        } as? JSONObject
        return obj?.toChapterList()
    }

    private fun parsePagesList(response: Response): PagesList {
        val obj: JSONObject = response.extractNextJs {
            it is JSONObject && it.has("pages")
        } as? JSONObject
            ?: throw RuntimeException("Pages list not found")
        return obj.toPagesList()
    }

    private fun JSONObject.toSeriesItem(): SeriesItem {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        return SeriesItem(
            slug = getString("series_slug"),
            title = getString("title"),
            alternativeNames = optString("alternative_names", null),
            thumbnail = optString("thumbnail", null),
            status = optString("status", null),
            updated = dateFormat.parseSafe(optString("update_chapter", null)),
            created = dateFormat.parseSafe(optString("created_at", null)),
            views = optLong("total_views", 0),
        )
    }

    private fun JSONObject.toSeriesDetails(): SeriesDetails {
        return SeriesDetails(
            slug = getString("series_slug"),
            title = getString("title"),
            thumbnail = optString("thumbnail", null),
            author = optString("author", null),
            studio = optString("studio", null),
            year = optString("release_year", null),
            alternativeNames = optString("alternative_names", null),
            adult = optBoolean("adult", false),
            badge = optString("badge", null),
            status = optString("status", null),
            description = optString("description", null),
            tags = optJSONArray("tag_series")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val tagObj = arr.getJSONObject(i).getJSONObject("tag")
                    TagWrapper(Tag(tagObj.getString("name")))
                }
            },
        )
    }

    private fun JSONObject.toChapterList(): ChapterList {
        val seasonsArray = getJSONArray("Season")
        val seasons = (0 until seasonsArray.length()).map { i ->
            val seasonObj = seasonsArray.getJSONObject(i)
            val chaptersArray = seasonObj.getJSONArray("Chapter")
            val chapters = (0 until chaptersArray.length()).map { j ->
                val chap = chaptersArray.getJSONObject(j)
                ChapterItem(
                    name = chap.getString("chapter_name"),
                    title = chap.optString("chapter_title", null),
                    slug = chap.getString("chapter_slug"),
                    price = chap.getInt("price"),
                    created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
                        .parseSafe(chap.optString("created_at", null)),
                )
            }
            Season(chapters)
        }
        return ChapterList(seasons)
    }

    private fun JSONObject.toPagesList(): PagesList {
        val pagesArray = getJSONArray("pages")
        val pages = (0 until pagesArray.length()).map { pagesArray.getString(it) }
        return PagesList(pages)
    }

    private fun stateToApi(state: MangaState): String = when (state) {
        MangaState.ONGOING -> "Ongoing"
        MangaState.FINISHED -> "Completed"
        MangaState.PAUSED -> "Hiatus"
        MangaState.ABANDONED -> "Canceled"
        else -> ""
    }

    data class SeriesItem(
        val slug: String,
        val title: String,
        val alternativeNames: String?,
        val thumbnail: String?,
        val status: String?,
        val updated: Long,
        val created: Long,
        val views: Long,
    )

    data class SeriesDetails(
        val slug: String,
        val title: String,
        val thumbnail: String?,
        val author: String?,
        val studio: String?,
        val year: String?,
        val alternativeNames: String?,
        val adult: Boolean,
        val badge: String?,
        val status: String?,
        val description: String?,
        val tags: List<TagWrapper>?,
    )

    data class TagWrapper(val tag: Tag)
    data class Tag(val name: String)

    data class ChapterList(val seasons: List<Season>)
    data class Season(val chapters: List<ChapterItem>)
    data class ChapterItem(
        val name: String,
        val title: String?,
        val slug: String,
        val price: Int,
        val created: Long,
    )

    data class PagesList(val pages: List<String>)

    companion object {
        private val TAG_REGEX = Regex("""(?i)#(\w+)""")
        private val TRAILING_WORD_REGEX = Regex("""[\w\s]+:?\s*$""")
    }
}
