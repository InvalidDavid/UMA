package tsuki.site.all

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.EnumSet

private val KNOWN_LOCALES = listOf("en", "ja", "zh", "zh-tw", "ko")

interface PixivTargetCompanion<out T : PixivTarget> {
    val searchPrefix: String
    fun fromSearchQuery(query: String): T? {
        if (!query.startsWith(searchPrefix)) return null
        val id = query.removePrefix(searchPrefix)
        if (!id.matches(Regex("\\d+"))) return null
        return fromSearchQueryId(id)
    }
    fun fromSearchQueryId(id: String): T?
}

sealed class PixivTarget {
    companion object {
        val BASE_URI = "https://www.pixiv.net".toHttpUrl()

        fun fromSearchQuery(query: String) = sequenceOf(User, Series, Illustration)
            .firstNotNullOfOrNull { it.fromSearchQuery(query) }

        fun fromUri(uri: String) = uri.toHttpUrlOrNull()?.let { fromUri(it) }
        fun fromUri(uri: HttpUrl): PixivTarget? {
            if (!(uri.scheme in listOf(null, "http", "https") && uri.host.let { "pixiv.net" == it.removePrefix("www.") })
            ) return null
            var pathSegments = uri.pathSegments.ifEmpty { null } ?: return null
            if (KNOWN_LOCALES.contains(pathSegments[0])) {
                pathSegments = pathSegments.subList(1, pathSegments.size)
            }
            if (pathSegments.size < 2) return null
            with(pathSegments[0]) {
                return when {
                    equals("artworks") -> Illustration(pathSegments[1])
                    equals("users") -> User(pathSegments[1])
                    equals("user") && (pathSegments.size >= 4 && pathSegments[2] == "series") ->
                        Series(pathSegments[3], pathSegments[1])
                    else -> null
                }
            }
        }
    }

    abstract fun toHttpUrl(): HttpUrl
    abstract fun toSearchQuery(): String

    data class User(val userId: String) : PixivTarget() {
        companion object : PixivTargetCompanion<User> {
            override val searchPrefix = "user:"
            override fun fromSearchQueryId(id: String) = User(id)
        }
        override fun toHttpUrl() = BASE_URI.newBuilder()
            .addPathSegment("users").addPathSegment(userId).build()
        override fun toSearchQuery(): String = searchPrefix + userId
    }

    data class Illustration(val illustId: String) : PixivTarget() {
        companion object : PixivTargetCompanion<Illustration> {
            override val searchPrefix = "aid:"
            override fun fromSearchQueryId(id: String) = Illustration(id)
        }
        override fun toHttpUrl() = BASE_URI.newBuilder()
            .addPathSegment("artworks").addPathSegment(illustId).build()
        override fun toSearchQuery(): String = searchPrefix + illustId
    }

    data class Series(val seriesId: String, val authorUserId: String? = null) : PixivTarget() {
        companion object : PixivTargetCompanion<Series> {
            override val searchPrefix = "sid:"
            override fun fromSearchQueryId(id: String) = Series(id)
        }
        override fun toHttpUrl() = BASE_URI.newBuilder()
            .addPathSegment("user").addPathSegment(authorUserId ?: "")
            .addPathSegment("series").addPathSegment(seriesId).build()
        override fun toSearchQuery(): String = searchPrefix + seriesId
    }
}

@MangaSourceParser("PIXIV_EN", "Pixiv (English)", "en")
internal class PixivEn(context: MangaLoaderContext) : PixivParser(context, MangaParserSource.PIXIV_EN)

@MangaSourceParser("PIXIV_JA", "Pixiv (Japanese)", "ja")
internal class PixivJa(context: MangaLoaderContext) : PixivParser(context, MangaParserSource.PIXIV_JA)

@MangaSourceParser("PIXIV_ZH", "Pixiv (Chinese)", "zh")
internal class PixivZh(context: MangaLoaderContext) : PixivParser(context, MangaParserSource.PIXIV_ZH)

@MangaSourceParser("PIXIV_KO", "Pixiv (Korean)", "ko")
internal class PixivKo(context: MangaLoaderContext) : PixivParser(context, MangaParserSource.PIXIV_KO)

abstract class PixivParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
) : PagedMangaParser(context, source, 18) {

    override val configKeyDomain = ConfigKey.Domain("pixiv.net")

    private val baseUrl = "https://$domain"

    private val imageQualityKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            "thumb_mini" to "Thumb Mini",
            "small" to "Small",
            "regular" to "Regular",
            "original" to "Original",
        ),
        defaultValue = "original",
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(imageQualityKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions()

    private suspend fun apiGet(path: String): JSONObject {
        val url = (baseUrl + path).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid URL: $path")
        val response = webClient.httpGet(url.toString())
        val body = response.body?.string() ?: throw Exception("Empty response")
        return JSONObject(body)
    }

    private suspend fun fetchRanking(page: Int): List<Manga> {
        val rankingJson = apiGet("/touch/ajax/ranking/illust?mode=daily&type=manga&page=$page")
        val rankingArray = rankingJson.optJSONObject("body")?.optJSONArray("ranking")
            ?: return emptyList()
        val illustIds = mutableListOf<String>()
        for (i in 0 until rankingArray.length()) {
            val entry = rankingArray.getJSONObject(i)
            entry.optString("illustId").takeIf { it.isNotEmpty() }?.let { illustIds.add(it) }
        }
        if (illustIds.isEmpty()) return emptyList()
        val detailsUrl = "/touch/ajax/illust/details/many?" +
                illustIds.joinToString("&") { "illust_ids[]=$it" }
        val detailsJson = apiGet(detailsUrl)
        val illustsArray = detailsJson.optJSONObject("body")?.optJSONArray("illust_details")
            ?: return emptyList()
        return (0 until illustsArray.length()).mapNotNull { i ->
            illustsArray.getJSONObject(i).toMangaIllust()
        }
    }

    private suspend fun fetchLatest(page: Int): List<Manga> {
        val json = apiGet("/touch/ajax/latest?type=manga&p=$page")
        val illustsArray = json.optJSONObject("body")?.optJSONArray("illusts")
            ?: return emptyList()
        return (0 until illustsArray.length()).mapNotNull { i ->
            illustsArray.getJSONObject(i).toMangaIllust()
        }
    }

    private suspend fun searchManga(query: String, page: Int): List<Manga> {
        val target = PixivTarget.fromUri(query) ?: PixivTarget.fromSearchQuery(query)
        when (target) {
            is PixivTarget.Illustration -> {
                val illust = getIllustCached(target.illustId)
                return listOfNotNull(illust?.toMangaIllust())
            }
            is PixivTarget.Series -> {
                val series = fetchSeries(target.seriesId)
                return listOfNotNull(series?.toMangaSeries())
            }
            is PixivTarget.User -> {
                val user = getUserCached(target.userId)
                return listOf(user.toMangaUser(target.userId))
            }
            else -> {}
        }

        val word = query.trim()
        val url = "/touch/ajax/search/illusts?word=${word.urlEncoded()}&s_mode=s_tc&p=$page"
        val json = apiGet(url)
        val illustsArray = json.optJSONObject("body")?.optJSONArray("illusts")
            ?: return emptyList()
        val results = mutableListOf<JSONObject>()
        for (i in 0 until illustsArray.length()) {
            val obj = illustsArray.getJSONObject(i)
            if (obj.optInt("is_ad_container") == 1) continue
            if (obj.optString("type") == "2") continue
            results.add(obj)
        }
        return results.mapNotNull { it.toMangaIllust() }
    }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim() ?: ""
        if (query.isNotBlank()) {
            return searchManga(query, page)
        }
        return when (order) {
            SortOrder.POPULARITY -> fetchRanking(page)
            SortOrder.NEWEST -> fetchLatest(page)
            else -> fetchLatest(page)
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val target = PixivTarget.fromUri(baseUrl + manga.url) ?: return@coroutineScope manga

        val detailsDeferred = async { fetchMangaDetails(target, manga) }
        val chaptersDeferred = async { fetchMangaChapters(target) }

        val details = detailsDeferred.await()
        val chapters = chaptersDeferred.await()

        details.copy(chapters = chapters)
    }

    private suspend fun fetchMangaDetails(target: PixivTarget, manga: Manga): Manga {
        return when (target) {
            is PixivTarget.User -> {
                val user = getUserCached(target.userId)
                manga.copy(
                    title = user.optString("name", manga.title),
                    authors = setOf(user.optString("name")),
                    description = user.optString("comment", manga.description ?: ""),
                    coverUrl = user.optString("imageBig", manga.coverUrl),
                )
            }
            is PixivTarget.Series -> {
                val series = fetchSeries(target.seriesId) ?: return manga
                val illusts = getSeriesIllustsCached(target.seriesId) ?: emptyList()
                val first = illusts.firstOrNull()
                val cover = series.optString("coverImage", null)
                    ?: first?.optString("url")
                    ?: manga.coverUrl
                val tags = illusts.flatMap { obj ->
                    val arr = obj.optJSONArray("tags") ?: return@flatMap emptyList()
                    (0 until arr.length()).map { arr.getString(it) }
                }.distinct().map { MangaTag(it, it, source) }.toSet()
                manga.copy(
                    title = series.optString("title", manga.title),
                    authors = setOfNotNull(first?.optJSONObject("author_details")?.optString("user_name")),
                    description = series.optString("caption", manga.description ?: ""),
                    coverUrl = cover,
                    tags = tags,
                )
            }
            is PixivTarget.Illustration -> {
                val illust = getIllustCached(target.illustId) ?: return manga
                val tags = (0 until (illust.optJSONArray("tags")?.length() ?: 0))
                    .map { illust.getJSONArray("tags").getString(it) }
                    .map { MangaTag(it, it, source) }.toSet()
                manga.copy(
                    title = illust.optString("title", manga.title),
                    authors = setOfNotNull(illust.optJSONObject("author_details")?.optString("user_name")),
                    description = illust.optString("comment", manga.description ?: ""),
                    coverUrl = illust.optString("url", manga.coverUrl),
                    tags = tags,
                )
            }
        }
    }

    private suspend fun fetchMangaChapters(target: PixivTarget): List<MangaChapter> {
        val illusts = when (target) {
            is PixivTarget.User -> {
                val json = apiGet("/touch/ajax/user/illusts?id=${target.userId}&p=1")
                val arr = json.optJSONObject("body")?.optJSONArray("illusts") ?: return emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }
            }
            is PixivTarget.Series -> getSeriesIllustsCached(target.seriesId) ?: emptyList()
            is PixivTarget.Illustration -> listOfNotNull(getIllustCached(target.illustId))
        }
        return illusts.mapIndexed { i, obj ->
            val id = obj.optString("id", i.toString())
            MangaChapter(
                id = generateUid(id),
                title = obj.optString("title", "(null)"),
                number = (illusts.size - i).toFloat(),
                volume = 0,
                url = "/artworks/$id",
                scanlator = null,
                uploadDate = obj.optLongOrNull("upload_timestamp")?.let { it * 1000 } ?: 0,
                branch = null,
                source = source,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val illustId = chapter.url.substringAfterLast('/')
        val json = apiGet("/ajax/illust/$illustId/pages")
        val pagesArray = json.optJSONArray("body") ?: return emptyList()
        val quality = config[imageQualityKey] ?: "original"
        return (0 until pagesArray.length()).map { i ->
            val pageObj = pagesArray.getJSONObject(i)
            val urls = pageObj.optJSONObject("urls") ?: JSONObject()
            val imageUrl = getImageUrl(urls, quality)
            MangaPage(
                id = generateUid("${chapter.id}-$i"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private fun getImageUrl(urls: JSONObject, quality: String): String {
        val sizeOrder = listOf("thumb_mini", "small", "regular", "original")
        val startIndex = sizeOrder.indexOf(quality).takeIf { it >= 0 } ?: sizeOrder.lastIndex
        return sizeOrder.drop(startIndex).firstNotNullOf { size ->
            urls.optString(size).takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    private suspend fun getIllustCached(illustId: String): JSONObject? {
        val json = apiGet("/touch/ajax/illust/details?illust_id=$illustId")
        return json.optJSONObject("body")?.optJSONObject("illust_details")
    }

    private suspend fun getUserCached(userId: String): JSONObject {
        val json = apiGet("/ajax/user/$userId?full=1")
        return json.optJSONObject("body") ?: json
    }

    private suspend fun getSeriesIllustsCached(seriesId: String): List<JSONObject>? {
        val result = mutableListOf<JSONObject>()
        var lastOrder = 0
        while (true) {
            val json = apiGet("/touch/ajax/illust/series_content/$seriesId?last_order=$lastOrder")
            val arr = json.optJSONObject("body")?.optJSONArray("series_contents") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                result.add(arr.getJSONObject(i))
            }
            lastOrder += arr.length()
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchSeries(seriesId: String): JSONObject? {
        val json = apiGet("/touch/ajax/illust/series/$seriesId")
        return json.optJSONObject("body")?.optJSONObject("series")
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        try { optLong(key) } catch (_: Exception) { null }

    private fun JSONObject.toMangaIllust(): Manga? {
        val id = optString("id").takeIf { it.isNotEmpty() } ?: return null
        val series = optJSONObject("series")
        val url = if (series != null) {
            val userId = series.optString("userId", optJSONObject("author_details")?.optString("user_id") ?: "")
            "/user/$userId/series/${series.optString("id")}"
        } else {
            "/artworks/$id"
        }
        val tags = optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.map { MangaTag(it, it, source) }.toSet()
        } ?: emptySet()
        val authors = setOfNotNull(optJSONObject("author_details")?.optString("user_name"))
        return Manga(
            id = generateUid(url),
            title = optString("title", "(null)"),
            altTitles = emptySet(),
            url = url,
            publicUrl = baseUrl + url,
            rating = RATING_UNKNOWN,
            contentRating = if (optString("x_restrict") == "1") ContentRating.ADULT else ContentRating.SAFE,
            coverUrl = optString("url", ""),
            tags = tags,
            state = null,
            authors = authors,
            largeCoverUrl = null,
            description = optString("comment"),
            source = source,
        )
    }

    private fun JSONObject.toMangaSeries(): Manga? {
        val id = optString("id").takeIf { it.isNotEmpty() } ?: return null
        val userId = optString("userId").takeIf { it.isNotEmpty() } ?: return null
        val url = "/user/$userId/series/$id"
        return Manga(
            id = generateUid(url),
            title = optString("title", "(null)"),
            altTitles = emptySet(),
            url = url,
            publicUrl = baseUrl + url,
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE,
            coverUrl = optString("coverImage", ""),
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            largeCoverUrl = null,
            description = optString("caption"),
            source = source,
        )
    }

    private fun JSONObject.toMangaUser(userId: String): Manga {
        val url = "/users/$userId"
        return Manga(
            id = generateUid(url),
            title = optString("name", "(null)"),
            altTitles = emptySet(),
            url = url,
            publicUrl = baseUrl + url,
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE,
            coverUrl = optString("imageBig", ""),
            tags = emptySet(),
            state = null,
            authors = setOf(optString("name")),
            largeCoverUrl = null,
            description = optString("comment"),
            source = source,
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
