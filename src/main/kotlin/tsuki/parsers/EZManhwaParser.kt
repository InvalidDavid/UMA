package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.AuthRequiredException

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.parseJson
import tsuki.util.toAbsoluteUrl
import tsuki.util.json.mapJSON
import tsuki.util.json.mapJSONToSet

import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

abstract class EZManhwaParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    api: String,
    pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize) {

    override val configKeyDomain = ConfigKey.Domain(domain)

    protected open val apiUrl = api

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.UPDATED_ASC,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_ASC,
        SortOrder.NEWEST,
        SortOrder.NEWEST_ASC,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
        isAuthorSearchSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
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
            ContentType.NOVEL,
        ),
        availableTags = emptySet(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val endpoint = if (filter.query.isNullOrBlank()) "$apiUrl/series" else "$apiUrl/series/search"
        val url = endpoint.toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", pageSize.toString())

            if (filter.query.isNullOrBlank()) {
                val sort = when (order) {
                    SortOrder.UPDATED, SortOrder.UPDATED_ASC -> "latest"
                    SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "popular"
                    SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "newest"
                    SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "alphabetical"
                    else -> "latest"
                }
                addQueryParameter("sort", sort)

                filter.states.firstOrNull()?.let {
                    addQueryParameter("status", when (it) {
                        MangaState.ONGOING -> "ONGOING"
                        MangaState.FINISHED -> "COMPLETED"
                        MangaState.PAUSED -> "HIATUS"
                        MangaState.ABANDONED -> "DROPPED"
                        else -> ""
                    })
                }
                filter.types.firstOrNull()?.let {
                    addQueryParameter("type", when (it) {
                        ContentType.MANGA -> "MANGA"
                        ContentType.MANHWA -> "MANHWA"
                        ContentType.MANHUA -> "MANHUA"
                        else -> ""
                    })
                }
            } else {
                addQueryParameter("q", filter.query)
            }
        }.build()

        val json = webClient.httpGet(url).parseJson()
        return parseSeriesList(json)
    }

    private fun parseSeriesList(json: JSONObject): List<Manga> {
        val data = json.getJSONArray("data")
        return data.mapJSON { obj ->
            obj.toManga(domain, source, isNsfwSource)
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "$apiUrl/series/${manga.url}"
        val json = webClient.httpGet(url).parseJson()
        val updatedManga = json.toManga(domain, source, isNsfwSource).copy(
            id = manga.id,
            source = source,
        )
        val chapters = fetchChapters(manga.url)
        return updatedManga.copy(chapters = chapters)
    }

    private suspend fun fetchChapters(seriesSlug: String): List<MangaChapter> {
        val allChapters = mutableListOf<MangaChapter>()
        var page = 1
        var hasMore = true

        while (hasMore) {
            val url = "$apiUrl/series/$seriesSlug/chapters?page=$page&perPage=100&sort=desc"
            val json = webClient.httpGet(url).parseJson()
            val data = json.getJSONArray("data")
            val totalPages = json.getInt("totalPages")
            val current = json.getInt("current")

            data.mapJSONToSet { chObj ->
                chObj.toSChapter(seriesSlug, dateFormat)
            }.let { allChapters.addAll(it) }

            hasMore = current < totalPages
            page++
        }
        return allChapters.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "$apiUrl/${chapter.url}"
        val json = webClient.httpGet(url).parseJson()

        if (json.optBoolean("requiresPurchase", false)) {
            throw AuthRequiredException(
                source,
                RuntimeException("This chapter requires coins. Purchase it via the website to read.")
            )
        }

        val images = json.getJSONArray("images")
        return (0 until images.length()).map { i ->
            val imgUrl = images.getJSONObject(i).getString("url")
            MangaPage(
                id = generateUid(imgUrl),
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    }

    fun JSONObject.toManga(domain: String, source: MangaSource, isNsfw: Boolean): Manga {
        val slug = getString("slug")
        val title = getString("title")
        val cover = optString("cover", "").takeIf { it.isNotBlank() && it != "null" }
        val status = optString("status", "").takeIf { it.isNotBlank() && it != "null" }
        val altTitles = optString("alternativeTitles", "").takeIf { it.isNotBlank() && it != "null" }
        val description = optString("description", "").takeIf { it.isNotBlank() && it != "null" }
        val author = optString("author", "").takeIf { it.isNotBlank() && it != "null" }
        val artist = optString("artist", "").takeIf { it.isNotBlank() && it != "null" }
        val genresArray = optJSONArray("genres")
        val genres = genresArray?.mapJSON { it.getString("name") } ?: emptyList()

        val state = when (status?.uppercase(Locale.ROOT)) {
            "ONGOING", "MASS_RELEASED" -> MangaState.ONGOING
            "COMPLETED" -> MangaState.FINISHED
            "DROPPED" -> MangaState.ABANDONED
            "HIATUS" -> MangaState.PAUSED
            else -> null
        }

        val altSet = altTitles?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && it != "null" }
            ?.toSet() ?: emptySet()
        val authors = setOfNotNull(author, artist).filter { it.isNotBlank() && it != "null" }.toSet()

        return Manga(
            id = generateUid(slug),
            url = slug,
            publicUrl = "/series/$slug".toAbsoluteUrl(domain),
            title = title,
            altTitles = altSet,
            coverUrl = cover,
            description = description,
            authors = authors,
            tags = genres.map { MangaTag(key = it, title = it, source = source) }.toSet(),
            state = state,
            rating = RATING_UNKNOWN,
            source = source,
            contentRating = if (isNsfw) ContentRating.ADULT else null,
        )
    }

    private fun JSONObject.toSChapter(seriesSlug: String, dateFormat: SimpleDateFormat): MangaChapter {
        val slug = getString("slug")
        val number = optDouble("number", -1.0).takeIf { it != -1.0 }?.toFloat() ?: -1f
        val title = optString("title", "").takeIf { it.isNotBlank() && it != "null" }
        val createdAt = optString("createdAt", "").takeIf { it.isNotBlank() && it != "null" }

        val prefix = if (optBoolean("requiresPurchase", false)) "🔒 " else ""
        val numStr = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
        val chapterName = when {
            title == null || title == numStr -> "Chapter $numStr"
            title.matches(Regex("^(?i)(chapter|ch\\.?|episode|ep\\.?)\\s*.*")) -> title
            title.startsWith("-") || title.startsWith(":") -> "Chapter $numStr $title"
            else -> "Chapter $numStr - $title"
        }

        return MangaChapter(
            id = generateUid(slug),
            url = "series/$seriesSlug/chapters/$slug",
            title = prefix + chapterName,
            number = number,
            volume = 0,
            uploadDate = dateFormat.parseSafe(createdAt),
            scanlator = null,
            branch = null,
            source = source,
        )
    }

    private fun SimpleDateFormat.parseSafe(dateStr: String?): Long {
        return dateStr?.let { runCatching { parse(it)?.time ?: 0L }.getOrDefault(0L) } ?: 0L
    }
}
