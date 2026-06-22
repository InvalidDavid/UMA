package org.koitharu.kotatsu.parsers.site.kotatsu.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGADOTNET", "Mangadot.net", "en", ContentType.MANGA)
internal class Mangadotnet(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGADOTNET, 20) {

    override val configKeyDomain = ConfigKey.Domain("mangadot.net")

    private val baseUrl = "https://mangadot.net"

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = cachedGenres.mapTo(LinkedHashSet(cachedGenres.size)) { MangaTag(key = it, title = it, source = source) },
    )

    private var cachedGenres: List<String> = emptyList()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // region RSC decoder

    private fun decodeRsc(flat: JSONArray): Any? {
        val cache = arrayOfNulls<Any>(flat.length())
        val nil = Object()

        fun resolve(i: Int): Any? {
            if (i < 0 || i >= flat.length()) return null
            val cached = cache[i]
            if (cached != null) return if (cached === nil) null else cached

            val result: Any? = when (val el = flat.opt(i)) {
                JSONObject.NULL -> null
                is String, is Number, is Boolean -> el
                is JSONArray -> {
                    (0 until el.length()).mapTo(mutableListOf()) { j ->
                        resolve(el.optInt(j, -1))
                    }
                }
                is JSONObject -> {
                    val map = mutableMapOf<String, Any?>()
                    for (key in el.keys()) {
                        val actualKey = if (key.startsWith("_")) {
                            flat.optString(key.substring(1).toInt(), key)
                        } else {
                            key
                        }
                        map[actualKey] = resolve(el.optInt(key, -1))
                    }
                    map
                }
                else -> null
            }

            cache[i] = result ?: nil
            return result
        }

        return resolve(0)
    }

    /**
     * Fetch an RSC-encoded URL and extract the given route.
     * Route values are wrapped in `{"data": ...}` (Data<T>).
     * Returns the inner `data` value of the route.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun fetchRscData(url: String, route: String): Map<String, Any?>? {
        val flat = webClient.httpGet(url).parseJsonArray()
        val decoded = decodeRsc(flat) ?: return null
        val routeValue = (decoded as? Map<String, Any?>)?.get(route) as? Map<String, Any?> ?: return null
        return routeValue.asMap("data")
    }

    // endregion

    // region Listing

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        if (!query.isNullOrEmpty()) {
            return getSearchPage(page, query, order)
        }
        return when (order) {
            SortOrder.POPULARITY -> getPopularPage(page)
            else -> getLatestPage(page)
        }
    }

    private suspend fun getPopularPage(page: Int): List<Manga> {
        val url = "$baseUrl/view-all/trending.data".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
            addQueryParameter("_routes", "pages/ViewAllPage")
        }.build().toString()
        return parseViewAllPage(url)
    }

    private suspend fun getLatestPage(page: Int): List<Manga> {
        val url = "$baseUrl/view-all/latest-updates.data".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
            addQueryParameter("_routes", "pages/ViewAllPage")
        }.build().toString()
        return parseViewAllPage(url)
    }

    /**
     * ViewAllPage route structure:
     *   route_value = { "data": ViewAllData }
     *   ViewAllData = { "data": MangaList, "allGenres": [...] }
     *   MangaList = { "manga_list"|"results": [...], "pagination": {...} }
     *
     * After fetchRscData: we get ViewAllData.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun parseViewAllPage(url: String): List<Manga> {
        val viewAllData = fetchRscData(url, "pages/ViewAllPage") ?: return emptyList()

        // Update genres
        (viewAllData["allGenres"] as? List<*>)?.filterIsInstance<String>()?.let {
            if (it.isNotEmpty()) cachedGenres = it
        }

        // Navigate to MangaList: viewAllData.data
        val mangaListMap = viewAllData.asMap("data") ?: return emptyList()
        return parseMangaList(mangaListMap)
    }

    private suspend fun getSearchPage(page: Int, query: String, order: SortOrder): List<Manga> {
        val url = "$baseUrl/search.data".toHttpUrl().newBuilder().apply {
            addQueryParameter("search", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("sortBy", when (order) {
                SortOrder.NEWEST -> "latest"
                SortOrder.ALPHABETICAL -> "Alphabetical"
                else -> "relevance"
            })
            addQueryParameter("_routes", "pages/SearchPage")
        }.build().toString()

        /**
         * SearchPage route structure:
         *   route_value = { "data": MangaList }
         *   MangaList = { "manga_list"|"results": [...], "pagination": {...}, "allGenres": [...] }
         *
         * After fetchRscData: we get MangaList directly.
         */
        @Suppress("UNCHECKED_CAST")
        val mangaListMap = fetchRscData(url, "pages/SearchPage") ?: return emptyList()

        (mangaListMap["allGenres"] as? List<*>)?.filterIsInstance<String>()?.let {
            if (it.isNotEmpty()) cachedGenres = it
        }

        return parseMangaList(mangaListMap)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMangaList(mangaListMap: Map<String, Any?>): List<Manga> {
        val mangaList = mangaListMap["manga_list"] as? List<*>
            ?: mangaListMap["results"] as? List<*>
            ?: return emptyList()
        return mangaList.filterIsInstance<Map<String, Any?>>().map { parseMangaFromList(it) }
    }

    private fun parseMangaFromList(data: Map<String, Any?>): Manga {
        val id = (data["id"] as? Number)?.toInt()?.toString() ?: ""
        val title = data["title"] as? String ?: ""
        val photo = data["photo"] as? String

        val coverUrl = photo?.let {
            when {
                it.startsWith("/") -> "$baseUrl$it"
                it.startsWith("http") -> it
                else -> null
            }
        }

        return Manga(
            id = generateUid(id),
            url = id,
            publicUrl = "$baseUrl/manga/$id",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    // endregion

    // region Details

    /**
     * MangaDetailPage route structure:
     *   route_value = { "data": MangaData }
     *   MangaData = { "mangaData": { "manga": Manga } }
     *
     * After fetchRscData: we get MangaData.
     */
    @Suppress("UNCHECKED_CAST")
    override suspend fun getDetails(manga: Manga): Manga {
        val url = "$baseUrl/manga/${manga.url}.data?_routes=pages/MangaDetailPage"
        val mangaData = fetchRscData(url, "pages/MangaDetailPage") ?: return manga

        val mangaInfo = mangaData
            .asMap("mangaData")?.asMap("manga")
            ?: return manga

        val title = mangaInfo["title"] as? String ?: manga.title
        val description = mangaInfo["description"] as? String
        val photo = mangaInfo["photo"] as? String
        val coverUrl = photo?.let {
            when {
                it.startsWith("/") -> "$baseUrl$it"
                it.startsWith("http") -> it
                else -> null
            }
        } ?: manga.coverUrl
        val hiatus = mangaInfo["hiatus"] as? String
        val status = mangaInfo["status"] as? String
        val genres = (mangaInfo["genres"] as? List<*>)?.filterIsInstance<String>()
            ?.mapNotNull { it.trim().ifBlank { null } }.orEmpty()
        val altTitles = (mangaInfo["alt_titles"] as? List<*>)?.filterIsInstance<String>()
            ?.mapNotNull { it.trim().ifBlank { null } }?.toSet().orEmpty()
        val origin = mangaInfo["country_of_origin"] as? String
        val ratingValue = (mangaInfo["avg_rating"] as? Number)?.toFloat()
        val anilistId = (mangaInfo["anilist_id"] as? Number)?.toLong()
        val sourceUrl = mangaInfo["source_url"] as? String
        val author = mangaInfo["author"] as? String

        val state = when {
            "One Shot" in genres -> MangaState.FINISHED
            hiatus.equals("Yes", ignoreCase = true) -> MangaState.PAUSED
            else -> when (status?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                else -> null
            }
        }

        val tags = genres.mapTo(LinkedHashSet(genres.size)) { MangaTag(key = it, title = it, source = source) }
        val contentType = when (origin) {
            "JP" -> "Manga"
            "KR" -> "Manhwa"
            "CN" -> "Manhua"
            else -> null
        }
        val contentTag = contentType?.let { MangaTag(key = it, title = it, source = source) }
        val allTags = if (contentTag != null && contentTag !in tags) tags + contentTag else tags

        val richDescription = buildString {
            ratingValue?.let { rating ->
                val stars = (rating / 2).toInt().coerceIn(0, 5)
                append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} $rating\n\n")
            }
            description?.let {
                append(
                    it.replace("\r\n", "\n")
                        .replace(Regex("\n{3,}"), "\n\n")
                        .trim(),
                    "\n\n",
                )
            }
            val links = buildList {
                anilistId?.let { add("[AniList](https://anilist.co/manga/$it)") }
                sourceUrl?.let { add("[Source]($it)") }
            }
            if (links.isNotEmpty()) {
                append("\nLinks:\n")
                links.forEach { append("- ", it, "\n") }
            }
            if (altTitles.isNotEmpty()) {
                append("\nAlternative Names:\n")
                altTitles.forEach { append("- ", it, "\n") }
            }
        }.trim()

        val chapters = fetchChapters(manga.url)

        return manga.copy(
            title = title,
            coverUrl = coverUrl,
            description = richDescription,
            altTitles = altTitles,
            tags = allTags,
            state = state,
            authors = setOfNotNull(author).filterTo(mutableSetOf()) { it.isNotBlank() },
            chapters = chapters,
        )
    }

    // endregion

    // region Chapters

    @Suppress("UNCHECKED_CAST")
    private suspend fun fetchChapters(mangaId: String): List<MangaChapter> {
        val response = webClient.httpGet("$baseUrl/api/manga/$mangaId/chapters/list?lang=en").parseJsonArray()

        val allChapters = (0 until response.length()).map { i ->
            response.getJSONObject(i)
        }

        val chaptersByTeam = mutableMapOf<String, MutableList<JSONObject>>()
        for (chapter in allChapters) {
            val group = chapter.optString("group_name", "").nullIfEmpty()
            val scanlator = chapter.optString("scanlator_name", "").nullIfEmpty()
            val teamName = group ?: scanlator ?: "Unknown"
            chaptersByTeam.getOrPut(teamName) { mutableListOf() }.add(chapter)
        }

        val allChapterNumbers = allChapters.map { it.optDouble("chapter_number", 0.0).toFloat() }.toSet().sorted()

        val chaptersBuilder = ChaptersListBuilder(allChapters.size * chaptersByTeam.size)

        for ((teamName, teamChapters) in chaptersByTeam) {
            val teamChapterMap = teamChapters.associateBy { it.optDouble("chapter_number", 0.0).toFloat() }

            for (chapterNumber in allChapterNumbers) {
                val chapterData = teamChapterMap[chapterNumber]
                    ?: allChapters.find { it.optDouble("chapter_number", 0.0).toFloat() == chapterNumber }
                    ?: continue

                val chapterId = chapterData.getString("id")
                val chapterSource = chapterData.optString("source", "scraper")
                val number = chapterData.optDouble("chapter_number", 0.0).toFloat()
                val name = chapterData.optString("chapter_title", "").nullIfEmpty()
                val group = chapterData.optString("group_name", "").nullIfEmpty()
                val scanlator = chapterData.optString("scanlator_name", "").nullIfEmpty()
                val actualTeamName = group ?: scanlator ?: "Unknown"
                val date = chapterData.optString("date_added", "").nullIfEmpty()

                val title = buildString {
                    val numStr = number.toString().substringBefore(".0")
                    if (name != null && !name.contains(numStr)) {
                        append("Chapter $numStr: ")
                    } else if (name == null) {
                        append("Chapter $numStr")
                    }
                    name?.let { append(it.trim()) }
                }

                val chapter = MangaChapter(
                    id = generateUid("$teamName-$chapterId"),
                    title = title,
                    number = number,
                    volume = 0,
                    url = JSONObject().apply {
                        put("id", chapterId)
                        put("source", chapterSource)
                    }.toString(),
                    uploadDate = date?.let { dateFormat.parseSafe(it) } ?: 0L,
                    source = source,
                    scanlator = actualTeamName,
                    branch = teamName,
                )

                chaptersBuilder.add(chapter)
            }
        }

        return chaptersBuilder.toList()
    }

    // endregion

    // region Pages

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterData = JSONObject(chapter.url)
        val chapterId = chapterData.getString("id")
        val chapterSource = chapterData.optString("source", "scraper")

        val segment = if (chapterSource == "user") "uploads" else "chapters"
        val response = webClient.httpGet("$baseUrl/api/$segment/$chapterId/images").parseJson()

        // Response can be wrapped in {"data": {"manga": {"id": ...}, "images": [...]}} or flat {"images": [...]}
        val data = response.optJSONObject("data")
        val imagesArray = if (data != null && data.has("images")) {
            data.getJSONArray("images")
        } else {
            response.getJSONArray("images")
        }

        return (0 until imagesArray.length()).mapNotNull { i ->
            val img = imagesArray.getJSONObject(i)
            val imgUrl = img.optString("url", "").nullIfEmpty() ?: return@mapNotNull null
            val fullUrl = when {
                imgUrl.startsWith("/") -> "$baseUrl$imgUrl"
                imgUrl.startsWith("http") -> imgUrl
                else -> return@mapNotNull null
            }
            MangaPage(
                id = generateUid(fullUrl),
                url = fullUrl,
                preview = null,
                source = source,
            )
        }
    }

    // endregion

    // region Helpers

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.asMap(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    // endregion
}
