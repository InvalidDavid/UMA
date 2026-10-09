package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.UserAgents

import tsuki.model.ContentRating
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

import tsuki.util.LinkResolver
import tsuki.util.generateUid
import tsuki.util.mapChapters
import tsuki.util.nullIfEmpty
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseJson
import tsuki.util.parseSafe
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlBuilder
import tsuki.util.json.getBooleanOrDefault
import tsuki.util.json.getDoubleOrDefault
import tsuki.util.json.getFloatOrDefault
import tsuki.util.json.getIntOrDefault
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSONIndexed
import tsuki.util.json.mapJSONNotNull
import tsuki.util.json.mapJSONNotNullToSet
import tsuki.util.json.toStringSet
import tsuki.util.suspendlazy.suspendLazy

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("IKIRU", "Ikiru", "id")
internal class Ikiru(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.IKIRU, pageSize = 21) {

    override val configKeyDomain = ConfigKey.Domain("09.ikiru.wtf")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
        if (request.header("User-Agent") == null) {
            builder.header("User-Agent", config[userAgentKey])
        }
        if (request.header("Referer") == null) {
            builder.header("Referer", "https://$domain/")
        }
        return chain.proceed(builder.build())
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
            isAuthorSearchSupported = false,
        )

    private val availableTags = suspendLazy(initializer = ::fetchTags)

    private suspend fun fetchTags(): Set<MangaTag> = runCatching {
        val url = urlBuilder().apply {
            addPathSegment("api")
            addPathSegment("user")
            addPathSegment("genres")
        }.build()
        val response = webClient.httpGet(url)
        val json = response.parseJson()
        val allGenres = json.getJSONObject("data").getJSONArray("allGenres")
        allGenres.mapJSONNotNullToSet { item ->
            val id = item.getStringOrNull("id") ?: return@mapJSONNotNullToSet null
            val name = item.getStringOrNull("name") ?: id
            MangaTag(
                key = id,
                title = name,
                source = source,
            )
        }
    }.getOrDefault(emptySet())

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = availableTags.get(),
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
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = urlBuilder().apply {
            addPathSegment("api")
            addPathSegment("public")
            addPathSegment("library")
            addPathSegment("search")

            addQueryParameter("page", page.toString())

            if (!filter.query.isNullOrEmpty()) {
                addQueryParameter("query", filter.query)
            }

            for ((_, key) in filter.tags) {
                addQueryParameter("genre", key)
            }

            val contentType = filter.types.oneOrThrowIfMany()
            if (contentType != null) {
                val typeParam = when (contentType) {
                    ContentType.MANGA -> "MANGA"
                    ContentType.MANHWA -> "MANHWA"
                    ContentType.MANHUA -> "MANHUA"
                    else -> null
                }
                if (typeParam != null) {
                    addQueryParameter("type", typeParam)
                }
            }

            val state = filter.states.oneOrThrowIfMany()
            if (state != null) {
                val statusParam = when (state) {
                    MangaState.ONGOING -> "ONGOING"
                    MangaState.FINISHED -> "COMPLETED"
                    MangaState.PAUSED -> "HIATUS"
                    else -> null
                }
                if (statusParam != null) {
                    addQueryParameter("status", statusParam)
                }
            }

            val (sortBy, sortDir) = when (order) {
                SortOrder.POPULARITY -> "popular" to "desc"
                SortOrder.UPDATED -> "updated" to "desc"
                SortOrder.RATING -> "rating" to "desc"
                SortOrder.ALPHABETICAL -> "title" to "asc"
                SortOrder.ALPHABETICAL_DESC -> "title" to "desc"
                else -> "popular" to "desc"
            }
            addQueryParameter("sortBy", sortBy)
            addQueryParameter("sort", sortDir)
        }.build()

        val response = webClient.httpGet(url)
        val json = response.parseJson()
        val mangasArray = json.optJSONObject("data")?.optJSONArray("mangas") ?: return emptyList()

        return mangasArray.mapJSONNotNull { item ->
            val slug = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
            val relativeUrl = "/manga/$slug"
            val metadata = item.optJSONObject("metadata")
            val score = metadata?.getDoubleOrDefault("score", -1.0) ?: -1.0
            val rating = if (score > 0) (score / 10.0).toFloat() else RATING_UNKNOWN

            val tags = metadata?.optJSONArray("genre")?.mapJSONNotNullToSet { g ->
                val key = g.getStringOrNull("id") ?: return@mapJSONNotNullToSet null
                val name = g.getStringOrNull("name") ?: key
                MangaTag(key = key, title = name, source = source)
            }.orEmpty()

            Manga(
                id = generateUid(relativeUrl),
                title = item.getStringOrNull("title") ?: slug,
                altTitles = emptySet(),
                url = relativeUrl,
                publicUrl = relativeUrl.toAbsoluteUrl(domain),
                rating = rating,
                contentRating = null,
                coverUrl = item.getStringOrNull("featuredImage"),
                tags = tags,
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removePrefix("/manga/").substringBefore('/')
        val url = urlBuilder().apply {
            addPathSegment("api")
            addPathSegment("public")
            addPathSegment("manga")
            addPathSegment(slug)
        }.build()

        val response = webClient.httpGet(url)
        val json = response.parseJson()
        val data = json.getJSONObject("data")

        val metadata = data.optJSONObject("metadata")
        val score = metadata?.getDoubleOrDefault("score", -1.0) ?: -1.0
        val rating = if (score > 0) (score / 10.0).toFloat() else manga.rating

        val altTitles = metadata?.optJSONArray("alternateTitles")?.toStringSet().orEmpty()

        val tags = metadata?.optJSONArray("genre")?.mapJSONNotNullToSet { g ->
            val key = g.getStringOrNull("id") ?: return@mapJSONNotNullToSet null
            val name = g.getStringOrNull("name") ?: key
            MangaTag(key = key, title = name, source = source)
        }.takeUnless { it.isNullOrEmpty() } ?: manga.tags

        val authors = (
                metadata?.optJSONArray("author")?.mapJSONNotNullToSet { it.getStringOrNull("name") }.orEmpty() +
                        metadata?.optJSONArray("artist")?.mapJSONNotNullToSet { it.getStringOrNull("name") }.orEmpty()
                )

        val state = when (data.getStringOrNull("status")?.uppercase()) {
            "ONGOING" -> MangaState.ONGOING
            "COMPLETED" -> MangaState.FINISHED
            "HIATUS" -> MangaState.PAUSED
            else -> null
        }

        val isAdult = data.getBooleanOrDefault("isAdult", false)
        val contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE

        val rawDescription = data.getStringOrNull("description")
        val description = rawDescription?.let {
            if (it.contains('<') && it.contains('>')) {
                Jsoup.parse(it).text().nullIfEmpty()
            } else {
                it
            }
        }

        val mangaTitle = data.getStringOrNull("title") ?: manga.title

        val chaptersArray = data.optJSONObject("chapters")?.optJSONArray("chapters") ?: JSONArray()
        val chapters = chaptersArray.mapChapters(reversed = true) { index, ch ->
            val number = ch.getFloatOrDefault("number", (index + 1).toFloat())
            val numberStr = ch.getStringOrNull("number") ?: if (number % 1.0f == 0.0f) number.toInt().toString() else number.toString()
            val relUrl = "/manga/$slug/chapter-$numberStr"
            val dateStr = ch.getStringOrNull("updatedAt") ?: ch.getStringOrNull("createdAt")
            val uploadDate = parseDate(dateStr)

            MangaChapter(
                id = generateUid(relUrl),
                url = relUrl,
                title = "Chapter $numberStr",
                number = number,
                volume = 0,
                uploadDate = uploadDate,
                scanlator = null,
                branch = null,
                source = source,
            )
        }

        return manga.copy(
            title = mangaTitle,
            altTitles = altTitles,
            rating = rating,
            contentRating = contentRating,
            coverUrl = data.getStringOrNull("featuredImage") ?: manga.coverUrl,
            description = description,
            tags = tags,
            state = state,
            authors = authors,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val mangaSlug = chapter.url.substringBefore("/chapter-").substringAfterLast("/")
        val chapterNumber = chapter.url.substringAfterLast("/chapter-")
        val url = urlBuilder().apply {
            addPathSegment("api")
            addPathSegment("public")
            addPathSegment("chapter")
            addPathSegment(chapterNumber)
            addQueryParameter("mangaSlug", mangaSlug)
        }.build()

        val response = webClient.httpGet(url)
        val json = response.parseJson()
        val data = json.getJSONObject("data")
        val medias = data.getJSONArray("medias")

        return medias.mapJSONIndexed { index, media ->
            val filePath = media.getString("filePath")
            val pageNumber = media.getIntOrDefault("pageNumber", index)
            MangaPage(
                id = generateUid("${chapter.url}#$pageNumber"),
                url = filePath,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        val segments = link.pathSegments
        if (segments.size >= 2 && segments[0] == "manga") {
            val slug = segments[1]
            val relativeUrl = "/manga/$slug"
            return getDetails(
                Manga(
                    id = generateUid(relativeUrl),
                    title = slug,
                    altTitles = emptySet(),
                    url = relativeUrl,
                    publicUrl = relativeUrl.toAbsoluteUrl(domain),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = null,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            )
        }
        return null
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        val fullFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val parsed = fullFormat.parseSafe(dateStr)
        if (parsed != 0L) return parsed
        val shortFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return shortFormat.parseSafe(dateStr)
    }
}