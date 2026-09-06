package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.MangaState
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.parseJson
import tsuki.util.parseHtml

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("COSMICSCANSID", "CosmicScans", "id")
internal class CosmicScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COSMICSCANSID, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("02.cosmicscans.to")
    private val apiUrl = "https://cdncid.csmcscns.id/v1/manga"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.NEWEST,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
    )

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Origin", "https://$domain")
        .set("Referer", "https://$domain/")
        .build()

    private val cursorCache = mutableMapOf<String, String>()
    private val lastPage = mutableMapOf<String, Int>()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = GENRES.map { MangaTag(it, it.lowercase(Locale.ROOT), source) }.toSet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val endpoint = if (query.isNotBlank()) "search" else "filter"
        val key = buildSearchKey(endpoint, query, order, filter)
        lastPage[key] = page

        val url = "$apiUrl/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("limit", pageSize.toString())
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("q", query)
                }
                if (endpoint == "filter") {
                    addQueryParameter("order_by", mapSortOrder(order))
                }
                if (endpoint != "search") {
                    if (page > 1) {
                        cursorCache["$key:$page"]?.takeIf { it.isNotBlank() }?.let {
                            addQueryParameter("after", it)
                        }
                    }
                }
            }
            .build()

        val response = try {
            webClient.httpGet(url, getRequestHeaders())
        } catch (e: Exception) {
            throw ParseException("Failed to load list page $page (URL: $url): ${e.message}", url.toString(), e)
        }

        val responseBody = try {
            response.parseJson()
        } catch (e: Exception) {
            throw ParseException("Invalid JSON from list API (URL: $url): ${e.message}", url.toString(), e)
        }

        val result = try {
            parseMangaListResponse(responseBody, key)
        } catch (e: Exception) {
            throw ParseException("Failed to parse manga list (URL: $url): ${e.message}", url.toString(), e)
        }

        return applyClientFilters(result, filter)
    }

    private fun mapSortOrder(order: SortOrder): String = when (order) {
        SortOrder.UPDATED -> "update"
        SortOrder.POPULARITY -> "popular"
        SortOrder.ALPHABETICAL -> "az"
        SortOrder.ALPHABETICAL_DESC -> "za"
        SortOrder.NEWEST -> "added"
        else -> "update"
    }

    private fun buildSearchKey(endpoint: String, query: String, order: SortOrder, filter: MangaListFilter):
            String = listOf(
        endpoint, query, mapSortOrder(order),
        filter.states.joinToString(",") { it.name },
        filter.types.joinToString(",") { it.name },
        filter.tags.joinToString(",") { it.key },
    ).joinToString(":")

    private fun parseMangaListResponse(json: JSONObject, key: String): List<Manga> {
        if (!json.optBoolean("success", true)) {
            val message = json.optString("message", "Unknown API error")
            throw ParseException("CosmicScans API error: $message", "https://$domain")
        }

        val data = json.optJSONArray("data")
            ?: throw ParseException("Missing 'data' array in list response", "https://$domain")

        val cursor = json.optJSONObject("cursor")
        val page = lastPage[key] ?: 1

        if (cursor != null) {
            val nextCursor = cursor.optString("nextCursor", "")
            if (nextCursor.isNotBlank()) {
                cursorCache["$key:${page + 1}"] = nextCursor
            }
        }

        val mangas = mutableListOf<Manga>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            mangas += parseMangaFromListJson(obj)
        }
        return mangas
    }

    private fun parseMangaFromListJson(obj: JSONObject): Manga {
        val title = obj.optString("title", "").ifBlank {
            throw ParseException("Missing 'title' in manga entry", "https://$domain")
        }
        val slug = obj.optString("slug", "").ifBlank {
            throw ParseException("Missing 'slug' in manga entry: $title", "https://$domain")
        }
        val cover = obj.optString("cover", "")
        val status = obj.optString("status", "")
        val genresArray = obj.optJSONArray("genres") ?: JSONArray()
        val genres = (0 until genresArray.length()).map { genresArray.getString(it) }

        val state = when (status.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed", "complete" -> MangaState.FINISHED
            "hiatus", "on hiatus", "on-hold", "on hold" -> MangaState.PAUSED
            else -> null
        }

        return Manga(
            id = generateUid("/series/$slug"),
            url = "/series/$slug",
            publicUrl = "https://$domain/series/$slug/",
            title = title,
            coverUrl = cover,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            tags = genres.map { MangaTag(it, it.lowercase(Locale.ROOT), source) }.toSet(),
            authors = emptySet(),
            state = state,
            source = source,
        )
    }

    private fun applyClientFilters(mangas: List<Manga>, filter: MangaListFilter): List<Manga> {
        return mangas.filter { manga ->
            if (filter.states.isNotEmpty() && manga.state !in filter.states) {
                return@filter false
            }

            if (filter.types.isNotEmpty()) {
                val mangaType = manga.description
                    ?.substringAfter("Type: ", "")
                    ?.lowercase(Locale.ROOT)
                val matchesType = filter.types.any { type ->
                    when (type) {
                        ContentType.MANGA -> mangaType == "manga"
                        ContentType.MANHWA -> mangaType == "manhwa"
                        ContentType.MANHUA -> mangaType == "manhua"
                        else -> false
                    }
                }
                if (!matchesType) return@filter false
            }

            if (filter.tags.isNotEmpty()) {
                val mangaTagKeys = manga.tags.map { it.key }.toSet()
                val allMatch = filter.tags.all { tag -> tag.key in mangaTagKeys }
                if (!allMatch) return@filter false
            }

            true
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        suspend fun fetchDetailFromSlug(slug: String): JSONObject? {
            val apiUrl = "$apiUrl/mangaDetail/$slug"
            return try {
                val response = webClient.httpGet(apiUrl, getRequestHeaders())
                val json = response.parseJson()
                if (!json.optBoolean("success", true)) {
                    val message = json.optString("message", "Unknown error")
                    throw ParseException("Details API error for slug '$slug': $message", apiUrl)
                }
                json.optJSONObject("data")
            } catch (e: ParseException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

        val initialSlug = manga.url
            .removePrefix("/series/")
            .removeSuffix("/")
            .ifBlank { throw ParseException("Manga URL is blank: ${manga.url}", manga.url) }

        var data = fetchDetailFromSlug(initialSlug)

        if (data == null) {
            val pageUrl = manga.publicUrl
            if (pageUrl.isBlank()) {
                throw ParseException("Public URL missing for manga: ${manga.title}", manga.url)
            }

            val doc = try {
                webClient.httpGet(pageUrl).parseHtml()
            } catch (e: Exception) {
                throw ParseException("Failed to load manga page for slug resolution: $pageUrl", pageUrl, e)
            }

            val canonicalUrl = doc.selectFirst("link[rel=canonical]")?.attr("href")
                ?: doc.selectFirst("meta[property=og:url]")?.attr("content")
                ?: pageUrl

            val canonicalSlug = canonicalUrl
                .substringAfterLast('/')
                .substringBefore('?')
                .trim()
                .ifBlank { throw ParseException("Could not extract canonical slug from page: $pageUrl", pageUrl) }

            if (canonicalSlug != initialSlug) {
                data = fetchDetailFromSlug(canonicalSlug)
            }
        }

        if (data == null) {
            throw ParseException(
                "Details not found for manga '${manga.title}' (tried slugs: $initialSlug)",
                manga.publicUrl,
            )
        }

        val title = data.optString("title", manga.title).ifBlank { manga.title }
        val cover = data.optString("cover", manga.coverUrl)
        val sinopsis = data.optString("sinopsis", "").takeIf { it.isNotBlank() && it != "null" }
        val status = data.optString("status", "")
        val author = data.optString("author", "").takeIf { it.isNotBlank() && it != "null" }
        val genresArray = data.optJSONArray("genre") ?: data.optJSONArray("genres") ?: JSONArray()
        val genres = (0 until genresArray.length()).map { genresArray.getString(it) }

        val state = when (status.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed", "complete" -> MangaState.FINISHED
            "hiatus", "on hiatus", "on-hold", "on hold" -> MangaState.PAUSED
            else -> null
        }

        val rating = data.optString("rating", "").toFloatOrNull()
        val normalizedRating = if (rating != null && rating > 0f) rating / 2f else RATING_UNKNOWN

        val chaptersJson = data.optJSONArray("chapters") ?: JSONArray()
        val chapters = parseChapters(chaptersJson)

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = sinopsis,
            authors = setOfNotNull(author),
            tags = genres.map { MangaTag(it, it.lowercase(Locale.ROOT), source) }.toSet(),
            state = state,
            rating = normalizedRating,
            chapters = chapters,
            contentRating = null,
        )
    }

    private fun parseChapters(chaptersJson: JSONArray): List<MangaChapter> {
        if (chaptersJson.length() == 0) {
            return emptyList()
        }

        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until chaptersJson.length()) {
            val obj = chaptersJson.getJSONObject(i)
            val slug = obj.optString("slug", "").ifBlank { continue }
            val chapterNumRaw = obj.optString("chapterNum", "").ifBlank { continue }
            val time = obj.optString("time", "")

            val number = Regex("""^(\d+(?:\.\d+)?)""")
                .find(chapterNumRaw)
                ?.groupValues
                ?.get(1)
                ?.toFloatOrNull()
                ?: continue

            val uploadDate = runCatching { dateFormat.parse(time)?.time }.getOrNull() ?: 0L

            chapters += MangaChapter(
                id = generateUid("/chapter/$slug"),
                title = "Chapter $chapterNumRaw".trim(),
                url = "/chapter/$slug",
                number = number,
                volume = 0,
                scanlator = null,
                uploadDate = uploadDate,
                branch = null,
                source = source,
            )
        }

        return chapters.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterSlug = chapter.url.substringAfterLast('/')
        if (chapterSlug.isBlank()) {
            throw ParseException("Chapter URL missing slug: ${chapter.url}", chapter.url)
        }

        val url = "$apiUrl/readingPage/$chapterSlug"
        val response = try {
            webClient.httpGet(url, getRequestHeaders())
        } catch (e: Exception) {
            throw ParseException("Failed to load pages (URL: $url): ${e.message}", url, e)
        }

        val json = try {
            response.parseJson()
        } catch (e: Exception) {
            throw ParseException("Invalid JSON from reading page API (URL: $url): ${e.message}", url, e)
        }

        if (!json.optBoolean("success", true)) {
            val message = json.optString("message", "Unknown error")
            throw ParseException("Reading page API error: $message", url)
        }

        val data = json.optJSONObject("data")
            ?: throw ParseException("Missing 'data' object in reading page response", url)

        val chaptersArray = data.optJSONArray("chapters")
            ?: throw ParseException("Missing 'chapters' array in reading page data", url)

        if (chaptersArray.length() == 0) {
            throw ParseException("No pages found in reading page data", url)
        }

        val pages = mutableListOf<MangaPage>()
        for (i in 0 until chaptersArray.length()) {
            val html = chaptersArray.getString(i)
            val img = Jsoup.parse(html).selectFirst("img")
                ?: throw ParseException("Page $i does not contain an <img> tag", url)
            val imageUrl = img.attr("src").takeIf { it.isNotBlank() }
                ?: throw ParseException("Page $i has empty src attribute", url)
            pages += MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }

        return pages
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    companion object {
        private val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Martial Arts", "Romance",
            "School Life", "Shounen", "Supernatural", "System", "Thriller", "Murim",
        )
    }
}
