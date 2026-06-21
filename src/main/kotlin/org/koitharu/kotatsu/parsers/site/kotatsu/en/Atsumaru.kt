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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@MangaSourceParser("ATSUMARU", "Atsumaru", "en")
internal class Atsumaru(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ATSUMARU, pageSize = 40) {

    override val configKeyDomain = ConfigKey.Domain("atsu.moe")
    private val apiUrl = "https://$domain/api/"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.RATING,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = setOf(
            MangaTag(
                key = "Manga",
                title = "Manga",
                source = source
            ),
            MangaTag(
                key = "Manwha",
                title = "Manhwa",
                source = source
            ),
            MangaTag(
                key = "Manhua",
                title = "Manhua",
                source = source
            ),
            MangaTag(
                key = "OEL",
                title = "OEL",
                source = source
            )
        ),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED
        )
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {

        val sort = when (order) {
            SortOrder.POPULARITY -> "views:desc"
            SortOrder.RATING -> "mbRating:desc"
            else -> "views:desc"
        }

        val url = "https://$domain/collections/manga/documents/search"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter(
                "q",
                filter.query ?: "*"
            )
            .addQueryParameter(
                "query_by",
                "title,englishTitle,otherNames,authors"
            )
            .addQueryParameter(
                "query_by_weights",
                "4,3,2,1"
            )
            .addQueryParameter(
                "num_typos",
                "4,3,2,1"
            )
            .addQueryParameter(
                "include_fields",
                "id,title,englishTitle,poster,posterSmall,posterMedium,type,isAdult,status,year,mbRating,populairty"
            )
            .addQueryParameter(
                "page",
                page.toString()
            )
            .addQueryParameter(
                "per_page",
                pageSize.toString()
            )
            .addQueryParameter(
                "sort_by",
                sort
            )
            .addQueryParameter(
                "filter_by",
                buildFilter(filter)
            )
            .build()


        val json = webClient
            .httpGet(url.toString())
            .parseJson()


        val hits = json.optJSONArray("hits")
            ?: return emptyList()


        return (0 until hits.length()).map {
            val document = hits
                .getJSONObject(it)
                .getJSONObject("document")

            parseManga(document)
        }
    }

    private fun buildFilter(filter: MangaListFilter): String {

        val filters = mutableListOf<String>()

        filters += "isAdult:=false"
        filters += "views:>0"
        filters += "hidden:!=true"


        // Manga type filter
        val types = filter.tags.map {
            "`${it.key}`"
        }

        if (types.isNotEmpty()) {
            filters += "type:=[${types.joinToString(",")}]"
        }


        // Status filter
        val statuses = filter.states.mapNotNull {
            when (it) {
                MangaState.ONGOING -> "`Ongoing`"
                MangaState.FINISHED -> "`Completed`"
                MangaState.PAUSED -> "`Hiatus`"
                MangaState.ABANDONED -> "`Canceled`"
                else -> null
            }
        }

        if (statuses.isNotEmpty()) {
            filters += "status:=[${statuses.joinToString(",")}]"
        }


        return filters.joinToString(" && ")
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
            tags = emptySet(),
            authors = authors,
            state = state,
            chapters = chapters
        )
    }


    private suspend fun fetchAllChapters(
        mangaId: String,
        mangaPage: JSONObject?
    ): List<MangaChapter> = coroutineScope {

        val scanlators = mangaPage.parseScanlators()

        // First request is needed to know total pages
        val firstUrl =
            "${apiUrl}manga/chapters?id=$mangaId&filter=all&sort=desc&page=0"

        val firstJson = webClient
            .httpGet(firstUrl)
            .parseJson()

        val totalPages = firstJson.optInt("pages", 1)

        val semaphore = Semaphore(4)

        val pages = buildList {

            // Add already fetched page 0
            add(firstJson)

            // Fetch remaining pages in parallel
            addAll(
                (1 until totalPages).map { page ->

                    async {
                        semaphore.withPermit {

                            val url =
                                "${apiUrl}manga/chapters?id=$mangaId&filter=all&sort=desc&page=$page"

                            webClient
                                .httpGet(url)
                                .parseJson()
                        }
                    }

                }.awaitAll()
            )
        }


        pages.flatMap { json ->

            val array = json.optJSONArray("chapters")
                ?: return@flatMap emptyList()

            (0 until array.length())
                .mapNotNull { i ->
                    array.optJSONObject(i)
                }
                .map {
                    parseChapter(
                        it,
                        mangaId,
                        scanlators
                    )
                }
        }
            .sortedBy { it.number }
            .mapChapterBranches()
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

        val counters = HashMap<Float, Int>()

        return map { chapter ->

            val count = counters.getOrDefault(chapter.number, 0) + 1
            counters[chapter.number] = count

            val branch =
                if (count == 1) {
                    "Group"
                } else {
                    "Group $count"
                }

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