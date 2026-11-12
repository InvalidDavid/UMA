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

import tsuki.util.NextJsData
import tsuki.util.generateUid
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseRaw

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("VORATOON", "VoraToon", "id")
internal class VoraToon(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.VORATOON, 30) {

    override val configKeyDomain = ConfigKey.Domain("voratoon.co", "13.voratoon.com", "voratoon.id")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 403 && request.url.host.contains("voratoon")) {
            response.close()
            runCatching {
                chain.proceed(
                    request.newBuilder()
                        .url("https://$domain/")
                        .headers(request.headers.newBuilder().removeAll("Accept").build())
                        .build(),
                ).close()
            }
            return chain.proceed(request)
        }
        return response
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .add("Accept-language", "en-US,en;q=0.9,id;q=0.8")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
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
            SortOrder.POPULARITY -> "popular"
            SortOrder.UPDATED -> "new"
            SortOrder.ALPHABETICAL -> "az"
            SortOrder.RATING -> "rating"
            else -> "new"
        }

        val url = buildString {
            append("https://").append(domain).append("/browse")
            val params = mutableListOf<String>()

            if (page > 1) {
                params.add("page=$page")
            }

            params.add("sort=$sortParam")

            if (!filter.query.isNullOrBlank()) {
                params.add("q=${URLEncoder.encode(filter.query, "UTF-8")}")
            }

            filter.states.oneOrThrowIfMany()?.let { state ->
                val status = when (state) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    MangaState.PAUSED -> "hiatus"
                    MangaState.ABANDONED -> "cancelled"
                    else -> null
                }
                status?.let { params.add("status=$it") }
            }

            filter.types.oneOrThrowIfMany()?.let { type ->
                val format = when (type) {
                    ContentType.MANGA -> "manga"
                    ContentType.MANHWA -> "manhwa"
                    ContentType.MANHUA -> "manhua"
                    else -> null
                }
                format?.let { params.add("format=$it") }
            }

            if (filter.tags.isNotEmpty()) {
                val genreParam = filter.tags.joinToString(",") { it.key }
                params.add("genre=${URLEncoder.encode(genreParam, "UTF-8")}")
            }

            if (params.isNotEmpty()) {
                append("?").append(params.joinToString("&"))
            }
        }

        val html = runCatching {
            webClient.httpGet(url, getRequestHeaders()).parseRaw()
        }.getOrNull() ?: return emptyList()

        val seriesArray = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("series") && node.optJSONArray("series") != null
        }?.let { (it as JSONObject).optJSONArray("series") }
            ?: NextJsData.findLargestArray(html) { first ->
                first.has("data") && first.optJSONObject("data")?.has("slug") == true
            }

        return if (seriesArray != null) {
            parseSeriesList(seriesArray)
        } else {
            emptyList()
        }
    }

    private fun parseSeriesList(seriesArray: JSONArray): List<Manga> {
        val result = mutableListOf<Manga>()
        for (i in 0 until seriesArray.length()) {
            val item = seriesArray.optJSONObject(i) ?: continue
            val data = item.optJSONObject("data") ?: item
            val slug = data.optString("slug").takeIf { it.isNotBlank() } ?: continue
            val relativeUrl = "/series/$slug"

            val genres = mutableSetOf<MangaTag>()
            data.optJSONArray("genres")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.toGenreTagOrNull()?.let(genres::add)
                }
            }

            val cover = data.nonNullString("coverImage")?.let {
                if (it.startsWith("http")) it else "https://$domain$it"
            }
            val background = data.nonNullString("backgroundImage")?.let {
                if (it.startsWith("http")) it else "https://$domain$it"
            }

            result.add(
                Manga(
                    id = generateUid(relativeUrl),
                    url = relativeUrl,
                    publicUrl = "https://$domain$relativeUrl",
                    title = data.optString("title").ifBlank { slug.replace('-', ' ') },
                    altTitles = setOfNotNull(data.nonNullString("nativeTitle")),
                    rating = data.toRating(),
                    contentRating = null,
                    coverUrl = cover,
                    largeCoverUrl = background ?: cover,
                    tags = genres,
                    state = data.toMangaState(),
                    authors = setOfNotNull(data.nonNullString("author")),
                    source = source,
                ),
            )
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")
        val url = "https://$domain/series/$slug"
        val html = runCatching {
            webClient.httpGet(url, getRequestHeaders()).parseRaw()
        }.getOrNull() ?: return manga

        return try {
            val initData = NextJsData.findValue(html) { node ->
                node is JSONObject && node.has("series") && node.has("chapters")
            } as? JSONObject

            val seriesArray = initData?.optJSONArray("series")
            val seriesData = seriesArray?.optJSONObject(0)?.optJSONObject("data")
                ?: NextJsData.findObject(html, "slug", slug)
                ?: return manga

            val title = seriesData.optString("title").ifBlank { manga.title }
            val nativeTitle = seriesData.nonNullString("nativeTitle")
            val synopsis = seriesData.optString("synopsis", "")
            val author = seriesData.nonNullString("author")
            val coverImage = seriesData.nonNullString("coverImage")?.let {
                if (it.startsWith("http")) it else "https://$domain$it"
            } ?: manga.coverUrl
            val backgroundImage = seriesData.nonNullString("backgroundImage")?.let {
                if (it.startsWith("http")) it else "https://$domain$it"
            }
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

            val chaptersArray = initData?.optJSONArray("chapters")
                ?: NextJsData.findLargestArray(html) { first ->
                    first.has("data") && first.optJSONObject("data")?.has("index") == true
                }

            val chapters = chaptersArray?.let { parseChapters(it, slug) }
                ?.ifEmpty { null }
                ?: parseChaptersFromHtml(html, slug)
                ?: manga.chapters

            manga.copy(
                title = title,
                altTitles = setOfNotNull(nativeTitle),
                description = synopsis,
                state = seriesData.toMangaState() ?: manga.state,
                authors = setOfNotNull(author).ifEmpty { manga.authors },
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

    private fun parseChapters(chaptersArray: JSONArray, slug: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.optJSONObject(i) ?: continue
            val chapterData = ch.optJSONObject("data")

            val chapterIndex = if (ch.has("chapterIndex")) {
                ch.optDouble("chapterIndex", 0.0)
            } else {
                chapterData?.optDouble("index", 0.0) ?: 0.0
            }

            val formattedIndex = chapterIndex.toString().removeSuffix(".0")
            val chapterUrl = "/series/$slug/chapter/$formattedIndex"

            val title = chapterData?.nonNullString("title")
            val titleText = if (!title.isNullOrBlank()) {
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
                ),
            )
        }
        return chapters.sortedBy { it.number }
    }

    private fun parseChaptersFromHtml(html: String, slug: String): List<MangaChapter>? {
        return runCatching {
            val doc = Jsoup.parse(html)
            val elements = doc.select("a[href*=/series/$slug/chapter/]")
            val chapters = mutableListOf<MangaChapter>()
            for (el in elements) {
                val href = el.attr("href").substringBefore('?').substringBefore('#')
                val chNumStr = href.removeSuffix("/").substringAfterLast("/")
                val chNum = chNumStr.toFloatOrNull() ?: continue
                val titleText = el.text().trim().ifBlank { "Chapter $chNumStr" }
                chapters.add(
                    MangaChapter(
                        id = generateUid(href),
                        title = titleText,
                        url = href,
                        number = chNum,
                        volume = 0,
                        scanlator = null,
                        uploadDate = 0L,
                        branch = null,
                        source = source,
                    ),
                )
            }
            chapters.distinctBy { it.number }.sortedBy { it.number }.ifEmpty { null }
        }.getOrNull()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = if (chapter.url.startsWith("http")) chapter.url else "https://$domain${chapter.url}"
        val html = runCatching {
            webClient.httpGet(fullUrl, getRequestHeaders()).parseRaw()
        }.getOrNull() ?: return emptyList()

        // 1. Try NextJsData
        val chapterObj = NextJsData.findValue(html) { node ->
            node is JSONObject && node.has("chapter") && node.optJSONObject("chapter")?.has("data") == true
        }?.let { (it as JSONObject).optJSONObject("chapter") }

        val imagesArray = chapterObj?.optJSONObject("data")?.optJSONArray("images")
        if (imagesArray != null && imagesArray.length() > 0) {
            val pages = (0 until imagesArray.length()).mapNotNull { i ->
                val imgUrl = imagesArray.optString(i)
                if (imgUrl.isNullOrBlank()) {
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
            if (pages.isNotEmpty()) return pages
        }

        // 2. Fallback: Parse data-chapter-page DOM attributes
        val doc = Jsoup.parse(html)
        val imgElements = doc.select("img[data-chapter-page]")
        if (imgElements.isNotEmpty()) {
            val pages = imgElements.mapNotNull { el ->
                val imgUrl = el.attr("data-chapter-page").ifBlank { el.attr("src") }.trim()
                if (imgUrl.isBlank() || !imgUrl.startsWith("http")) {
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
            if (pages.isNotEmpty()) return pages
        }

        // 3. Fallback: Regex extraction of cdn.voratoon.com image URLs
        val matches = cdnImageRegex.findAll(html)
            .map { it.value }
            .distinct()
            .toList()
        if (matches.isNotEmpty()) {
            return matches.map { url ->
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }

        return emptyList()
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
        val url = "https://$domain/browse"
        val html = runCatching {
            webClient.httpGet(url, getRequestHeaders()).parseRaw()
        }.getOrNull()

        val map = mutableMapOf<String, MangaTag>()

        if (!html.isNullOrBlank()) {
            try {
                val initData = NextJsData.findValue(html) { node ->
                    node is JSONObject && node.has("genres") && node.optJSONArray("genres") != null
                } as? JSONObject

                val genresArray = initData?.optJSONArray("genres")
                    ?: NextJsData.findLargestArray(html) { first ->
                        first.has("data") && first.optJSONObject("data")?.has("name") == true
                    }

                if (genresArray != null) {
                    for (i in 0 until genresArray.length()) {
                        genresArray.optJSONObject(i)?.toGenreTagOrNull()?.let {
                            map[it.title] = it
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (map.isEmpty()) {
            for ((id, name) in STATIC_GENRES) {
                map[name] = MangaTag(title = name, key = id.toString(), source = source)
            }
        }
        return map
    }

    private fun parseChapterDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        return runCatching {
            OffsetDateTime.parse(dateStr).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching {
                isoDateFormat.parse(dateStr)?.time ?: 0L
            }.getOrDefault(0L)
        }
    }

    private fun JSONObject.nonNullString(key: String): String? {
        return optString(key).takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.toMangaState(): MangaState? {
        val status = nonNullString("status")?.lowercase(Locale.ROOT) ?: return null
        return when (status) {
            "ongoing", "publishing" -> MangaState.ONGOING
            "completed", "complete", "finished" -> MangaState.FINISHED
            "hiatus", "on hiatus" -> MangaState.PAUSED
            "canceled", "cancelled", "dropped", "axed" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun JSONObject.toRating(): Float {
        val raw = optDouble("rating", 0.0)
        return if (raw > 0.0) (raw / 10.0).toFloat().coerceIn(0f, 1f) else RATING_UNKNOWN
    }

    private fun JSONObject.toGenreTagOrNull(): MangaTag? {
        val name = optJSONObject("data")?.optString("name").orEmpty().ifBlank { optString("name") }.trim()
        val id = optInt("id", -1)
        if (name.isEmpty()) return null
        val tagKey = if (id != -1) id.toString() else name
        return MangaTag(title = name, key = tagKey, source = source)
    }

    companion object {
        private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
        private val cdnImageRegex = Regex("""https?://cdn\.voratoon\.com/[^"'\s\\<>]+\.(?:jpg|jpeg|png|webp)""")

        private val STATIC_GENRES = mapOf(
            49 to "Adult",
            11 to "4-Koma",
            19 to "Action",
            16 to "Adventure",
            22 to "Comedy",
            7 to "Cooking",
            40 to "Demons",
            29 to "Drama",
            47 to "Ecchi",
            28 to "Fantasy",
            17 to "Game",
            41 to "Gender Bender",
            15 to "Gore",
            34 to "Harem",
            10 to "Historical",
            18 to "Horror",
            6 to "Isekai",
            23 to "Josei",
            35 to "Magic",
            46 to "Martial Arts",
            37 to "Mature",
            20 to "Mecha",
            9 to "Medical",
            36 to "Military",
            44 to "Music",
            39 to "Mystery",
            33 to "One-Shot",
            43 to "Police",
            4 to "Psychological",
            14 to "Reincarnation",
            26 to "Romance",
            42 to "School",
            2 to "School Life",
            32 to "Sci-Fi",
            1 to "Seinen",
            31 to "Shoujo",
            27 to "Shoujo Ai",
            30 to "Shounen",
            25 to "Shounen Ai",
            13 to "Slice of Life",
            12 to "Sports",
            45 to "Super Power",
            24 to "Supernatural",
            5 to "Thriller",
            38 to "Tragedy",
            21 to "Vampire",
            8 to "Webtoons",
            3 to "Yuri",
        )
    }
}
