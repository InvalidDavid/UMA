package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNullToSet
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import okhttp3.Interceptor
import okhttp3.Response

@MangaSourceParser("MANGAK", "MangaK", "en", ContentType.MANGA)
internal class MangaK(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAK, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("mangak.io")

    private val apiUrl = "https://api.mangak.io"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful && request.url.host.matches(IMAGE_FALLBACK_REGEX)) {
            response.close()
            val newUrl = request.url.newBuilder().host("rx.rzyn.net").build()
            return chain.proceed(request.newBuilder().url(newUrl).build())
        }
        return response
    }


    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.RATING,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
//            MangaState.PAUSED,
//            MangaState.ABANDONED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
        availableDemographics = EnumSet.of(
            Demographic.SHOUJO,
            Demographic.SEINEN,
            Demographic.SHOUNEN,
            Demographic.JOSEI,
        ),
        availableContentRating = EnumSet.of(
            ContentRating.SAFE,
            ContentRating.SUGGESTIVE,
            ContentRating.ADULT,
        ),
    )

    private suspend fun fetchTags(): Set<MangaTag> {
        val json = webClient.httpGet("$apiUrl/genres").parseJson()
        return json.getJSONObject("data").getJSONArray("items").mapJSONToSet { item ->
            MangaTag(
                title = item.getString("name").toTitleCase(sourceLocale),
                key = item.getString("slug"),
                source = source,
            )
        }
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val allExcludedTags = filter.tagsExclude.map { it.key }.toMutableSet()
        val query = filter.query
        val url = buildString {
            append(apiUrl)
            append("/titles/search?page=")
            append(page)
            append("&limit=")
            append(pageSize)
            append("&sort=")
            append(
                when (order) {
                    SortOrder.UPDATED -> "latest"
                    SortOrder.NEWEST -> "newest"
                    SortOrder.POPULARITY -> "popular"
                    SortOrder.RATING -> "rating"
                    else -> "latest"
                },
            )

            if (!query.isNullOrEmpty()) {
                append("&q=")
                // api does not like commans and character limits to 60-70
                append(query.replace(",", "").urlEncoded())
            }

            if (filter.tags.isNotEmpty()) {
                append("&genres=")
                append(filter.tags.joinToString(",") { it.key })
            }

            if (allExcludedTags.isNotEmpty()) {
                append("&exclude=")
                append(allExcludedTags.joinToString(","))
            }

            filter.types.oneOrThrowIfMany()?.let {
                append("&type=")
                append(
                    when (it) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        else -> null
                    } ?: return@let
                )
            }

            filter.contentRating.oneOrThrowIfMany()?.let {
                append("&content_rating=")
                append(
                    when (it) {
                        ContentRating.SAFE -> "safe"
                        ContentRating.SUGGESTIVE -> "suggestive"
                        ContentRating.ADULT -> "pornographic"
                    }
                )
            }

            filter.demographics.oneOrThrowIfMany()?.let {
                append("&demographic=")
                append(
                    when (it) {
                        Demographic.SHOUJO -> "shoujo"
                        Demographic.SEINEN -> "seinen"
                        Demographic.SHOUNEN -> "shounen"
                        Demographic.JOSEI -> "josei"
                        else -> null
                    } ?: return@let
                )
            }

            filter.states.oneOrThrowIfMany()?.let {
                append("&status=")
                append(
                    when (it) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
//                        MangaState.PAUSED -> "hiatus"
//                        MangaState.ABANDONED -> "cancelled"
                        else -> null
                    } ?: return@let
                )
            }
        }

        val json = webClient.httpGet(url).parseJson()
        return json.getJSONObject("data").getJSONArray("items").mapJSON { item ->
            item.toManga()
        }
    }

    private fun JSONObject.toManga(): Manga {
        val relativeUrl = getString("url")
        return Manga(
            id = generateUid(getString("id")),
            title = getString("name"),
            altTitles = parseAltTitles(),
            url = getString("id"),
            publicUrl = "https://$domain$relativeUrl",
            rating = optDouble("rating", 0.0).toRating(),
            contentRating = optString("content_rating").nullIfEmpty().toContentRating(),
            coverUrl = optString("cover").nullIfEmpty(),
            tags = parseTags(),
            state = optString("status").toMangaState(),
            authors = emptySet(),
            description = optString("summary").nullIfEmpty(),
            source = source,
        )
    }


    private fun String?.toContentRating() = when (this) {
        "safe" -> ContentRating.SAFE
        "suggestive" -> ContentRating.SUGGESTIVE
        "erotica", "pornographic" -> ContentRating.ADULT
        else -> null
    }


    private fun JSONObject.parseAltTitles(): Set<String> {
        val result = mutableSetOf<String>()
        optJSONArray("alt_names")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")?.nullIfEmpty()?.let { result.add(it) }
            }
        }
        optString("alt_name").nullIfEmpty()?.let { result.add(it) }
        return result
    }


    private fun JSONObject.parseTags(): Set<MangaTag> =
        optJSONArray("genres")?.mapJSONNotNullToSet { genre ->
            val slug = genre.optString("slug").nullIfEmpty() ?: return@mapJSONNotNullToSet null
            MangaTag(genre.getString("name").toTitleCase(sourceLocale), slug, source)
        }.orEmpty()


    override suspend fun getDetails(manga: Manga): Manga {
        val json = webClient.httpGet("$apiUrl/titles/${manga.url}").parseJson()
            .getJSONObject("data").getJSONObject("title")
        val cv = json.optLong("cv")
        val authors = json.optJSONArray("authors")?.mapJSONNotNullToSet {
            it.optString("name").nullIfEmpty()
        }.orEmpty()
        val artists = json.optJSONArray("artists")?.mapJSONNotNullToSet {
            it.optString("name").nullIfEmpty()
        }.orEmpty()
        return manga.copy(
            title = json.optString("name").nullIfEmpty() ?: manga.title,
            altTitles = json.parseAltTitles().ifEmpty { manga.altTitles },
            description = json.optString("summary").nullIfEmpty() ?: manga.description,
            tags = json.parseTags().ifEmpty { manga.tags },
            authors = authors + artists,
            state = json.optString("status").toMangaState() ?: manga.state,
            rating = json.optDouble("rating", 0.0).toRating(),
            chapters = fetchChapters(manga.url, cv),
        )
    }


    private suspend fun fetchChapters(id: String, cv: Long): List<MangaChapter> {
        val json = webClient.httpGet("$apiUrl/titles/$id/chapters?cv=$cv").parseJson()
        val chapters = json.getJSONObject("data").getJSONArray("chapters").mapJSON { it }
        val total = chapters.size
        return chapters.mapIndexed { index, chapter ->
            MangaChapter(
                id = generateUid(chapter.getString("id")),
                title = chapter.optString("name").nullIfEmpty(),
                number = chapter.optDouble("chapter_number", Double.NaN).takeUnless { it.isNaN() }?.toFloat()
                    ?: (total - index).toFloat(),
                volume = 0,
                url = chapter.getString("url"),
                scanlator = null,
                uploadDate = dateFormat.parseSafe(chapter.optString("updated_at").substringBefore('.')),
                branch = null,
                source = source,
            )
        }.reversed()
    }


    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}").parseHtml()
        val raw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw ParseException("Cannot find chapter data", chapter.url)
        val images = JSONObject(raw)
            .getJSONObject("props")
            .getJSONObject("pageProps")
            .getJSONObject("initialChapter")
            .getJSONArray("images")
        return (0 until images.length()).map { i ->
            val imageUrl = images.getString(i)
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }


    private fun Double.toRating() = if (this > 0.0) (this / 5.0).toFloat() else RATING_UNKNOWN

    private fun String?.toMangaState() = when (this?.lowercase(Locale.US)) {
        "ongoing" -> MangaState.ONGOING
        "completed" -> MangaState.FINISHED
        "hiatus" -> MangaState.PAUSED
        "cancelled" -> MangaState.ABANDONED
        else -> null
    }

    companion object {
        private val IMAGE_FALLBACK_REGEX = Regex("rx\\.qvzr[a-z]\\.org")
    }
}
