package org.koitharu.kotatsu.parsers.site.kotatsu.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ATSUMARUIDK", "Atsumaru", "en")
internal class ATSUMARUIDK(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ATSUMARUIDK, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("atsu.moe")
    private val apiUrl = "https://$domain/api/"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val endpoint = when (order) {
            SortOrder.POPULARITY -> "infinite/trending"
            SortOrder.UPDATED -> "infinite/recentlyUpdated"
            else -> "infinite/trending"
        }

        val url = "${apiUrl}$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("types", "Manga,Manwha,Manhua,OEL")
            .build()

        val query = filter.query
        if (!query.isNullOrEmpty()) {
            return getSearchPage(page, query)
        }

        val json = webClient.httpGet(url.toString()).parseJson()
        val items = json.getJSONArray("items")

        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            parseManga(item)
        }
    }

    private suspend fun getSearchPage(page: Int, query: String): List<Manga> {
        val url = "https://$domain/collections/manga/documents/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("query_by", "title,englishTitle,otherNames")
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("query_by_weights", "3,2,1")
            .addQueryParameter("include_fields", "id,title,englishTitle,poster")
            .addQueryParameter("num_typos", "4,3,2")
            .build()

        val json = webClient.httpGet(url.toString()).parseJson()
        val hits = json.getJSONArray("hits")

        return (0 until hits.length()).map { i ->
            val hit = hits.getJSONObject(i)
            val document = hit.getJSONObject("document")
            parseManga(document)
        }
    }

    private fun parseManga(json: JSONObject): Manga {
        val id = json.getString("id")
        val title = json.optString("title").ifEmpty {
            json.optString("englishTitle", "Unknown")
        }

        // List results have "image", search results have "poster"
        val image = json.optString("image")
        val poster = json.optString("poster")
        val imagePath = image.ifEmpty { poster }

        val coverUrl = if (imagePath.isNotEmpty()) {
            when {
                imagePath.startsWith("http") -> imagePath
                imagePath.startsWith("/static/") -> "https://$domain$imagePath"
                imagePath.startsWith("/") -> "https://$domain$imagePath"
                else -> "https://$domain/static/$imagePath"
            }
        } else null

        return Manga(
            id = generateUid(id),
            title = title,
            altTitles = emptySet(),
            url = "/manga/$id",
            publicUrl = "https://$domain/manga/$id",
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE,
            coverUrl = coverUrl,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.url.substringAfterLast("/")
        val json = webClient.httpGet("${apiUrl}manga/page?id=$mangaId").parseJson()
        val mangaPage = if (json.has("mangaPage") && !json.isNull("mangaPage")) {
            json.getJSONObject("mangaPage")
        } else {
            null
        }

        val title = mangaPage?.optString("title")?.ifEmpty {
            mangaPage.optString("englishTitle", manga.title)
        } ?: manga.title

        val description = mangaPage?.optString("synopsis") ?: manga.description

        val posterObj = mangaPage?.optJSONObject("poster")
        val posterImage = posterObj?.optString("image")
        val coverUrl = if (!posterImage.isNullOrEmpty()) {
            "https://$domain/static/$posterImage"
        } else manga.coverUrl

        val tagsArray = mangaPage?.optJSONArray("tags")
        val tags = if (tagsArray != null) {
            (0 until tagsArray.length()).mapNotNull { i ->
                val tag = tagsArray.optJSONObject(i) ?: return@mapNotNull null
                MangaTag(
                    key = tag.optString("id"),
                    title = tag.optString("name"),
                    source = source
                )
            }.toSet()
        } else manga.tags

        val authorsArray = mangaPage?.optJSONArray("authors")
        val authors = if (authorsArray != null) {
            (0 until authorsArray.length()).mapNotNull { i ->
                val author = authorsArray.optJSONObject(i) ?: return@mapNotNull null
                author.optString("name").takeIf { it.isNotEmpty() }
            }.toSet()
        } else manga.authors

        val status = mangaPage?.optString("status").orEmpty()
        val state = when (status.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled" -> MangaState.ABANDONED
            else -> manga.state
        }


        val chapters = fetchAllChapters(mangaId, mangaPage)

        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            tags = tags,
            authors = authors,
            state = state,
            chapters = chapters
        )
    }



    private suspend fun fetchAllChapters(mangaId: String, mangaPage: JSONObject?): List<MangaChapter> {
        val scanlators = mangaPage.parseScanlators()

        val allChapters = mutableListOf<MangaChapter>()
        var currentPage = 0
        var totalPages = 1

        while (currentPage < totalPages) {

            val url = "${apiUrl}manga/chapters?id=$mangaId&filter=all&sort=desc&page=$currentPage"
            val json = webClient.httpGet(url).parseJson()

            val chaptersArray = json.optJSONArray("chapters") ?: break
            for (i in 0 until chaptersArray.length()) {
                val chapter = chaptersArray.optJSONObject(i) ?: continue
                allChapters.add(parseChapter(chapter, mangaId, scanlators))
            }

            totalPages = json.getInt("pages")
            currentPage++
        }

        return allChapters.reversed().mapChapterBranches()
    }

    private fun JSONObject?.parseScanlators(): Map<String, String> {
        val scanlatorsArray = this?.optJSONArray("scanlators") ?: return emptyMap()
        return buildMap {
            for (i in 0 until scanlatorsArray.length()) {
                val scanlator = scanlatorsArray.optJSONObject(i) ?: continue
                val id = scanlator.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val name = scanlator.optString("name").takeIf { it.isNotEmpty() } ?: continue
                put(id, name)
            }
        }
    }


    private fun parseChapter(
        json: JSONObject,
        mangaId: String,
        scanlators: Map<String, String>,
    ): MangaChapter {
        val chapterId = json.getString("id")
        val title = json.optString("title").takeIf { it.isNotEmpty() }
        val number = json.optString("number").toFloatOrNull()
            ?: json.optDouble("number", 0.0).toFloat()
        val scanlator = scanlators[json.optString("scanlationMangaId")]

        val uploadDate = when (val createdAt = json.opt("createdAt")) {
            is Number -> createdAt.toLong()
            is String -> {
                try {
                    dateFormat.parse(createdAt)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }
            else -> 0L
        }

        return MangaChapter(
            id = generateUid("$mangaId/$chapterId"),
            title = title,
            number = number,
            volume = 0,
            url = "$mangaId/$chapterId",
            uploadDate = uploadDate,
            source = source,
            scanlator = scanlator,
            branch = scanlator
        )
    }

    private fun List<MangaChapter>.mapChapterBranches(): List<MangaChapter> {
        val usedBranches = HashMap<String, HashSet<Pair<Int, Float>>>()

        return map { chapter ->

            val baseBranch = "Group"

            val branch = (1..Int.MAX_VALUE).first { number ->
                val candidate = "$baseBranch $number"

                val usedNumbers = usedBranches[candidate]

                usedNumbers == null || chapter.volume to chapter.number !in usedNumbers
            }.let { number ->
                "$baseBranch $number"
            }

            usedBranches.getOrPut(branch, ::HashSet)
                .add(chapter.volume to chapter.number)

            chapter.copy(
                scanlator = branch,
                branch = branch
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (mangaId, chapterId) = chapter.url.split("/")
        val url = "${apiUrl}read/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("mangaId", mangaId)
            .addQueryParameter("chapterId", chapterId)
            .build()

        val json = webClient.httpGet(url.toString()).parseJson()
        val readChapter = json.getJSONObject("readChapter")
        val pages = readChapter.getJSONArray("pages")

        return (0 until pages.length()).map { i ->
            val page = pages.getJSONObject(i)
            val imagePath = page.getString("image")
            val fullUrl = "https://$domain$imagePath"

            MangaPage(
                id = generateUid(fullUrl),
                url = fullUrl,
                preview = null,
                source = source
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        return emptyList()
    }
}
