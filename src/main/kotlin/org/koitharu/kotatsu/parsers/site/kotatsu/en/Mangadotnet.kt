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

    private val genreNames = setOf(
        "Josei", "Seinen", "Shoujo", "Shounen",
        "Action", "Adventure", "Comedy", "Drama", "Fantasy",
        "Historical", "Horror", "Mecha", "Mystery", "Psychological",
        "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller", "Tragedy",
        "Cooking", "Demons", "Ecchi", "Harem", "Isekai", "Magic", "Martial Arts",
        "Medical", "Military", "Music", "School Life", "Webtoon",
        "Academy", "Acting", "Adult", "Aliens", "Animals", "Anthology", "Apocalypse",
        "Avant Garde", "Award Winning", "BDSM", "Boys Love", "Bully", "Business",
        "Child Abuse", "Child Neglect", "Comic", "Crime", "crossdressing", "Crossdressing",
        "Cultivation", "Delinquents", "Difficult Childhood", "dojinshi", "Doujinshi",
        "Erotica", "Female Protagonist", "Femdom", "Fight", "futanari on male", "futunari",
        "Gender Bender", "Genderswap", "Ghosts", "Girls Love", "Gore", "Gourmet",
        "Gyaru", "Hentai", "Hunters", "Idol", "Incest", "Loli", "Lolicon", "Mafia",
        "Magical Girls", "Mahou Shoujo", "Manga", "Manhua", "Manhwa", "Mature",
        "Medieval Area", "Monster Girls", "Monsters", "Ninja", "Nobility",
        "Office Romance", "Office Worker", "Office Workers", "One Shot", "Otome",
        "Overpowered", "Philosophical", "playboy", "Police", "Post-Apocalyptic",
        "Reincarnation", "Revenge", "Reverse Harem", "Royalty", "Samurai", "School",
        "Seinin", "Shota", "Shotacon", "Shoujo Ai", "Shounen Ai", "Smut", "Superhero",
        "Survival", "Suspense", "System", "Time Travel", "Traditional Games", "uncensored",
        "Vampires", "Video Games", "Villainess", "Virtual Reality", "War", "Workplace",
        "Wuxia", "Yaoi", "Yuri", "Zombies"
    )

    // RSC decoder
    private fun decodeRsc(flat: JSONArray): Any? {
        val cache = arrayOfNulls<Any>(flat.length())
        val nil = Any()
        fun resolve(i: Int): Any? {
            if (i < 0 || i >= flat.length()) return null
            val cached = cache[i]
            if (cached != null) return if (cached === nil) null else cached
            val result: Any? = when (val el = flat.opt(i)) {
                JSONObject.NULL -> null
                is String, is Number, is Boolean -> el
                is JSONArray -> (0 until el.length()).mapTo(mutableListOf()) { j -> resolve(el.optInt(j, -1)) }
                is JSONObject -> {
                    val map = mutableMapOf<String, Any?>()
                    for (key in el.keys()) {
                        val actualKey = if (key.startsWith("_")) flat.optString(key.removePrefix("_").toInt(), key) else key
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

    @Suppress("UNCHECKED_CAST")
    private suspend fun fetchRscData(url: String, route: String): Map<String, Any?>? {
        val flat = webClient.httpGet(url).parseJsonArray()
        val decoded = decodeRsc(flat) ?: return null
        val routeValue = (decoded as? Map<String, Any?>)?.get(route) as? Map<String, Any?> ?: return null
        return routeValue.asMap("data")
    }

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = genreNames.map { name -> MangaTag(name, name, source) }.toSet()
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
            availableContentTypes = setOf(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.OTHER),
            availableDemographics = setOf(Demographic.JOSEI, Demographic.SEINEN, Demographic.SHOUJO, Demographic.SHOUNEN),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrBlank() || filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() ||
            filter.states.isNotEmpty() || filter.types.isNotEmpty() || filter.demographics.isNotEmpty()) {
            return getSearchPage(page, order, filter)
        }
        return getBrowsePage(page, order)
    }

    private suspend fun getBrowsePage(page: Int, order: SortOrder): List<Manga> {
        val sortParam = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.RATING -> "rating"
            SortOrder.ALPHABETICAL -> "alphabetical"
            else -> null
        }
        val url = "$baseUrl/view-all/latest-updates.data".toHttpUrl().newBuilder().apply {
            addQueryParameter("adult", "0")
            if (page > 1) addQueryParameter("page", page.toString())
            sortParam?.let { addQueryParameter("sortBy", it) }
            addQueryParameter("_routes", "pages/ViewAllPage")
        }.build().toString()
        return parseViewAllPage(url)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun getSearchPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "$baseUrl/search.data".toHttpUrl().newBuilder().apply {
            addQueryParameter("adult", "0")
            if (!filter.query.isNullOrBlank()) addQueryParameter("search", filter.query!!)
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", "56")

            val (sortBy, sortOrder) = when (order) {
                SortOrder.UPDATED -> "latest" to "desc"
                SortOrder.ALPHABETICAL -> "alphabetical" to "asc"
                SortOrder.POPULARITY -> "views" to "desc"
                SortOrder.RATING -> "rating" to "desc"
                else -> "relevance" to "desc"
            }
            addQueryParameter("sortBy", sortBy)
            addQueryParameter("sortOrder", sortOrder)

            if (filter.states.size == 1) {
                addQueryParameter("status", filter.states.first().toApiStatus())
            }

            filter.types.forEach { ct ->
                when (ct) {
                    ContentType.MANGA -> addQueryParameter("origin", "JP")
                    ContentType.MANHWA -> addQueryParameter("origin", "KR")
                    ContentType.MANHUA -> addQueryParameter("origin", "CN")
                    ContentType.OTHER -> addQueryParameter("origin", "ONESHOT")
                    else -> {}
                }
            }

            filter.demographics.forEach { demographic ->
                addQueryParameter("genre", demographic.name.lowercase().replaceFirstChar { it.uppercase() })
            }

            filter.tags.forEach { tag ->
                addQueryParameter("genre", tag.key)
            }
            filter.tagsExclude.forEach { tag ->
                addQueryParameter("genre", "-${tag.key}")
            }

            addQueryParameter("_routes", "pages/SearchPage")
        }.build().toString()

        val dataMap = fetchRscData(url, "pages/SearchPage") ?: return emptyList()
        return parseMangaList(dataMap)
    }

    private suspend fun parseViewAllPage(url: String): List<Manga> {
        val viewAllData = fetchRscData(url, "pages/ViewAllPage") ?: return emptyList()
        val mangaListMap = viewAllData.asMap("data") ?: return emptyList()
        return parseMangaList(mangaListMap)
    }

    private fun parseMangaList(data: Map<String, Any?>): List<Manga> {
        val list = data["manga_list"] as? List<*> ?: data["results"] as? List<*> ?: return emptyList()
        return list.filterIsInstance<Map<String, Any?>>().map { parseMangaFromList(it) }
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

    @Suppress("UNCHECKED_CAST")
    override suspend fun getDetails(manga: Manga): Manga {
        val url = "$baseUrl/manga/${manga.url}.data?_routes=pages/MangaDetailPage"
        val mangaData = fetchRscData(url, "pages/MangaDetailPage") ?: return manga

        val mangaInfo = mangaData.asMap("mangaData")?.asMap("manga") ?: return manga
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
        val bannerImage = mangaInfo["banner_image"] as? String
        val largeCoverUrl = bannerImage?.let {
            when {
                it.startsWith("/") -> "$baseUrl$it"
                it.startsWith("http") -> it
                else -> null
            }
        }
        val hiatus = mangaInfo["hiatus"] as? String
        val status = mangaInfo["status"] as? String
        val genres = (mangaInfo["genres"] as? List<*>)?.filterIsInstance<String>()?.mapNotNull { it.trim().ifBlank { null } }.orEmpty()
        val altTitles = (mangaInfo["alt_titles"] as? List<*>)?.filterIsInstance<String>()?.mapNotNull { it.trim().ifBlank { null } }?.toSet().orEmpty()
        val origin = mangaInfo["country_of_origin"] as? String
        val ratingValue = (mangaInfo["avg_rating"] as? Number)?.toFloat()
        val authorRaw = mangaInfo["authors"] as? String
        val author = parseJsonArrayString(authorRaw)?.joinToString(", ") ?: authorRaw
        val year = (mangaInfo["year"] as? Number)?.toInt()
        val chapterCount = (mangaInfo["chapter_count"] as? Number)?.toInt()
        val trackedCount = (mangaInfo["tracked_count"] as? Number)?.toInt()
        val contentRating = mangaInfo["content_rating"] as? String
        val ratingCount = (mangaInfo["rating_count"] as? Number)?.toInt()

        val state = when {
            "One Shot" in genres -> MangaState.FINISHED
            hiatus.equals("Yes", ignoreCase = true) -> MangaState.PAUSED
            else -> when (status?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                else -> null
            }
        }

        val tagSet = genres.mapTo(LinkedHashSet(genres.size)) { MangaTag(key = it, title = it, source = source) }
        when (origin) {
            "JP" -> tagSet.add(MangaTag("Manga", "Manga", source))
            "KR" -> tagSet.add(MangaTag("Manhwa", "Manhwa", source))
            "CN" -> tagSet.add(MangaTag("Manhua", "Manhua", source))
        }

        val tagsArray = mangaInfo["tags"] as? List<Map<String, Any?>>
        if (tagsArray != null) {
            tagsArray.flatMap { category ->
                (category["tags"] as? List<Map<String, Any?>> ?: emptyList())
                    .mapNotNull { it["name"] as? String }
            }
                .distinct()
                .take(20)
                .forEach { tagSet.add(MangaTag(it, it, source)) }
        }

        val anilistId = (mangaInfo["anilist_id"] as? Number)?.toLong()
        val malId = (mangaInfo["mal_id"] as? Number)?.toLong()
        val mangadexId = mangaInfo["mangadex_id"] as? String
        val mangaupdatesId = mangaInfo["mangaupdates_id"] as? String
        val kitsuId = (mangaInfo["kitsu_id"] as? Number)?.toLong()
        val mangabakaId = (mangaInfo["mangabaka_id"] as? Number)?.toLong()
        val sourceUrl = mangaInfo["source_url"] as? String

        val richDescription = buildString {
            ratingValue?.let { rating ->
                val stars = (rating / 2).toInt().coerceIn(0, 5)
                append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} $rating\n\n")
            }
            val metaInfo = buildList {
                year?.let { add("**Year:** $it") }
                chapterCount?.let { add("**Chapters:** $it") }
                trackedCount?.let { add("**Tracked:** $it") }
                contentRating?.let { add("**Content Rating:** ${it.replaceFirstChar { c -> c.uppercase() }}") }
                ratingCount?.takeIf { it > 0 }?.let { add("$it ratings") }
            }
            if (metaInfo.isNotEmpty()) {
                append(metaInfo.joinToString(" · "), "\n\n")
            }
            description?.let {
                append(it.replace("\r\n", "\n").replace(Regex("\n{3,}"), "\n\n").trim(), "\n\n")
            }
            val links = buildList {
                anilistId?.let { add("[AniList](https://anilist.co/manga/$it)") }
                malId?.let { add("[MyAnimeList](https://myanimelist.net/manga/$it)") }
                mangadexId?.let { add("[MangaDex](https://mangadex.org/title/$it)") }
                mangaupdatesId?.let { add("[MangaUpdates](https://www.mangaupdates.com/series/$it)") }
                kitsuId?.let { add("[Kitsu](https://kitsu.app/manga/$it)") }
                mangabakaId?.let { add("[MangaBaka](https://mangabaka.org/$it)") }
                sourceUrl?.let { add("[Source]($it)") }
            }
            if (links.isNotEmpty()) {
                append("\n**Links:**\n")
                links.forEach { append("- ", it, "\n") }
            }
            if (altTitles.isNotEmpty()) {
                append("\n**Alternative Names:**\n")
                altTitles.forEach { append("- ", it, "\n") }
            }
        }.trim()

        val chapters = fetchChapters(manga.url)

        return manga.copy(
            title = title,
            coverUrl = coverUrl,
            largeCoverUrl = largeCoverUrl,
            description = richDescription,
            altTitles = altTitles,
            tags = tagSet,
            state = state,
            authors = setOfNotNull(author).filterTo(mutableSetOf()) { it.isNotBlank() },
            chapters = chapters,
        )
    }

    private fun parseJsonArrayString(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it) }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchChapters(mangaId: String): List<MangaChapter> {
        val response = webClient.httpGet("$baseUrl/api/manga/$mangaId/chapters/list?lang=en").parseJsonArray()
        val allChapters = (0 until response.length()).map { response.getJSONObject(it) }

        val chaptersByTeam = mutableMapOf<String, MutableList<JSONObject>>()
        for (ch in allChapters) {
            val team = listOf(
                ch.optString("group_name", ""),
                ch.optString("scanlator_name", "")
            ).firstOrNull { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?: "Unknown"
            chaptersByTeam.getOrPut(team) { mutableListOf() }.add(ch)
        }

        val allNumbers = allChapters.map { it.optDouble("chapter_number", 0.0).toFloat() }.distinct().sorted()
        val builder = ChaptersListBuilder(allChapters.size * chaptersByTeam.size)

        for ((team, teamChapters) in chaptersByTeam) {
            val mapByNum = teamChapters.associateBy { it.optDouble("chapter_number", 0.0).toFloat() }
            for (num in allNumbers) {
                val chapter = mapByNum[num] ?: continue
                val chId = chapter.getString("id")
                val src = chapter.optString("source", "user")
                val name = chapter.optString("chapter_title", "").nullIfEmpty()
                val date = chapter.optString("date_added", "").nullIfEmpty()
                val title = buildString {
                    val numStr = num.toString().removeSuffix(".0")
                    if (name != null && !name.contains(numStr)) {
                        append("Chapter $numStr: ")
                    } else if (name == null) {
                        append("Chapter $numStr")
                    }
                    name?.let { append(it.trim()) }
                }
                val chapterUrl = JSONObject().apply {
                    put("id", chId)
                    put("source", src)
                }.toString()

                builder.add(
                    MangaChapter(
                        id = generateUid("$team-$chId"),
                        title = title,
                        number = num,
                        volume = 0,
                        url = chapterUrl,
                        uploadDate = date?.let { dateFormat.parseSafe(it) } ?: 0L,
                        source = source,
                        scanlator = team,
                        branch = team,
                    )
                )
            }
        }

        return builder.toList()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterData = JSONObject(chapter.url)
        val chapterId = chapterData.getString("id")
        val source = chapterData.optString("source", "user")
        val segment = if (source == "user") "uploads" else "chapters"
        val response = webClient.httpGet("$baseUrl/api/$segment/$chapterId/images").parseJson()
        val data = response.optJSONObject("data")
        val imagesArray = if (data != null && data.has("images")) data.getJSONArray("images") else response.getJSONArray("images")
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
                source = this.source,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val url = "$baseUrl/manga/${seed.url}.data?_routes=pages/MangaDetailPage"
        val data = fetchRscData(url, "pages/MangaDetailPage") ?: return emptyList()

        val related = mutableListOf<Manga>()

        // suggestions
        (data["suggestions"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.mapTo(related) { parseMangaFromList(it) }

        // relations
        val relationsData = data.asMap("relationsData")
        val relations = relationsData?.asMap("relations") as? Map<String, List<Map<String, Any?>>>
        relations?.values?.forEach { list -> list.mapTo(related) { parseMangaFromList(it) } }

        return related
    }

    private fun MangaState.toApiStatus() = when (this) {
        MangaState.ONGOING -> "Ongoing"
        MangaState.FINISHED -> "Completed"
        MangaState.PAUSED -> "Hiatus"
        else -> ""
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.asMap(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
}
