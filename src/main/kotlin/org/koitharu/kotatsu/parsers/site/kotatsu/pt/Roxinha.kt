package org.koitharu.kotatsu.parsers.site.kotatsu.pt

import okhttp3.Headers
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

@MangaSourceParser("ROXINHA", "Roxinha", "pt")
internal class Roxinha(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ROXINHA, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("roxinha.online")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = false,

    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .build()

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.COMICS // Webtoon
        ),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val offset = (page - 1) * pageSize
        val url = "https://$domain/api/manga/search/advanced".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", pageSize.toString())
            addQueryParameter("offset", offset.toString())
            addQueryParameter("mode", "default")

            if (!filter.query.isNullOrBlank()) {
                addQueryParameter("q", filter.query!!)
            }

            val (sortField, sortOrder) = when (order) {
                SortOrder.ALPHABETICAL -> "title" to "ASC"
                SortOrder.UPDATED -> "updatedAt" to "DESC"
                SortOrder.POPULARITY -> "views" to "DESC"
                SortOrder.RATING -> "avgRating" to "DESC"
                else -> "title" to "ASC"
            }
            addQueryParameter("sort", sortField)
            addQueryParameter("order", sortOrder)

            if (filter.states.isNotEmpty()) {
                val status = when (filter.states.first()) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    else -> null
                }
                status?.let { addQueryParameter("status", it) }
            }

            if (filter.types.isNotEmpty()) {
                val type = when (filter.types.first()) {
                    ContentType.MANGA -> "Manga"
                    ContentType.MANHWA -> "Manhwa"
                    ContentType.MANHUA -> "Manhua"
                    ContentType.COMICS -> "Webtoon"
                    else -> null
                }
                type?.let { addQueryParameter("type", it) }
            }
        }.build()

        val json = webClient.httpGet(url).parseJson()
        val mangasArray = json.optJSONArray("mangas") ?: return emptyList()

        val list = mutableListOf<Manga>()
        for (i in 0 until mangasArray.length()) {
            val item = mangasArray.getJSONObject(i)
            list.add(item.toManga())
        }
        return list
    }

    private fun JSONObject.toManga(): Manga {
        val id = getInt("id")
        return Manga(
            id = generateUid(id.toString()),
            url = id.toString(),
            publicUrl = "https://$domain/manga/$id",
            title = getString("title"),
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = optString("cover", null)?.let { if (it.startsWith("http")) it else "https://$domain$it" } ?: "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val id = manga.url
        val json = webClient.httpGet("https://$domain/api/manga/$id").parseJson()

        val title = json.getString("title")
        val cover = json.optString("cover", null)?.let { if (it.startsWith("http")) it else "https://$domain$it" }
        val author = json.optString("author", null).nullIfEmpty()
        val description = json.optString("description", null).nullIfEmpty()
        val status = json.optString("status", null)
        val genresRaw = json.optString("genres", null)
        val tags = genresRaw?.split(",")?.mapNotNull { it -> it.trim().takeIf { it.isNotEmpty() } }?.map {
            MangaTag(it, it.lowercase().replace(" ", "_"), source)
        }.orEmpty().toSet()

        val state = when (status) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            else -> null
        }

        val chaptersArray = json.optJSONArray("chapters")
        val chapters = chaptersArray?.let { arr ->
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toChapter() }
        } ?: emptyList()

        return manga.copy(
            title = title,
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            authors = setOfNotNull(author),
            description = description,
            state = state,
            tags = tags,
            chapters = chapters,
        )
    }

    private fun JSONObject.toChapter(): MangaChapter {
        val id = getInt("id")
        val number = optDouble("chapterNumber", Double.NaN).let { if (it.isNaN()) -1f else it.toFloat() }
        val rawTitle = optString("title", null)
        val createdAt = optString("createdAt", null)

        val title = if (!rawTitle.isNullOrBlank() && rawTitle.toDoubleOrNull() == null) {
            rawTitle
        } else {
            "Capítulo ${number.toString().removeSuffix(".0")}"
        }

        return MangaChapter(
            id = generateUid(id.toString()),
            title = title,
            number = number,
            volume = 0,
            url = id.toString(),  // chapter ID
            uploadDate = createdAt?.let { dateFormat.parseSafe(it) } ?: 0L,
            source = source,
            scanlator = null,
            branch = null,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url
        // access ticket
        val ticketJson = webClient.httpGet("https://$domain/api/manga/chapter/$chapterId/access").parseJson()
        val ticket = ticketJson.getString("ticket")

        // fetch chapter pages with ticket header
        val pagesHeaders = getRequestHeaders().newBuilder()
            .add("x-chapter-access", ticket)
            .build()
        val pagesJson = webClient.httpGet("https://$domain/api/manga/chapter/$chapterId", pagesHeaders).parseJson()
        val pagesArray = pagesJson.optJSONArray("pages") ?: return emptyList()

        return (0 until pagesArray.length()).map { i ->
            val url = pagesArray.getString(i)
            val fullUrl = if (url.startsWith("http")) url else "https://$domain$url"
            MangaPage(
                id = generateUid(fullUrl),
                url = fullUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private fun String.nullIfEmpty() = ifBlank { null }
    private fun SimpleDateFormat.parseSafe(date: String): Long? = runCatching { parse(date)?.time }.getOrNull()
}
