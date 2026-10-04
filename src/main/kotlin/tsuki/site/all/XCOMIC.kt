package tsuki.site.all

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Demographic
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
import tsuki.util.parseRaw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val BROWSE_PAGE_SIZE = 24
private const val CHAPTER_PAGE_SIZE = 100
private const val COMIC_PROBES_PER_TITLE = 5

private val ID_QUERY by lazy {
    Regex("^id\\s*:?\\s*([a-zA-Z0-9\\-_]+)\\s*$", RegexOption.IGNORE_CASE)
}
private val URL_REGEX by lazy {
    Regex("(?<![\\[(])(https?://[^\\s<\"]+)")
}
private val TITLE_REGEX by lazy {
    Regex(
        "\\([^()]*\\)|\\{[^{}]*\\}|\\[(?:(?!\\]).)*\\]|«[^»]*»|〘[^〙]*〙|「[^」]*」|『[^』]*』" +
                "|≪[^≫]*≫|﹛[^﹜]*﹜|〖[^〖〗]*〗|《[^》]*》|/Official|/ Official",
        RegexOption.IGNORE_CASE,
    )
}

private object XComicQueries {
    const val TITLE_BROWSE = $$"""
        query get_title_browse($select: Title_Browse_Select) {
            get_title_browse_items(select: $select) {
                id
                data {
                    title native_title romanized_title original_language
                    translated_languages type cover_local_url cover_url
                    comic_ids chap_last_public_at
                }
            }
        }
    """

    const val TITLE_NODE = $$"""
        query get_title_titleNode($id: ID!) {
            get_title_titleNode(id: $id) {
                id
                data {
                    title alt_titles native_title romanized_title original_language
                    translated_languages authors artists year type status description
                    cover_local_url cover_url urlPath total_comics total_chapters
                    total_follows total_reviews total_comments vote_avg vote_users
                    chap_last_public_at is_merged merged_to comic_ids
                    content_rating_id type_id demographic_ids genre_ids format_ids
                    tracking_sites {
                        anilist myanimelist mangaupdates kitsu animeplanet shikimori mangabaka
                    }
                }
            }
        }
    """

    const val COMIC_NODE = $$"""
        query get_comicNode($id: ID!) {
            get_comicNode(id: $id) {
                id
                data {
                    id name subName altNames authors artists
                    originalLanguage translatedLanguage originalStatus uploadStatus
                    type demographics contentRating genres tags publishers dbStatus isPublic
                    follows reviews comments_total score_val is_hot is_new
                    chaps_normal dateUpload
                    chapterNode_up_to { id data { dname datePublic } }
                    summary { text }
                    extraInfo { text }
                    urlPath urlCover
                }
            }
        }
    """

    const val COMIC_PROBE = $$"""
        query get_comicNode($id: ID!) {
            get_comicNode(id: $id) {
                id
                data {
                    name subName dbStatus isPublic translatedLanguage chaps_normal urlPath urlCover
                }
            }
        }
    """

    const val CHAPTER_LIST = $$"""
        query get_comic_chapterList_fullList($select: Select_Comic_ChapterList) {
            get_comic_chapterList_fullList(select: $select) {
                paging { next total }
                items {
                    id
                    data {
                        id comicId dbStatus isFinal volume serial dname title urlPath
                        dateCreate datePublic dateModify chaNum volNum count_images is_new
                        srcName profileNodes { data { name } }
                    }
                }
            }
        }
    """

    const val CHAPTER_PAGES = $$"""
        query($id: ID!) {
            get_chapterNode(id: $id) { id data { imageUrls } }
        }
    """
}

private fun JSONObject.strOrNull(k: String): String? =
    if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null

private fun JSONObject.longOrNull(k: String): Long? =
    if (has(k) && !isNull(k)) optLong(k) else null

private fun JSONObject.intOrNull(k: String): Int? =
    if (has(k) && !isNull(k)) optInt(k) else null

private fun JSONObject.floatOrNull(k: String): Float? =
    if (has(k) && !isNull(k)) optDouble(k).toFloat() else null

private fun JSONObject.boolOrNull(k: String): Boolean? =
    if (has(k) && !isNull(k)) optBoolean(k) else null

private fun JSONObject.objOrNull(k: String): JSONObject? =
    if (has(k) && !isNull(k)) optJSONObject(k) else null

private fun JSONObject.arrOrNull(k: String): JSONArray? =
    if (has(k) && !isNull(k)) optJSONArray(k) else null

private fun JSONObject.stringList(k: String): List<String> {
    val arr = arrOrNull(k)
    if (arr != null) {
        return (0 until arr.length()).mapNotNull { i ->
            if (arr.isNull(i)) null else arr.optString(i).takeIf { it.isNotEmpty() }
        }
    }
    val str = strOrNull(k)
    if (str != null) {
        return str.split(" ").filter { it.isNotBlank() }
    }
    return emptyList()
}

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray.strings(): List<String> =
    (0 until length()).mapNotNull { i ->
        if (isNull(i)) null else optString(i).takeIf { it.isNotEmpty() }
    }

private fun HttpUrl.csv(name: String): List<String> =
    queryParameter(name)?.split(",")?.filter { it.isNotBlank() }.orEmpty()

/** Reads `tl`, `translated_langs`, `incTLangs`, or `translated_languages` from a URL, whichever is present. */
private fun HttpUrl.translatedLangs(): List<String> {
    val candidates = listOf("tl", "translated_langs", "incTLangs", "translated_languages")
    return candidates.flatMap { csv(it) }.distinct()
}

private fun String?.toContentRating(): ContentRating = when (this) {
    "suggestive" -> ContentRating.SUGGESTIVE
    "erotica", "pornographic", "adult" -> ContentRating.ADULT
    else -> ContentRating.SAFE
}

private interface Liveable {
    val dbStatus: String?
    val isPublic: Boolean?

    fun isLive() = isPublic != false && (dbStatus == null || dbStatus == "normal")
}

private data class BrowseQuery(
    val word: String,
    val sort: String,
    val types: List<String>,
    val demographics: List<String>,
    val contentRatings: List<String>,
    val translatedLanguages: List<String>,
    val genres: List<String>,
    val excludedGenres: List<String>,
    val statuses: List<String>,
    val yearMin: Int?,
    val yearMax: Int?,
) {
    fun toVariables(apiPage: Int): JSONObject = JSONObject().apply {
        put("word", word)
        put("page", apiPage)
        put("size", BROWSE_PAGE_SIZE)
        put("init", (apiPage - 1) * BROWSE_PAGE_SIZE)
        put("sortby", sort)
        put("where", "browse")
        put("releaseYearMin", yearMin ?: JSONObject.NULL)
        put("releaseYearMax", yearMax ?: JSONObject.NULL)
        put("incTypes", JSONArray(types))
        put("incDemographics", JSONArray(demographics))
        put("incContentRatings", JSONArray(contentRatings))
        put("incOLangs", JSONArray())
        put("incTLangs", JSONArray(translatedLanguages))
        put("incGenres", JSONArray(genres))
        put("excGenres", JSONArray(excludedGenres))
        put("incGenresMode", JSONObject.NULL)
        put("excGenresMode", JSONObject.NULL)
        put("origStatus", JSONArray(statuses))
        put("chapCount", JSONObject.NULL)
        put("ignoreGlobalGenres", false)
        put("ignoreGlobalULangs", false)
        put("ignoreGlobalBlocks", false)
    }
}

@MangaSourceParser("XCOMIC", "XCOMIC")
internal class XComic(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.XCOMIC, pageSize = BROWSE_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("xcomic.me")

    init {
        paginator.firstPage = 0
        searchPaginator.firstPage = 0
    }

    private val probeCache = ConcurrentHashMap<String, ComicProbe>()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.RELEVANCE,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isYearRangeSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GENRE_TAGS.mapTo(mutableSetOf()) { (name, slug) ->
            MangaTag(key = slug, title = name, source = source)
        },
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
            MangaState.UPCOMING,
        ),
        availableContentRating = EnumSet.of(
            ContentRating.SAFE,
            ContentRating.SUGGESTIVE,
            ContentRating.ADULT,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
            ContentType.IMAGE_SET,
            ContentType.OTHER,
        ),
        availableDemographics = EnumSet.of(
            Demographic.SHOUNEN,
            Demographic.SHOUJO,
            Demographic.SEINEN,
            Demographic.JOSEI,
            Demographic.KODOMO,
        ),
        availableLocales = XCOMIC_LANGS.mapTo(mutableSetOf()) { (_, code) ->
            xcomicCodeToLocale(code)
        },
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val rawQuery = filter.query?.trim().orEmpty()

        ID_QUERY.matchEntire(rawQuery)?.let { match ->
            val id = match.groupValues[1].substringBefore("-")
            return listOfNotNull(fetchTitleNode(id)?.toManga(id))
        }

        val searchUrl = rawQuery
            .takeIf { "/search" in it || "genres_in=" in it || "types=" in it }
            ?.toHttpUrlOrNull()
        val query = if (searchUrl != null) {
            queryFromUrl(searchUrl, order)
        } else {
            queryFromFilter(rawQuery, filter, order)
        }

        val activeLangs = query.translatedLanguages.filter { it.isNotBlank() }.distinct()
        val langParam = if (activeLangs.isEmpty()) "" else "?lang=" + activeLangs.joinToString(",")

        val data = postGraphQL(
            XComicQueries.TITLE_BROWSE,
            JSONObject().put("select", query.toVariables(page + 1)),
        )
        val items = data.objOrNull("data")?.arrOrNull("get_title_browse_items") ?: return emptyList()

        return items.objects().mapNotNull { item ->
            val titleId = item.strOrNull("id") ?: return@mapNotNull null
            val d = item.objOrNull("data") ?: return@mapNotNull null
            val title = cleanTitle(d.strOrNull("title") ?: titleId).ifBlank { titleId }
            val cover = (d.strOrNull("cover_local_url") ?: d.strOrNull("cover_url"))?.toAbsolute()

            Manga(
                id = generateUid(titleId),
                url = titleId + langParam,
                publicUrl = "https://$domain/title/$titleId",
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    private fun queryFromUrl(url: HttpUrl, order: SortOrder) = BrowseQuery(
        word = url.queryParameter("word") ?: url.queryParameter("q").orEmpty(),
        sort = url.queryParameter("sortby")?.takeIf { it.isNotBlank() } ?: sortFor(order),
        types = url.csv("types"),
        demographics = url.csv("demographic"),
        contentRatings = url.csv("content_ratings"),
        translatedLanguages = url.translatedLangs(),
        genres = url.csv("genres_in"),
        excludedGenres = url.csv("genres_ex"),
        statuses = url.csv("status"),
        yearMin = url.queryParameter("year_min")?.toIntOrNull(),
        yearMax = url.queryParameter("year_max")?.toIntOrNull(),
    )

    private fun queryFromFilter(word: String, filter: MangaListFilter, order: SortOrder) = BrowseQuery(
        word = word,
        sort = sortFor(order),
        types = filter.types.mapNotNull { it.toApiType() },
        demographics = filter.demographics.mapNotNull { it.toApiDemo() },
        contentRatings = filter.contentRating.flatMap { it.toApiRatings() },
        translatedLanguages = listOfNotNull(filter.locale?.toXComicLangCode()),
        genres = filter.tags.map { it.key },
        excludedGenres = filter.tagsExclude.map { it.key },
        statuses = filter.states.mapNotNull { it.toApiStatus() },
        yearMin = filter.yearFrom.takeIf { it > 0 } ?: filter.year.takeIf { it > 0 },
        yearMax = filter.yearTo.takeIf { it > 0 } ?: filter.year.takeIf { it > 0 },
    )

    private fun TitleNodeData.toManga(titleId: String) = Manga(
        id = generateUid(titleId),
        url = titleId,
        publicUrl = "https://$domain/title/$titleId",
        title = cleanTitle(title.orEmpty()).ifBlank { titleId },
        altTitles = altTitles.toSet(),
        rating = voteAvg?.div(10f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN,
        contentRating = contentRating.toContentRating(),
        coverUrl = (coverLocalUrl ?: coverUrl)?.toAbsolute(),
        tags = genreIds.mapTo(mutableSetOf()) { MangaTag(key = it, title = it.toTagCase(), source = source) },
        state = status.toMangaState(),
        authors = authors.toSet(),
        source = source,
    )

    private fun String.toAbsolute(): String = if (startsWith("http")) this else "https://$domain$this"

    /** Splits `titleId?lang=en,de` into `(titleId, [en, de])`. */
    private fun splitMangaUrl(url: String): Pair<String, List<String>> {
        val idx = url.indexOf("?lang=")
        if (idx < 0) return url to emptyList()
        val titleId = url.substring(0, idx)
        val langs = url.substring(idx + "?lang=".length)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return titleId to langs
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val (titleId, langFilter) = splitMangaUrl(manga.url)

        val title = fetchTitleNode(titleId)
            ?: return@coroutineScope manga.copy(chapters = emptyList())

        val resolved = if (title.isMerged == true &&
            !title.mergedTo.isNullOrBlank() &&
            title.mergedTo != titleId
        ) {
            fetchTitleNode(title.mergedTo) ?: title
        } else title

        val comicIds = resolved.comicIds.filter { it.isNotBlank() }
        val probes = mutableMapOf<String, ComicProbe>()
        coroutineScope {
            comicIds.chunked(COMIC_PROBES_PER_TITLE).forEach { chunk ->
                chunk.map { cid ->
                    async {
                        fetchComicProbe(cid)?.let { probes[cid] = it }
                    }
                }.awaitAll()
            }
        }

        val allLive = probes
            .filterValues { it.isLive() }
            .entries
            .sortedByDescending { it.value.chapsNormal ?: 0 }

        if (allLive.isEmpty()) {
            return@coroutineScope manga.copy(chapters = emptyList())
        }

        val liveComics = if (langFilter.isEmpty()) {
            allLive
        } else {
            allLive.filter { (_, probe) ->
                val lang = probe.translatedLanguage?.takeIf { it.isNotBlank() } ?: "_t"
                lang in langFilter
            }.ifEmpty { allLive }
        }

        val chaptersByComic: List<Triple<String, ComicProbe, List<MangaChapter>>> =
            liveComics.map { (comicId, probe) ->
                async { Triple(comicId, probe, fetchChapters(comicId)) }
            }.awaitAll()

        val candidateLabels: Map<String, String> = chaptersByComic.associate { (comicId, probe, chapters) ->
            val langCode = probe.translatedLanguage?.takeIf { it.isNotBlank() } ?: "_t"
            val langLabel = langDisplayName(langCode)
            val uploader = chapters.firstNotNullOfOrNull { ch ->
                ch.scanlator?.takeIf { it.isNotBlank() }
            } ?: probe.name?.takeIf { it.isNotBlank() }

            val label = if (!uploader.isNullOrBlank() && !uploader.equals(langLabel, ignoreCase = true)) {
                "$langLabel • $uploader"
            } else {
                langLabel
            }
            comicId to label
        }

        val labelCounts = candidateLabels.values.groupingBy { it }.eachCount()
        val finalLabels: Map<String, String> = candidateLabels.mapValues { (comicId, base) ->
            if ((labelCounts[base] ?: 0) > 1) {
                "$base · ${comicId.takeLast(4)}"
            } else {
                base
            }
        }

        val allChapters = chaptersByComic
            .flatMap { (comicId, _, chapters) ->
                val label = finalLabels[comicId]!!
                chapters.map { it.copy(branch = label, scanlator = label) }
            }
            .sortedWith(compareBy({ it.number }, { it.branch ?: "" }))

        val primaryComicId = liveComics.first().key
        val primaryComic = fetchComicNode(primaryComicId)

        manga.copy(
            title = resolved.title?.let { cleanTitle(it) } ?: manga.title,
            altTitles = resolved.altTitles.toSet(),
            description = primaryComic?.let { buildDescription(it) } ?: "",
            authors = (resolved.authors + (primaryComic?.authorNames ?: emptyList())).toSet(),
            tags = (resolved.genreIds + (primaryComic?.genres ?: emptyList())).mapTo(mutableSetOf()) {
                MangaTag(key = it, title = it.toTagCase(), source = source)
            },
            state = (primaryComic?.status() ?: resolved.status).toMangaState(),
            rating = resolved.voteAvg?.div(10f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN,
            contentRating = (resolved.contentRating ?: primaryComic?.contentRating).toContentRating(),
            coverUrl = (resolved.coverLocalUrl ?: primaryComic?.urlCover ?: resolved.coverUrl)?.toAbsolute()
                ?: manga.coverUrl,
            chapters = allChapters,
        )
    }

    private fun buildDescription(c: ComicNode): String =
        c.summary?.takeIf { it.isNotBlank() }?.trim()?.toMarkdownUrls().orEmpty()

    private suspend fun fetchChapters(comicId: String): List<MangaChapter> = coroutineScope {
        val first = fetchChapterPage(comicId, 1)
        val all = first.chapters.toMutableList()
        val total = first.total ?: 0
        if (total > CHAPTER_PAGE_SIZE && first.hasNext) {
            val totalPages = (total + CHAPTER_PAGE_SIZE - 1) / CHAPTER_PAGE_SIZE
            (2..totalPages).chunked(3).forEach { batch ->
                val pages = batch.map { p -> async { fetchChapterPage(comicId, p).chapters } }
                all.addAll(pages.awaitAll().flatten())
            }
        }
        all.sortedBy { it.number }
    }

    private data class ChapterPage(val chapters: List<MangaChapter>, val total: Int?, val hasNext: Boolean)

    private suspend fun fetchChapterPage(comicId: String, page: Int): ChapterPage {
        val variables = JSONObject().apply {
            put(
                "select",
                JSONObject().apply {
                    put("comic_id", comicId)
                    put("page", page)
                    put("size", CHAPTER_PAGE_SIZE)
                    put("sortby", "chapter_desc")
                },
            )
        }

        val data = postGraphQL(XComicQueries.CHAPTER_LIST, variables)
        val root = data.objOrNull("data")?.objOrNull("get_comic_chapterList_fullList")
            ?: return ChapterPage(emptyList(), 0, false)
        val paging = root.objOrNull("paging")
        val items = root.arrOrNull("items") ?: return ChapterPage(emptyList(), 0, false)

        return ChapterPage(
            chapters = items.objects().mapNotNull { it.toChapter(comicId) },
            total = paging?.intOrNull("total"),
            hasNext = (paging?.intOrNull("next") ?: 0) != 0,
        )
    }

    private fun JSONObject.toChapter(comicId: String): MangaChapter? {
        val wrapperData = objOrNull("data") ?: return null
        val chapterId = wrapperData.strOrNull("id") ?: return null
        val number = wrapperData.floatOrNull("chaNum") ?: wrapperData.floatOrNull("serial") ?: 0f
        val dname = wrapperData.optString("dname", "")
        val title = wrapperData.strOrNull("title")
        val srcName = wrapperData.strOrNull("srcName")
        val profileNames = wrapperData.arrOrNull("profileNodes")
            ?.objects()
            ?.mapNotNull { it.objOrNull("data")?.strOrNull("name") }
            ?.joinToString()
            ?.takeIf { it.isNotEmpty() }
        val date = wrapperData.longOrNull("dateModify")
            ?: wrapperData.longOrNull("dateCreate")
            ?: wrapperData.longOrNull("datePublic")
            ?: 0L

        val name = buildString {
            val n = number.toString().removeSuffix(".0")
            if (!dname.contains(n)) append("Chapter ", n)
            if (dname.isNotEmpty()) {
                if (isNotEmpty()) append(": ")
                append(dname)
            }
            if (!title.isNullOrEmpty()) {
                if (isNotEmpty()) append(": ")
                append(title)
            }
        }

        val chapterUrl = "$chapterId:$comicId"

        return MangaChapter(
            id = generateUid(chapterUrl),
            title = name,
            number = number,
            volume = 0,
            url = chapterUrl,
            scanlator = srcName ?: profileNames,
            uploadDate = date,
            branch = null,
            source = source,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringBefore(":")
        val data = postGraphQL(
            XComicQueries.CHAPTER_PAGES,
            JSONObject().apply { put("id", chapterId) },
        )
        val urls = data.objOrNull("data")
            ?.objOrNull("get_chapterNode")
            ?.objOrNull("data")
            ?.arrOrNull("imageUrls")
            ?.strings()
            ?: return emptyList()

        return urls.map { url ->
            val abs = url.toAbsolute()
            MangaPage(id = generateUid(abs), url = abs, preview = null, source = source)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        return if (host.contains("img") || host.contains("xcomic")) {
            val newRequest = request.newBuilder()
                .header("Referer", "https://$domain/")
                .build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(request)
        }
    }

    private suspend fun postGraphQL(query: String, variables: JSONObject): JSONObject {
        val payload = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }
        val headers = getRequestHeaders().newBuilder()
            .set("Origin", "https://$domain")
            .set("Referer", "https://$domain/")
            .build()
        val response = webClient.httpPost("https://$domain/query/".toHttpUrl(), payload, headers)
        return JSONObject(response.parseRaw())
    }

    private suspend fun <T> fetchNode(query: String, id: String, field: String, map: JSONObject.() -> T): T? =
        try {
            postGraphQL(query, JSONObject().put("id", id))
                .objOrNull("data")
                ?.objOrNull(field)
                ?.objOrNull("data")
                ?.map()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun fetchTitleNode(id: String): TitleNodeData? =
        fetchNode(XComicQueries.TITLE_NODE, id, "get_title_titleNode") { toTitleNodeData() }

    private suspend fun fetchComicNode(id: String): ComicNode? =
        fetchNode(XComicQueries.COMIC_NODE, id, "get_comicNode") { toComicNode(id) }

    private suspend fun fetchComicProbe(id: String): ComicProbe? {
        probeCache[id]?.let { return it }
        val fetched = fetchNode(XComicQueries.COMIC_PROBE, id, "get_comicNode") { toComicProbe(id) }
        if (fetched != null) probeCache[id] = fetched
        return fetched
    }

    private fun JSONObject.toTitleNodeData(): TitleNodeData = TitleNodeData(
        id = strOrNull("id"),
        title = strOrNull("title"),
        altTitles = stringList("alt_titles"),
        originalLanguage = strOrNull("original_language"),
        translatedLanguages = stringList("translated_languages"),
        authors = stringList("authors"),
        contentRating = strOrNull("content_rating_id"),
        genreIds = stringList("genre_ids"),
        year = intOrNull("year"),
        status = strOrNull("status"),
        coverLocalUrl = strOrNull("cover_local_url"),
        coverUrl = strOrNull("cover_url"),
        voteAvg = floatOrNull("vote_avg"),
        totalFollows = intOrNull("total_follows"),
        totalReviews = intOrNull("total_reviews"),
        totalComments = intOrNull("total_comments"),
        isMerged = boolOrNull("is_merged"),
        mergedTo = strOrNull("merged_to"),
        comicIds = stringList("comic_ids"),
    )

    private fun JSONObject.toComicNode(fallbackId: String): ComicNode = ComicNode(
        id = strOrNull("id") ?: fallbackId,
        name = strOrNull("name").orEmpty(),
        subName = strOrNull("subName"),
        translatedLanguage = strOrNull("translatedLanguage"),
        originalStatus = strOrNull("originalStatus"),
        uploadStatus = strOrNull("uploadStatus"),
        type = strOrNull("type"),
        contentRating = strOrNull("contentRating"),
        genres = stringList("genres"),
        authorNames = arrOrNull("authorNodes")
            ?.objects()
            ?.mapNotNull { it.objOrNull("data")?.strOrNull("name") }
            ?: stringList("authors"),
        summary = objOrNull("summary")?.strOrNull("text"),
        dbStatus = strOrNull("dbStatus"),
        isPublic = boolOrNull("isPublic"),
        isHot = boolOrNull("is_hot"),
        isNew = boolOrNull("is_new"),
        chapsNormal = intOrNull("chaps_normal"),
        urlCover = strOrNull("urlCover"),
    )

    private fun JSONObject.toComicProbe(id: String): ComicProbe = ComicProbe(
        id = id,
        name = strOrNull("name"),
        subName = strOrNull("subName"),
        dbStatus = strOrNull("dbStatus"),
        isPublic = boolOrNull("isPublic"),
        translatedLanguage = strOrNull("translatedLanguage"),
        chapsNormal = intOrNull("chaps_normal"),
        urlPath = strOrNull("urlPath"),
        urlCover = strOrNull("urlCover"),
    )

    private data class TitleNodeData(
        val id: String?,
        val title: String?,
        val altTitles: List<String>,
        val originalLanguage: String?,
        val translatedLanguages: List<String>,
        val authors: List<String>,
        val contentRating: String?,
        val genreIds: List<String>,
        val year: Int?,
        val status: String?,
        val coverLocalUrl: String?,
        val coverUrl: String?,
        val voteAvg: Float?,
        val totalFollows: Int?,
        val totalReviews: Int?,
        val totalComments: Int?,
        val isMerged: Boolean?,
        val mergedTo: String?,
        val comicIds: List<String>,
    )

    private data class ComicNode(
        val id: String,
        val name: String,
        val subName: String?,
        val translatedLanguage: String?,
        val originalStatus: String?,
        val uploadStatus: String?,
        val type: String?,
        val contentRating: String?,
        val genres: List<String>,
        val authorNames: List<String>,
        val summary: String?,
        override val dbStatus: String?,
        override val isPublic: Boolean?,
        val isHot: Boolean?,
        val isNew: Boolean?,
        val chapsNormal: Int?,
        val urlCover: String?,
    ) : Liveable {
        fun status(): String? = originalStatus ?: uploadStatus
    }

    private data class ComicProbe(
        val id: String,
        val name: String?,
        val subName: String?,
        override val dbStatus: String?,
        override val isPublic: Boolean?,
        val translatedLanguage: String?,
        val chapsNormal: Int?,
        val urlPath: String?,
        val urlCover: String?,
    ) : Liveable

    private fun sortFor(order: SortOrder): String = when (order) {
        SortOrder.POPULARITY -> "field_follow"
        SortOrder.RATING -> "field_score"
        SortOrder.NEWEST -> "field_create"
        SortOrder.UPDATED -> "field_update"
        SortOrder.ALPHABETICAL -> "field_name_asc"
        SortOrder.ALPHABETICAL_DESC -> "field_name_desc"
        SortOrder.RELEVANCE -> "field_chapter"
        else -> "field_update"
    }

    private fun MangaState.toApiStatus(): String? = when (this) {
        MangaState.ONGOING -> "releasing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        MangaState.ABANDONED -> "cancelled"
        MangaState.UPCOMING -> "upcoming"
        else -> null
    }

    private fun ContentRating.toApiRatings(): List<String> = when (this) {
        ContentRating.SAFE -> listOf("safe")
        ContentRating.SUGGESTIVE -> listOf("suggestive")
        ContentRating.ADULT -> listOf("erotica", "pornographic")
    }

    private fun ContentType.toApiType(): String? = when (this) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        ContentType.COMICS -> "cartoon"
        ContentType.IMAGE_SET -> "imageset"
        ContentType.OTHER -> "other"
        else -> null
    }

    private fun Demographic.toApiDemo(): String? = when (this) {
        Demographic.SHOUNEN -> "shounen"
        Demographic.SHOUJO -> "shoujo"
        Demographic.SEINEN -> "seinen"
        Demographic.JOSEI -> "josei"
        Demographic.KODOMO -> "kodomo"
        else -> null
    }

    private fun String?.toMangaState(): MangaState? = when {
        this == null -> null
        contains("pending") -> null
        contains("ongoing") || contains("releasing") -> MangaState.ONGOING
        contains("cancelled") -> MangaState.ABANDONED
        contains("hiatus") -> MangaState.PAUSED
        contains("completed") -> MangaState.FINISHED
        contains("upcoming") -> MangaState.UPCOMING
        else -> null
    }

    private fun String.toTagCase(): String = replace("_", " ")
        .split(" ").joinToString(" ") { w ->
            w.lowercase(Locale.ROOT)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

    private fun cleanTitle(title: String): String = title
        .replace(TITLE_REGEX, "")
        .trim()

    private fun String.toMarkdownUrls(): String =
        replace(URL_REGEX) { "[${it.value}](${it.value})" }

    private fun langDisplayName(code: String): String =
        XCOMIC_LANGS.firstOrNull { it.second == code }?.first ?: code.uppercase()

    private val XCOMIC_LANGS = listOf(
        "English" to "en",
        "French" to "fr",
        "Portuguese" to "pt",
        "Portuguese (BR)" to "pt_br",
        "Spanish" to "es",
        "Spanish (LA)" to "es_419",
        "Korean" to "ko",
        "Japanese" to "ja",
        "Indonesian" to "id",
        "Chinese" to "zh",
        "Chinese (Traditional)" to "zh_hk",
        "Russian" to "ru",
        "German" to "de",
        "Italian" to "it",
        "Arabic" to "ar",
        "Thai" to "th",
        "Vietnamese" to "vi",
        "Turkish" to "tr",
        "Polish" to "pl",
        "Ukrainian" to "uk",
        "Filipino" to "fil",
        "Abkhazian" to "ab",
        "Afrikaans" to "af",
        "Albanian" to "sq",
        "Amharic" to "am",
        "Armenian" to "hy",
        "Azerbaijani" to "az",
        "Belarusian" to "be",
        "Bengali" to "bn",
        "Bosnian" to "bs",
        "Bulgarian" to "bg",
        "Burmese" to "my",
        "Cambodian" to "km",
        "Catalan" to "ca",
        "Cebuano" to "ceb",
        "Croatian" to "hr",
        "Czech" to "cs",
        "Chuvash" to "cv",
        "Danish" to "da",
        "Dutch" to "nl",
        "Estonian" to "et",
        "Esperanto" to "eo",
        "Basque" to "eu",
        "Faroese" to "fo",
        "Finnish" to "fi",
        "Georgian" to "ka",
        "Greek" to "el",
        "Guarani" to "gn",
        "Gujarati" to "gu",
        "Haitian Creole" to "ht",
        "Hausa" to "ha",
        "Hebrew" to "he",
        "Hindi" to "hi",
        "Hungarian" to "hu",
        "Icelandic" to "is",
        "Igbo" to "ig",
        "Irish" to "ga",
        "Galician" to "gl",
        "Javanese" to "jv",
        "Kannada" to "kn",
        "Kazakh" to "kk",
        "Kurdish" to "ku",
        "Kyrgyz" to "ky",
        "Latin" to "la",
        "Laothian" to "lo",
        "Latvian" to "lv",
        "Lithuanian" to "lt",
        "Luxembourgish" to "lb",
        "Macedonian" to "mk",
        "Malagasy" to "mg",
        "Malay" to "ms",
        "Malayalam" to "ml",
        "Maltese" to "mt",
        "Maori" to "mi",
        "Marathi" to "mr",
        "Moldavian" to "mo",
        "Mongolian" to "mn",
        "Nepali" to "ne",
        "Norwegian" to "no",
        "Nyanja" to "ny",
        "Pashto" to "ps",
        "Persian" to "fa",
        "Romanian" to "ro",
        "Romansh" to "rm",
        "Samoan" to "sm",
        "Serbian" to "sr",
        "Serbo-Croatian" to "sh",
        "Siswati" to "ss",
        "Sesotho" to "st",
        "Shona" to "sn",
        "Sindhi" to "sd",
        "Sinhalese" to "si",
        "Slovak" to "sk",
        "Slovenian" to "sl",
        "Somali" to "so",
        "Swahili" to "sw",
        "Swedish" to "sv",
        "Tajik" to "tg",
        "Tamil" to "ta",
        "Telugu" to "te",
        "Tigrinya" to "ti",
        "Tonga" to "to",
        "Turkmen" to "tk",
        "Urdu" to "ur",
        "Uzbek" to "uz",
        "Yoruba" to "yo",
        "Zulu" to "zu",
        "Other" to "_t",
    )

    private fun Locale.toXComicLangCode(): String? {
        if (this == Locale.ROOT) return null
        return when {
            language == "pt" && country.equals("BR", true) -> "pt_br"
            language == "es" && country == "419" -> "es_419"
            language == "zh" && (country.equals("HK", true) || country.equals("TW", true)) -> "zh_hk"
            language == "other" -> "_t"
            language.isBlank() -> null
            else -> language
        }
    }
}

private fun xcomicCodeToLocale(code: String): Locale = when (code) {
    "pt_br" -> Locale("pt", "BR")
    "es_419" -> Locale("es", "419")
    "zh_hk" -> Locale("zh", "HK")
    "_t" -> Locale("other")
    else -> Locale(code)
}

private val GENRE_TAGS: List<Pair<String, String>> by lazy {
    listOf(
        "1-Koma" to "1_koma",
        "2-Koma" to "2_koma",
        "3-Koma" to "3_koma",
        "4-Koma" to "4_koma",
        "Action" to "action",
        "Adaptation" to "adaptation",
        "Adult" to "adult",
        "Adventure" to "adventure",
        "Age Gap" to "age_gap",
        "Aliens" to "aliens",
        "Animals" to "animals",
        "Anthology" to "anthology",
        "Art-by-AI" to "art_by_ai",
        "Artbook" to "artbook",
        "Award Winning" to "award_winning",
        "Bara" to "bara",
        "Beasts" to "beasts",
        "Blackmail" to "blackmail",
        "Bloody" to "bloody",
        "Bodyswap" to "bodyswap",
        "Boys" to "boys",
        "Boys Love" to "boys_love",
        "Brocon Siscon" to "brocon_siscon",
        "Cars" to "cars",
        "Cheating/Infidelity" to "cheating_infidelity",
        "Childhood Friends" to "childhood_friends",
        "College life" to "college_life",
        "Comedy" to "comedy",
        "Comic" to "comic",
        "Contest winning" to "contest_winning",
        "Cooking" to "cooking",
        "Crime" to "crime",
        "Crossdressing" to "crossdressing",
        "Cultivation" to "cultivation",
        "Death Game" to "death_game",
        "Degeneratemc" to "degeneratemc",
        "Delinquents" to "delinquents",
        "Dementia" to "dementia",
        "Demons" to "demons",
        "Doujinshi" to "doujinshi",
        "Drama" to "drama",
        "Dungeons" to "dungeons",
        "Ecchi" to "ecchi",
        "Emperor's Daughter" to "emperors_daughter",
        "Fan Colored" to "fan_colored",
        "Fanbook" to "fanbook",
        "Fanwork" to "fanwork",
        "Fantasy" to "fantasy",
        "Female-protagonists" to "female_protagonists",
        "Fetish" to "fetish",
        "Full Color" to "full_color",
        "Futa" to "futa",
        "Game" to "game",
        "Genderswap" to "genderswap",
        "Ghosts" to "ghosts",
        "Girls" to "girls",
        "Girls Love" to "girls_love",
        "Gore" to "gore",
        "Guidebook" to "guidebook",
        "Gyaru" to "gyaru",
        "Harem" to "harem",
        "Harlequin" to "harlequin",
        "Hentai" to "hentai",
        "Historical" to "historical",
        "Horror" to "horror",
        "Illustbook" to "illustbook",
        "Illustration Book" to "illustration_book",
        "Incest" to "incest",
        "Isekai" to "isekai",
        "Japanese Novel" to "japanese_novel",
        "Kids" to "kids",
        "Light Novel" to "light_novel",
        "Loli" to "loli",
        "Long Strip" to "long_strip",
        "Longstrip" to "longstrip",
        "Mafia" to "mafia",
        "Magic" to "magic",
        "Magical Girls" to "magical_girls",
        "Mahjong" to "mahjong",
        "Male-protagonists" to "male_protagonists",
        "Martial Arts" to "martial_arts",
        "Master-Servant" to "master_servant",
        "Mature" to "mature",
        "Mecha" to "mecha",
        "Medical" to "medical",
        "Milf" to "milf",
        "Military" to "military",
        "Monster Girls" to "monster_girls",
        "Monsters" to "monsters",
        "Music" to "music",
        "Mystery" to "mystery",
        "Netorare/NTR" to "netorare_ntr",
        "Netori" to "netori",
        "Ninja" to "ninja",
        "Novels" to "novels",
        "Office Workers" to "office_workers",
        "Official Colored" to "official_colored",
        "Omegaverse" to "omegaverse",
        "Oneshot" to "oneshot",
        "Original Doujinshi" to "original_doujinshi",
        "Parody" to "parody",
        "Partially Colored" to "partially_colored",
        "Partially Colored Webtoon" to "partially_colored_webtoon",
        "Philosophical" to "philosophical",
        "Police" to "police",
        "Post-Apocalyptic" to "post_apocalyptic",
        "Psychological" to "psychological",
        "Regression" to "regression",
        "Reincarnation" to "reincarnation",
        "Revenge" to "revenge",
        "Reverse Harem" to "reverse_harem",
        "Reverse Isekai" to "reverse_isekai",
        "Romance" to "romance",
        "Royal family" to "royal_family",
        "Royalty" to "royalty",
        "Samurai" to "samurai",
        "School Life" to "school_life",
        "Sci-Fi" to "sci_fi",
        "Sexual Violence" to "sexual_violence",
        "Shota" to "shota",
        "Shoujo ai" to "shoujo_ai",
        "Shounen ai" to "shounen_ai",
        "Showbiz" to "showbiz",
        "Slice of Life" to "slice_of_life",
        "SM/BDSM/SUB-DOM" to "sm_bdsm_sub_dom",
        "Smut" to "smut",
        "Space" to "space",
        "Sports" to "sports",
        "Spy" to "spy",
        "Step-family" to "step_family",
        "Story-by-AI" to "story_by_ai",
        "Super Power" to "super_power",
        "Superhero" to "superhero",
        "Supernatural" to "supernatural",
        "Survival" to "survival",
        "Suspense" to "suspense",
        "Teacher-Student" to "teacher_student",
        "Thriller" to "thriller",
        "Time Travel" to "time_travel",
        "Tower Climbing" to "tower_climbing",
        "Traditional Games" to "traditional_games",
        "Tragedy" to "tragedy",
        "Transmigration" to "transmigration",
        "Vampires" to "vampires",
        "Video Games" to "video_games",
        "Villainess" to "villainess",
        "Violence" to "violence",
        "Virtual Reality" to "virtual_reality",
        "Web Comic" to "web_comic",
        "Web Novel" to "web_novel",
        "Webtoon" to "webtoon",
        "Wuxia" to "wuxia",
        "Xianxia" to "xianxia",
        "Xuanhuan" to "xuanhuan",
        "Yakuzas" to "yakuzas",
        "Youkai" to "youkai",
        "Zombies" to "zombies",
    )
}
