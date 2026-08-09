package tsuki.site.en

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
import tsuki.util.parseJson

import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("SCANSGG", "ScansGG", "en")
internal class ScansGG(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SCANSGG, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("scans.gg")

    private val apiUrl = "https://api.scans.gg"
    private val cdnUrl = "https://cdn.scans.gg/uploads"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): okhttp3.Headers {
        return super.getRequestHeaders().newBuilder()
            .set("Origin", "https://$domain")
            .set("Referer", "https://$domain/")
            .build()
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.POPULARITY
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    private val tagsMap = mapOf(
        49 to "Regression", 48 to "Male Protagonist", 47 to "Survival",
        46 to "Avant Garde", 45 to "Award Winning", 44 to "Lolicon",
        43 to "Mahou Shoujo", 42 to "Doujinshi", 41 to "Girls Love",
        40 to "Hentai", 39 to "Mecha", 38 to "Shotacon", 37 to "Ecchi",
        36 to "Music", 35 to "Smut", 34 to "Erotica", 33 to "Adult",
        32 to "Gourmet", 31 to "Yuri", 30 to "Shoujo Ai", 29 to "Yaoi",
        28 to "Shounen Ai", 27 to "Boys Love", 26 to "Harem", 25 to "Tragedy",
        24 to "Gender Bender", 23 to "Suspense", 22 to "Psychological",
        21 to "Mature", 20 to "Horror", 19 to "Mystery", 18 to "Martial Arts",
        17 to "Sci-fi", 16 to "Adventure", 15 to "Supernatural", 14 to "Sports",
        13 to "Shounen", 12 to "Historical", 11 to "Seinen", 10 to "Action",
        9 to "Josei", 8 to "Thriller", 7 to "School Life", 6 to "Slice Of Life",
        5 to "Drama", 4 to "Comedy", 3 to "Shoujo", 2 to "Romance", 1 to "Fantasy"
    )

    private val typeToApiId = mapOf(
        ContentType.MANGA to 2, ContentType.MANHWA to 3, ContentType.MANHUA to 4,
        ContentType.COMICS to 1, ContentType.OTHER to 5
    )

    private val stateToApiId = mapOf(
        MangaState.ONGOING to 1, MangaState.FINISHED to 2,
        MangaState.PAUSED to 3, MangaState.ABANDONED to 4
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = tagsMap.map { (id, name) -> MangaTag(key = id.toString(), title = name, source = source) }
            .toSet(),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA,
            ContentType.COMICS, ContentType.OTHER
        ),
        availableStates = EnumSet.of(
            MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        if (order == SortOrder.UPDATED && query.isEmpty()) return fetchLatest(page)

        val limit = if (order == SortOrder.UPDATED) 14 else 21
        val offset = (page - 1) * limit
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .apply {
                if (query.isNotEmpty()) addQueryParameter("q", query)
                val typeIds = filter.types.mapNotNull { typeToApiId[it] }
                if (typeIds.isNotEmpty()) addQueryParameter("q_type", typeIds.joinToString(",", "[", "]"))
                val statusIds = filter.states.mapNotNull { stateToApiId[it] }
                if (statusIds.isNotEmpty()) addQueryParameter("q_status", statusIds.joinToString(",", "[", "]"))
                val tagIds = filter.tags.mapNotNull { it.key.toIntOrNull() }
                if (tagIds.isNotEmpty()) addQueryParameter("q_tags", tagIds.joinToString(",", "[", "]"))
            }
            .build()

        val json = webClient.httpGet(url).parseJson()
        return parseSeriesList(json)
    }

    private suspend fun fetchLatest(page: Int): List<Manga> {
        val url = "$apiUrl/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "14")
            .addQueryParameter("chapters", "true")
            .addQueryParameter("series_details", "true")
            .addQueryParameter("group_details", "true")
            .addQueryParameter("sort", "date")
            .build()

        val json = webClient.httpGet(url).parseJson()
        val data = json.getJSONArray("data")
        val meta = json.optJSONObject("meta")
        val hasMore = meta?.optBoolean("has_more", false) ?: false
        return (0 until data.length()).map { i -> data.getJSONObject(i).toManga(cdnUrl) }
            .take(if (hasMore) 14 else data.length())
    }

    private fun parseSeriesList(json: JSONObject): List<Manga> {
        val data = json.getJSONArray("data")
        return (0 until data.length()).map { i -> data.getJSONObject(i).toManga(cdnUrl) }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val seriesUrl = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("id", manga.url)
            .addQueryParameter("trackers", "true")
            .addQueryParameter("sources", "true")
            .build()
        val seriesJson = webClient.httpGet(seriesUrl).parseJson()
        val seriesObj = seriesJson.getJSONObject("data")
        val updatedManga = seriesObj.toMangaDetail(cdnUrl, tagsMap)
            .copy(id = manga.id, source = source)

        val chapters = fetchChaptersPaginated(manga.url)
        updatedManga.copy(chapters = chapters)
    }

    private suspend fun fetchChaptersPaginated(seriesId: String): List<MangaChapter> {
        val allChapters = mutableListOf<MangaChapter>()
        val groupTitles = mutableMapOf<Int, String>()
        var page = 1
        var hasMore = true

        while (hasMore) {
            val url = "$apiUrl/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("series_id", seriesId)
                .addQueryParameter("limit", "100")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("group_details", "true")
                .addQueryParameter("sort_order", "desc")
                .build()
            val json = webClient.httpGet(url).parseJson()
            val data = json.getJSONArray("data")
            for (i in 0 until data.length()) {
                val ch = data.getJSONObject(i)
                val chId = ch.getInt("id")
                val number = ch.getDouble("number").toFloat()
                val title = ch.optString("title", null)
                val createdAt = ch.optString("created_at", null)
                val groupId = ch.optInt("group_id", 0)
                val groupObj = ch.optJSONObject("group")
                val groupTitle = groupObj?.optString("title", null)
                if (groupTitle != null) {
                    groupTitles[groupId] = groupTitle
                }

                val name = buildString {
                    append("Chapter ${number.toString().removeSuffix(".0")}")
                    if (!title.isNullOrEmpty()) append(" - $title")
                }

                allChapters.add(
                    MangaChapter(
                        id = generateUid(chId.toString()),
                        title = name,
                        number = number,
                        volume = 0,
                        url = "$apiUrl/chapter-navigation?series_id=$seriesId&chapter_id=$chId&group_id=$groupId",
                        uploadDate = dateFormat.parseSafe(createdAt),
                        scanlator = groupTitle,
                        branch = null,
                        source = source,
                    )
                )
            }
            val meta = json.optJSONObject("meta")
            hasMore = meta?.optBoolean("has_more", false) ?: false
            page++
        }

        return if (groupTitles.size > 1) {
            allChapters.map { ch ->
                val branch = ch.scanlator ?: "Unknown"
                ch.copy(branch = branch)
            }.sortedBy { it.number }
        } else {
            allChapters.sortedBy { it.number }
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val json = webClient.httpGet(chapter.url).parseJson()
        val data = json.optJSONObject("data") ?: return emptyList()
        val chapterObj = data.optJSONObject("chapter") ?: data
        val chapterId = chapterObj.optInt("id", 0)
        val pages = chapterObj.optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).map { i ->
            val page = pages.getJSONObject(i)
            val path = page.getString("path")
            val imageUrl = "$cdnUrl/pages/$chapterId/$path"
            MangaPage(id = generateUid(imageUrl), url = imageUrl, preview = null, source = source)
        }
    }

    private fun JSONObject.toManga(cdnUrl: String): Manga {
        val id = getInt("id").toString()
        val title = getString("title")
        val cover = optString("cover", null)
        return Manga(
            id = generateUid(id), url = id,
            publicUrl = "https://$domain/series/$id", title = title,
            altTitles = emptySet(), coverUrl = cover?.let { "$cdnUrl/covers/$it" },
            rating = RATING_UNKNOWN, contentRating = null,
            tags = emptySet(), state = null, authors = emptySet(), source = source,
        )
    }

    private fun JSONObject.toMangaDetail(cdnUrl: String, tagsMap: Map<Int, String>): Manga {
        val id = getInt("id").toString()
        val title = getString("title")
        val summary = optString("summary", null)
        val cover = optString("cover", null)
        val authorList = optJSONArray("author")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
        val artistList = optJSONArray("artist")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
        val altTitlesArray = optJSONArray("alternative_titles")
        val altTitles = if (altTitlesArray != null) {
            (0 until altTitlesArray.length()).map { altTitlesArray.getJSONObject(it).getString("title") }.toSet()
        } else emptySet()
        val tagsIds = optJSONArray("tags")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } } ?: emptyList()
        val genres = tagsIds.mapNotNull { tagsMap[it] }
        val typeId = optInt("type", -1)
        val typeName = when (typeId) {
            1 -> "Comic"; 2 -> "Manga"; 3 -> "Manhwa"; 4 -> "Manhua"; 5 -> "Webtoon"
            else -> null
        }
        val tags = mutableSetOf<MangaTag>().apply {
            addAll(genres.map { MangaTag(key = it, title = it, source = source) })
            typeName?.let { add(MangaTag(key = "type:$typeId", title = it, source = source)) }
        }
        val statusId = optInt("status", -1)
        val state = when (statusId) {
            1 -> MangaState.ONGOING; 2 -> MangaState.FINISHED
            3 -> MangaState.PAUSED; 4, 5 -> MangaState.ABANDONED
            else -> null
        }
        return Manga(
            id = generateUid(id), url = id,
            publicUrl = "https://$domain/series/$id", title = title,
            altTitles = altTitles, coverUrl = cover?.let { "$cdnUrl/covers/$it" },
            description = summary, authors = (authorList + artistList).toSet(),
            tags = tags, state = state, rating = RATING_UNKNOWN,
            source = source, contentRating = null,
        )
    }

    private fun SimpleDateFormat.parseSafe(date: String?): Long {
        return date?.let { runCatching { parse(it)?.time }.getOrDefault(0L) } ?: 0L
    }
}
