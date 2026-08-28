package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

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
import tsuki.util.urlEncoded

import java.time.Instant
import java.util.EnumSet
import kotlinx.coroutines.coroutineScope
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

@MangaSourceParser("KAWIIMANGA", "Kawaii Manga", "ar")
internal class KawiiManga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAWIIMANGA, 20) {

    override val configKeyDomain = ConfigKey.Domain("kawaiimanga.org")

    private val apiUrl = "https://manga-api.kawaii-anime.com/api/manga/own"

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("x-app-key", "km_2026_live")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query.orEmpty()
        val url = if (query.isNotBlank()) {
            "$apiUrl?action=search&q=${query.urlEncoded()}"
        } else {
            val sort = when (order) {
                SortOrder.POPULARITY -> "views"
                else -> "latest"
            }
            "$apiUrl?action=browse&page=$page&sort=$sort"
        }
        val json = webClient.httpGet(url).parseJsonObject()
        return parseMangaList(json)
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val slug = manga.url
        val json = webClient.httpGet("$apiUrl?action=series&slug=$slug").parseJsonObject()
        val detailedManga = parseManga(json)
        val chapters = parseChapters(json.optJSONArray("chapters"), slug)
        detailedManga.copy(chapters = chapters)
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast('#')
        val json = webClient.httpGet("$apiUrl?action=pages&chapterId=$chapterId").parseJsonObject()
        val pagesArray = json.optJSONArray("pages") ?: return emptyList()
        return (0 until pagesArray.length()).map { i ->
            val url = pagesArray.getString(i)
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
    
    private fun Response.parseJsonObject(): JSONObject {
        val body = body?.string() ?: throw ParseException("Empty response body", request.url.toString())
        return JSONObject(body)
    }

    private fun parseMangaList(json: JSONObject): List<Manga> {
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            parseManga(results.getJSONObject(i))
        }
    }

    private fun parseManga(obj: JSONObject): Manga {
        val slug = obj.getString("slug")
        val title = obj.getString("title")
        val cover = obj.optString("coverUrl").takeIf { it.isNotBlank() }
        val author = obj.optString("author").takeIf { it.isNotBlank() && it != "unknown" }
        val artist = obj.optString("artist").takeIf { it.isNotBlank() && it != "unknown" }
        val description = obj.optString("description").takeIf { it.isNotBlank() }
        val type = obj.optString("type")
        val status = obj.optString("status")
        val genresArray = obj.optJSONArray("genres")

        val tags = buildSet {
            when (type) {
                "manga" -> add(MangaTag("manga", "Manga", source))
                "manhua" -> add(MangaTag("manhua", "Manhua", source))
                "manhwa" -> add(MangaTag("manhwa", "Manhwa", source))
            }
            if (genresArray != null) {
                for (i in 0 until genresArray.length()) {
                    val genre = genresArray.getString(i)
                    add(MangaTag(genre.lowercase(), genre, source))
                }
            }
        }

        val state = when (status) {
            "ongoing", "coming_soon" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "cancelled", "dropped" -> MangaState.ABANDONED
            else -> null
        }

        return Manga(
            id = generateUid(slug),
            url = slug,
            publicUrl = "https://$domain/manga/$slug",
            title = title,
            altTitles = emptySet(),
            authors = setOfNotNull(author, artist),
            coverUrl = cover,
            rating = RATING_UNKNOWN,
            tags = tags,
            state = state,
            description = description,
            contentRating = null,
            source = source,
        )
    }

    private fun parseChapters(jsonArray: JSONArray?, slug: String): List<MangaChapter> {
        if (jsonArray == null) return emptyList()
        return (0 until jsonArray.length()).map { i ->
            val obj = jsonArray.getJSONObject(i)
            val id = obj.getString("id")
            val title = obj.optString("title")
            val number = obj.getInt("number")
            val createdAt = obj.optString("createdAt")
            val chapterName = if (title.isNotBlank() && title != "$number") {
                "الفصل $number - $title"
            } else {
                "الفصل $number"
            }
            val date = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(0L)
            MangaChapter(
                id = generateUid("$slug/$number#$id"),
                url = "$slug/$number#$id",
                title = chapterName,
                number = number.toFloat(),
                volume = 0,
                uploadDate = date,
                scanlator = null,
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }
    }
}
