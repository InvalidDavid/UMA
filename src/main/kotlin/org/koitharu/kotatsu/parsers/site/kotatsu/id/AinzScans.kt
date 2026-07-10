package org.koitharu.kotatsu.parsers.site.kotatsu.id

import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("AINZSCANS", "AinzScans", "id")
internal class AinzScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.AINZSCANS, pageSize = 30, searchPageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("v2.ainzscans01.com")

    override val sourceLocale: Locale = Locale.ENGLISH

    override val availableSortOrders: Set<SortOrder>
        get() = EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.NEWEST,
            SortOrder.ALPHABETICAL,
            SortOrder.ALPHABETICAL_DESC,
        )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    override suspend fun getFavicons(): Favicons {
        return Favicons.single("https://api.ainzscans01.com/api/uploads/site-branding/favicon-1773679687168-jsyhcr.png")
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = fetchGenreMap().values.toSet(),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
            availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
        )
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .build()

    private var genreCache: Map<String, MangaTag>? = null

    private suspend fun fetchGenreMap(): Map<String, MangaTag> {
        genreCache?.let { return it }
        val url = "https://api.ainzscans01.com/api/genres"
        val jsonStr = webClient.httpGet(url).body?.string() ?: return emptyMap()
        val map = mutableMapOf<String, MangaTag>()
        try {
            val array = org.json.JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.optString("name")
                val slug = obj.optString("slug")
                if (name.isEmpty() || slug.isEmpty()) continue
                map[slug] = MangaTag(
                    title = name,
                    key = slug,
                    source = source,
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        genreCache = map
        return map
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        val url = buildString {
            append("https://api.ainzscans01.com/api/search?type=COMIC")
            append("&limit=").append(pageSize)
            append("&page=").append(page)

            if (!query.isNullOrEmpty()) {
                append("&q=").append(query.urlEncoded())
            }

            append("&sort=")
            append(
                when (order) {
                    SortOrder.UPDATED -> "new"
                    SortOrder.POPULARITY -> "views"
                    SortOrder.NEWEST -> "new"
                    SortOrder.ALPHABETICAL -> "az"
                    SortOrder.ALPHABETICAL_DESC -> "za"
                    else -> "new"
                },
            )
            append("&order=desc")

            filter.tags.firstOrNull()?.let {
                append("&genre=").append(it.key)
            }

            filter.states.oneOrThrowIfMany()?.let { state ->
                append("&status=")
                append(
                    when (state) {
                        MangaState.ONGOING -> "ONGOING"
                        MangaState.FINISHED -> "COMPLETED"
                        MangaState.PAUSED -> "HIATUS"
                        else -> ""
                    },
                )
            }

            filter.types.oneOrThrowIfMany()?.let { type ->
                append("&comic_type=")
                append(
                    when (type) {
                        ContentType.MANGA -> "MANGA"
                        ContentType.MANHWA -> "MANHWA"
                        ContentType.MANHUA -> "MANHUA"
                        else -> ""
                    },
                )
            }
        }

        val json = webClient.httpGet(url).body?.string() ?: return emptyList()
        return parseListJson(json)
    }

    private fun parseListJson(json: String): List<Manga> {
        val result = mutableListOf<Manga>()
        try {
            val responseObj = org.json.JSONObject(json)
            val dataArray = responseObj.optJSONArray("data") ?: return emptyList()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val slug = obj.optString("slug")
                if (slug.isEmpty()) continue

                val relativeUrl = "/comic/$slug"
                val rating = obj.optString("rating_average").toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN
                val state = when (obj.optString("comic_status").uppercase()) {
                    "ONGOING" -> MangaState.ONGOING
                    "COMPLETED" -> MangaState.FINISHED
                    "HIATUS" -> MangaState.PAUSED
                    else -> null
                }

                val manga = Manga(
                    id = generateUid(relativeUrl),
                    url = relativeUrl,
                    title = obj.optString("title").ifEmpty { slug },
                    altTitles = emptySet(),
                    publicUrl = "https://$domain/comic/$slug",
                    rating = rating,
                    contentRating = ContentRating.SAFE,
                    coverUrl = obj.optString("poster_image_url").nullIfEmpty(),
                    tags = emptySet(),
                    state = state,
                    authors = setOfNotNull(obj.optString("author_name").takeIf { it.isNotEmpty() }),
                    source = source,
                )
                result.add(manga)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfter("/comic/")
        val url = "https://api.ainzscans01.com/api/series/comic/$slug"

        val json = webClient.httpGet(url).body?.string()
            ?: throw Exception("Failed to fetch manga details")
        return parseDetailsJson(json, manga)
    }

    private fun parseDetailsJson(json: String, manga: Manga): Manga {
        try {
            val obj = JSONObject(json)
            val slug = obj.optString("slug")
            val relativeUrl = "/comic/$slug"

            val description = obj.optString("synopsis").nullIfEmpty()
            val rating = obj.optString("rating_average").toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN
            val state = when (obj.optString("comic_status").uppercase()) {
                "ONGOING" -> MangaState.ONGOING
                "COMPLETED" -> MangaState.FINISHED
                "HIATUS" -> MangaState.PAUSED
                else -> null
            }

            val genres = obj.optJSONArray("genres")?.let { genresArray ->
                (0 until genresArray.length()).mapNotNull { i ->
                    val genreObj = genresArray.optJSONObject(i) ?: return@mapNotNull null
                    val slug = genreObj.optString("slug")
                    val name = genreObj.optString("name")
                    if (slug.isEmpty() || name.isEmpty()) return@mapNotNull null
                    MangaTag(title = name, key = slug, source = source)
                }.toSet()
            } ?: emptySet()

            val chapters = parseUnitsJson(obj.optJSONArray("units"), relativeUrl, slug)

            return manga.copy(
                title = obj.optString("title").takeIf { it.isNotEmpty() } ?: manga.title,
                description = description,
                rating = rating,
                state = state,
                authors = setOfNotNull(obj.optString("author_name").takeIf { it.isNotEmpty() }),
                tags = genres,
                chapters = chapters,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return manga
        }
    }

    private fun parseUnitsJson(array: org.json.JSONArray?, mangaUrl: String, seriesSlug: String): List<MangaChapter> {
        if (array == null) return emptyList()
        val result = mutableListOf<MangaChapter>()
        try {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val chapterSlug = obj.optString("slug")
                if (chapterSlug.isEmpty()) continue

                val chapterUrl = "/comic/$seriesSlug/chapter/$chapterSlug"
                val numberStr = obj.optString("number")
                val number = numberStr.toFloatOrNull() ?: (result.size + 1).toFloat()

                val chapter = MangaChapter(
                    id = generateUid(chapterUrl),
                    url = chapterUrl,
                    title = obj.optString("title").nullIfEmpty() ?: "Chapter $numberStr",
                    number = number,
                    volume = 0,
                    branch = null,
                    scanlator = null,
                    uploadDate = parseChapterDate(obj.optString("created_at")),
                    source = source,
                )
                result.add(chapter)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val seriesSlug = chapter.url.substringAfter("/comic/").substringBefore("/chapter")
        val chapterSlug = chapter.url.substringAfter("/chapter/")
        val url = "https://api.ainzscans01.com/api/series/comic/$seriesSlug/chapter/$chapterSlug"

        val json = webClient.httpGet(url).body?.string()
            ?: throw Exception("Failed to fetch chapter pages")
        return parsePagesJson(json)
    }

    private fun parsePagesJson(json: String): List<MangaPage> {
        try {
            val obj = JSONObject(json)
            val chapterObj = obj.optJSONObject("chapter") ?: return emptyList()
            val pagesArray = chapterObj.optJSONArray("pages") ?: return emptyList()

            return (0 until pagesArray.length()).mapNotNull { i ->
                val pageObj = pagesArray.getJSONObject(i)
                val imgUrl = pageObj.optString("image_url").nullIfEmpty() ?: return@mapNotNull null
                MangaPage(
                    id = generateUid(imgUrl),
                    url = imgUrl,
                    preview = null,
                    source = source,
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun parseChapterDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH)
                .parse(dateStr).time
        } catch (_: Exception) {
            0L
        }
    }

    private fun String?.nullIfEmpty(): String? = if (this.isNullOrEmpty()) null else this
}
