package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.SinglePageMangaParser

import tsuki.model.*
import tsuki.util.*
import tsuki.util.json.asTypedList
import tsuki.util.json.entries
import tsuki.util.json.getFloatOrDefault
import tsuki.util.json.getLongOrDefault
import tsuki.util.json.getStringOrNull
import tsuki.util.json.mapJSON
import tsuki.util.json.mapJSONNotNull
import tsuki.util.json.toStringSet
import tsuki.util.suspendlazy.suspendLazy

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.EnumSet
import java.util.concurrent.TimeUnit

@MangaSourceParser("FLAMECOMICS", "FlameComics", "en")
internal class FlameComics(context: MangaLoaderContext) :
    SinglePageMangaParser(context, MangaParserSource.FLAMECOMICS) {

    private val commonPrefix = suspendLazy(initializer = ::fetchCommonPrefix)
    private val removeSpecialCharsRegex = Regex("[^A-Za-z0-9 ]")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
        )

    override val configKeyDomain = ConfigKey.Domain("flamecomics.xyz")

    private fun JSONObject.getJSONArraySafe(key: String): JSONArray? {
        return try {
            val value = get(key)
            value as? JSONArray
        } catch (_: JSONException) {
            null
        }
    }

    override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        val hasSearchQuery = !query.isNullOrEmpty()
        val hasTagFilter = filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty()

        val rawMangas = if (hasSearchQuery || hasTagFilter || order != SortOrder.UPDATED) {
            val url = urlBuilder()
                .addPathSegment("_next")
                .addPathSegment("data")
                .addPathSegment(commonPrefix.get())
                .addPathSegment("browse.json")
                .build()
            val json = webClient.httpGet(url).parseJson().getJSONObject("pageProps").getJSONArray("series")

            json.mapJSONNotNull { jo ->
                val manga = parseManga(jo)
                val lastEdit = jo.getLongOrDefault("last_edit", 0L)
                val views = jo.getLongOrDefault("views", 0L)
                Triple(manga, lastEdit, views)
            }
        } else {
            val url = urlBuilder()
                .addPathSegment("_next")
                .addPathSegment("data")
                .addPathSegment(commonPrefix.get())
                .addPathSegment("index.json")
                .build()
            val json = webClient.httpGet(url).parseJson()
            val seriesArray = json
                .getJSONObject("pageProps")
                .getJSONObject("latestEntries")
                .getJSONArray("blocks")
                .getJSONObject(0)
                .getJSONArray("series")

            seriesArray.mapJSONNotNull { jo ->
                val manga = parseManga(jo)
                Triple(manga, 0L, 0L)
            }
        }

        val filteredByTags = rawMangas.filter { (manga, _, _) -> manga.tags.matches(filter) }

        val filteredManga = if (hasSearchQuery) {
            val normalizedQuery = removeSpecialCharsRegex.replace(query.lowercase(), "")
            filteredByTags.filter { (manga, _, _) ->
                val titles = mutableListOf(manga.title)
                titles.addAll(manga.altTitles)
                titles.any { title ->
                    normalizedQuery in removeSpecialCharsRegex.replace(title.lowercase(), "")
                }
            }
        } else {
            filteredByTags
        }
        val sorted = when (order) {
            SortOrder.UPDATED   -> filteredManga.sortedByDescending { it.second }
            SortOrder.POPULARITY -> filteredManga.sortedByDescending { it.third }
            else                -> filteredManga
        }
        return sorted.map { it.first }
    }

    override suspend fun getDetails(manga: Manga): Manga = getDetailsImpl(manga.url.toLong())

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (seriesId, token) = chapter.url.split('?')
        val url = urlBuilder()
            .addPathSegment("_next")
            .addPathSegment("data")
            .addPathSegment(commonPrefix.get())
            .addPathSegment("series")
            .addPathSegment(seriesId)
            .addPathSegment("$token.json")
            .addQueryParameter("id", seriesId)
            .addQueryParameter("token", token)
            .build()
        val json = webClient.httpGet(url).parseJson().getJSONObject("pageProps")
            .getJSONObject("chapter")
            .getJSONObject("images")
            .entries<JSONObject>()
        return json.map { (i, jo) ->
            MangaPage(
                id = generateUid("$i|$token"),
                url = imageUrl(seriesId, token + "/" + jo.getString("name"), 1920),
                preview = imageUrl(seriesId, token + "/" + jo.getString("name"), 128),
                source = source,
            )
        }
    }

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        val seriesId = link.pathSegments.lastOrNull()?.toLongOrNull() ?: return null
        return getDetailsImpl(seriesId)
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    private suspend fun fetchCommonPrefix(): String {
        val raw = webClient.httpGet(urlBuilder().build()).parseRaw()
        val regex = Regex("/_next/static/([^/]+)/_buildManifest\\.js")
        val prefix = raw.findGroupValue(regex)
        return checkNotNull(prefix) {
            "Unable to find common prefix in page. Looking for pattern: /_next/static/([^/]+)/_buildManifest\\.js\n" +
                    "Page content sample: ${raw.take(1000)}"
        }
    }

    private fun imageUrl(seriesId: Any, url: String, width: Int) = urlBuilder()
        .addPathSegment("_next")
        .addPathSegment("image")
        .addQueryParameter(
            "url",
            urlBuilder("cdn")
                .addPathSegment("uploads")
                .addPathSegment("images")
                .addPathSegment("series")
                .addPathSegment(seriesId.toString())
                .addPathSegments(url)
                .build().toString(),
        )
        .addQueryParameter("w", width.toString())
        .addQueryParameter("q", "100")
        .build()
        .toString()

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val url = urlBuilder()
            .addPathSegment("_next")
            .addPathSegment("data")
            .addPathSegment(commonPrefix.get())
            .addPathSegment("browse.json")
            .build()
        val seriesArray = webClient.httpGet(url).parseJson()
            .getJSONObject("pageProps")
            .getJSONArray("series")

        val tags = mutableSetOf<MangaTag>()
        for (i in 0 until seriesArray.length()) {
            val jo = seriesArray.getJSONObject(i)
            val categoriesArray = jo.getJSONArraySafe("categories")
            categoriesArray?.asTypedList<String>()?.forEach { tagName ->
                tags += tagName.toMangaTag()
            }
        }
        return tags
    }

    private fun parseManga(jo: JSONObject): Manga {
        val seriesId = jo.getLongOrDefault("series_id", -1L).takeIf { it != -1L }
            ?: jo.getLongOrDefault("novel_id", -1L).takeIf { it != -1L }
            ?: jo.getStringOrNull("series_id")?.toLongOrNull()
            ?: jo.getStringOrNull("novel_id")?.toLongOrNull()
            ?: jo.getLongOrDefault("id", -1L).takeIf { it != -1L }
            ?: jo.getStringOrNull("id")?.toLongOrNull()
            ?: jo.getLongOrDefault("seriesId", -1L).takeIf { it != -1L }
            ?: jo.getStringOrNull("seriesId")?.toLongOrNull()
            ?: jo.getLongOrDefault("series", -1L).takeIf { it != -1L }
            ?: jo.getStringOrNull("series")?.toLongOrNull()
            ?: throw IllegalArgumentException(
                "No valid series ID found. Available fields: ${jo.keys().asSequence().joinToString()}\n" +
                        "series_id value: ${jo.optString("series_id", "MISSING")}\n" +
                        "novel_id value: ${jo.optString("novel_id", "MISSING")}\n" +
                        "JSON sample: ${jo.toString().take(500)}"
            )
        val cover = jo.getStringOrNull("cover")

        val authorArray = jo.getJSONArraySafe("author")
        val authors = authorArray?.asTypedList<String>()?.toSet() ?: emptySet()

        val categoriesArray = jo.getJSONArraySafe("categories")
        val tags = categoriesArray?.asTypedList<String>()?.mapToSet { it.toMangaTag() } ?: emptySet()

        return Manga(
            id = generateUid(seriesId),
            title = jo.getString("title"),
            altTitles = jo.getStringOrNull("altTitles")?.let {
                JSONArray(it).toStringSet()
            }.orEmpty(),
            url = seriesId.toString(),
            publicUrl = "https://${domain}/series/$seriesId",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = if (cover != null) {
                imageUrl(seriesId, cover, 384)
            } else null,
            tags = tags,
            state = when (jo.getStringOrNull("status")) {
                "Dropped" -> MangaState.ABANDONED
                "Completed" -> MangaState.FINISHED
                "Hiatus" -> MangaState.PAUSED
                "Ongoing" -> MangaState.ONGOING
                else -> null
            },
            authors = authors,
            largeCoverUrl = if (cover != null) {
                imageUrl(seriesId, cover, 720)
            } else null,
            description = jo.getStringOrNull("description"),
            source = source,
        )
    }

    private suspend fun getDetailsImpl(seriesId: Long): Manga {
        val url = urlBuilder()
            .addPathSegment("_next")
            .addPathSegment("data")
            .addPathSegment(commonPrefix.get())
            .addPathSegment("series")
            .addPathSegment("$seriesId.json")
            .addQueryParameter("id", seriesId.toString())
            .build()
        val json = webClient.httpGet(url).parseJson().getJSONObject("pageProps")
        val series = json.getJSONObject("series")
        val chapters = json.getJSONArray("chapters")
        return parseManga(series).copy(
            chapters = chapters.mapJSON { jo ->
                val chapterId = jo.getLong("chapter_id")
                val number = jo.getFloatOrDefault("chapter", 0f)
                MangaChapter(
                    id = generateUid(longOf(seriesId.toInt(), chapterId.toInt())),
                    title = jo.getStringOrNull("name"),
                    number = number,
                    volume = 0,
                    url = seriesId.toString() + "?" + jo.getStringOrNull("token").orEmpty(),
                    scanlator = null,
                    uploadDate = TimeUnit.SECONDS.toMillis(jo.getLongOrDefault("release_date", 0L)),
                    branch = jo.getStringOrNull("language"),
                    source = source,
                )
            }.reversed(),
        )
    }

    private fun Set<MangaTag>.matches(filter: MangaListFilter): Boolean {
        if (filter.tags.isNotEmpty() && !containsAll(filter.tags)) {
            return false
        }
        for (tag in filter.tagsExclude) {
            if (contains(tag)) {
                return false
            }
        }
        return true
    }

    private fun String.toMangaTag() = MangaTag(
        title = this.toTitleCase(sourceLocale),
        key = this.lowercase().trim(),
        source = source,
    )
}
