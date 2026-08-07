package tsuki.site.all

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
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.SocketException
import java.text.DecimalFormat
import java.util.Calendar
import java.util.EnumSet
import kotlin.math.max

private const val ID_SEARCH_PREFIX = "id:"

private const val webtoons = "webtoons"
private const val canvas = "canvas"

internal abstract class WebtoonsParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
) : PagedMangaParser(context, source, 20) {

    override val configKeyDomain = ConfigKey.Domain("webtoons.com")

    private val mobileApiDomain = "m.webtoons.com"
    private val staticDomain = "webtoon-phinf.pstatic.net"

    private val contentKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            webtoons to "Webtoons",
            canvas to "Canvas",
        ),
        defaultValue = webtoons,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(contentKey)
    }

    override val availableSortOrders: EnumSet<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.RELEVANCE,
        SortOrder.UPDATED,
        SortOrder.RATING,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override val userAgentKey = ConfigKey.UserAgent(
        "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
    )

    private val languageCode: String
        get() = when (val tag = sourceLocale.toLanguageTag()) {
            "in" -> "id"
            "zh" -> "zh-hant"
            else -> tag
        }

    private val localeForCookie: String
        get() = if (languageCode == "zh-hant") "zh_TW" else languageCode

    private val customClient by lazy {
        context.httpClient.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val url = original.url
                if (url.host.endsWith("webtoons.com")) {
                    val newRequest = original.newBuilder()
                        .header("Cookie", "ageGatePass=true; locale=$localeForCookie; needGDPR=false")
                        .build()
                    try {
                        chain.proceed(newRequest)
                    } catch (_: SocketException) {
                        chain.proceed(newRequest)
                    }
                } else {
                    chain.proceed(original)
                }
            }
            .build()
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/")
        .build()

    override val webClient by lazy {
        tsuki.network.OkHttpWebClient(customClient, source)
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags(),
    )

    private suspend fun fetchEpisodes(titleNo: Long, type: String): List<MangaChapter> {
        val baseUrl = "https://$mobileApiDomain/api/v1/$type/$titleNo/episodes"
        val url = if (type == "canvas") {
            "$baseUrl?pageSize=99999&readingLanguageCode=$languageCode"
        } else {
            "$baseUrl?pageSize=99999"
        }
        val json = webClient.httpGet(url).parseJson()
        val episodeList = json.optJSONObject("result")?.optJSONArray("episodeList")
            ?: throw ParseException("No episodes found for title $titleNo", url)

        val numberFormatter = DecimalFormat("#.##")
        data class RawEpisode(
            val jo: JSONObject,
            var chapterNumber: Float = -1f,
            var seasonNumber: Int = 1,
        )

        val rawList = (0 until episodeList.length()).map { i ->
            RawEpisode(episodeList.getJSONObject(i))
        }

        for (raw in rawList) {
            val title = raw.jo.optString("episodeTitle") ?: ""
            val match = episodeNoRegex.find(title)?.groupValues
            if (match != null && match.getOrNull(6).isNullOrBlank()) {
                raw.chapterNumber = match.getOrNull(11)?.toFloatOrNull() ?: -1f
                raw.seasonNumber = match.getOrNull(4)?.takeIf(String::isNotBlank)?.toInt() ?: 1
            }
        }

        var maxChapterNumber = 0f
        var currentSeason = 1
        var seasonOffset = 0f

        for ((idx, raw) in rawList.withIndex()) {
            if (raw.chapterNumber != -1f) {
                val original = raw.chapterNumber
                if (raw.seasonNumber > currentSeason) {
                    currentSeason = raw.seasonNumber
                    if (original <= maxChapterNumber) {
                        seasonOffset = maxChapterNumber
                    }
                }
                raw.chapterNumber = seasonOffset + original
                maxChapterNumber = max(maxChapterNumber, raw.chapterNumber)
            } else {
                val prev = rawList.getOrNull(idx - 1)
                if (prev != null) {
                    raw.chapterNumber = prev.chapterNumber + 0.01f
                } else {
                    raw.chapterNumber = 0f
                }
            }
        }

        return rawList.mapIndexed { idx, raw ->
            val jo = raw.jo
            val episodeTitle = jo.optString("episodeTitle", "")
            val viewerLink = jo.getString("viewerLink")
            val hasBgm = jo.optBoolean("hasBgm", false)
            val chapterNumber = raw.chapterNumber

            MangaChapter(
                id = generateUid("$titleNo-$idx"),
                title = buildString {
                    append(Parser.unescapeEntities(episodeTitle, false))
                    append(" (ch. ", numberFormatter.format(chapterNumber), ")")
                    if (hasBgm) append(" ♫")
                },
                number = chapterNumber,
                volume = 0,
                url = viewerLink,
                uploadDate = jo.optLong("exposureDateMillis"),
                branch = null,
                scanlator = null,
                source = source,
            )
        }.sortedBy { it.number }
    }

    private val episodeNoRegex = Regex(
        """(?:(s(eason)?|saison|part|vol(ume)?)\s*\.?\s*(\d+).*?)?(.*?(mini|bonus|special).*?)?(e(p(isode)?)?|ch(apter)?)\s*\.?\s*(\d+(\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val titleNo = manga.url.toLong()
        val detailsUrl = manga.publicUrl.ifBlank {
            "https://$domain/$languageCode/drama/placeholder/list?title_no=$titleNo"
        }

        val doc = webClient.httpGet(detailsUrl).parseHtml()

        val title = doc.select("meta[property='og:title']").attr("content")
            .ifEmpty { doc.select("h1.subj, h3.subj").text().ifEmpty { manga.title } }

        val description = listOf(
            doc.select("meta[property='og:description']").attr("content"),
            doc.select("#_asideDetail p.summary").text(),
            doc.select(".detail_header .summary").text(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val coverUrl = doc.select("meta[property=\"og:image\"]").attr("content").let { url ->
            if (url.isNotBlank()) url.toAbsoluteUrl(staticDomain) else manga.coverUrl
        }

        val author = listOf(
            doc.select("meta[property='com-linewebtoon:webtoon:author']").attr("content"),
            doc.select(".detail_header .info .author:nth-of-type(1)").firstOrNull()?.ownText(),
            doc.select(".author_area").text(),
        ).firstOrNull { !it.isNullOrBlank() && it != "null" }

        val artist = listOf(
            doc.select(".detail_header .info .author:nth-of-type(2)").firstOrNull()?.ownText(),
            doc.select(".author_area").text().takeIf { it != author },
        ).firstOrNull { !it.isNullOrBlank() } ?: author

        val genreElements = doc.select(".detail_header .info .genre").ifEmpty {
            doc.select("h2.genre")
        }
        val genres = genreElements.map { it.text() }.toSet()

        val dayInfo = doc.select("#_asideDetail p.day_info").text().ifEmpty {
            doc.select(".day_info").text()
        }
        val state = when {
            dayInfo.contains("UP") || dayInfo.contains("EVERY") || dayInfo.contains("NOUVEAU") -> MangaState.ONGOING
            dayInfo.contains("END") || dayInfo.contains("COMPLETED") || dayInfo.contains("TERMINÉ") -> MangaState.FINISHED
            else -> null
        }

        val type = if ("/canvas/" in detailsUrl) "canvas" else "webtoon"
        val chapters = async { fetchEpisodes(titleNo, type) }.await()

        Manga(
            id = generateUid(titleNo),
            title = title,
            altTitles = emptySet(),
            url = "$titleNo",
            publicUrl = detailsUrl,
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = coverUrl,
            largeCoverUrl = null,
            tags = genres.map { genre -> MangaTag(title = genre, key = genre.lowercase(), source = source) }.toSet(),
            authors = setOfNotNull(author, artist).filter { it != "null" }.toSet(),
            description = description,
            state = state,
            chapters = chapters,
            source = source,
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val currentContent = config[contentKey] ?: webtoons

        if (filter.query!!.startsWith("https://") || filter.query!!.startsWith(ID_SEARCH_PREFIX)) {
            return handleDirectSearch(filter.query!!, currentContent)
        }

        val selectedGenre = filter.tags.firstOrNull()

        val url = buildString {
            append("https://$domain/$languageCode")

            when {
                filter.query!!.isNotBlank() -> {
                    append("/search")
                    var cleanQuery = filter.query
                    var searchType: String? = null
                    when {
                        cleanQuery!!.startsWith("type:originals ") -> {
                            searchType = "originals"
                            cleanQuery = cleanQuery.removePrefix("type:originals ").trim()
                        }
                        cleanQuery.startsWith("type:canvas ") -> {
                            searchType = "canvas"
                            cleanQuery = cleanQuery.removePrefix("type:canvas ").trim()
                        }
                    }
                    if (searchType == null) {
                        searchType = when (currentContent) {
                            webtoons -> "originals"
                            canvas -> "canvas"
                            else -> null
                        }
                    }
                    if (searchType != null) append("/$searchType")
                    append("?keyword=${cleanQuery.urlEncoded()}")
                    if (page > 1 && searchType != null) append("&page=$page")
                }

                selectedGenre != null -> {
                    val genreUrlPath = genreUrlMap[selectedGenre.key] ?: selectedGenre.key
                    append("/genres/$genreUrlPath")
                    append("?sortOrder=${getSortOrderParam(order)}")
                    if (page > 1) append("&page=$page")
                }

                else -> {
                    if (currentContent == canvas) {
                        append("/ranking/canvas")
                        if (page > 1) append("?page=$page")
                    } else {
                        when (order) {
                            SortOrder.POPULARITY -> {
                                append("/ranking/popular")
                                if (page > 1) append("?page=$page")
                            }
                            SortOrder.RATING -> {
                                append("/ranking/trending")
                                if (page > 1) append("?page=$page")
                            }
                            SortOrder.RELEVANCE -> {
                                append("/ranking/originals")
                                if (page > 1) append("?page=$page")
                            }
                            SortOrder.UPDATED -> {
                                val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                                val dayName = when (day) {
                                    Calendar.MONDAY -> "monday"
                                    Calendar.TUESDAY -> "tuesday"
                                    Calendar.WEDNESDAY -> "wednesday"
                                    Calendar.THURSDAY -> "thursday"
                                    Calendar.FRIDAY -> "friday"
                                    Calendar.SATURDAY -> "saturday"
                                    Calendar.SUNDAY -> "sunday"
                                    else -> "sunday"
                                }
                                append("/originals/$dayName?sortOrder=UPDATE")
                            }
                            else -> throw Exception("Unknown sort order")
                        }
                    }
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".webtoon_list li a, .card_wrap .card_item a")
            .map { element -> createMangaFromElement(element, source as MangaParserSource, selectedGenre) }
    }

    private fun extractTitleNoFromUrl(url: String): Long {
        return Regex("title_no=(\\d+)").find(url)?.groupValues?.get(1)?.toLong()
            ?: throw ParseException("Could not extract title_no from URL: $url", url)
    }

    private fun createMangaFromElement(
        element: Element,
        source: MangaParserSource,
        selectedGenre: MangaTag? = null,
    ): Manga {
        val href = element.absUrl("href")
        val titleNo = extractTitleNoFromUrl(href)
        val title = element.select(".title, .card_title").text()
        val thumbnailUrl = element.select("img").attr("src")

        return Manga(
            id = generateUid(titleNo),
            title = title,
            altTitles = emptySet(),
            url = titleNo.toString(),
            publicUrl = href,
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = thumbnailUrl.toAbsoluteUrl(staticDomain),
            tags = selectedGenre?.let { setOf(it) } ?: emptySet(),
            state = null,
            authors = emptySet(),
            largeCoverUrl = null,
            description = null,
            source = source,
        )
    }

    private fun getSortOrderParam(order: SortOrder): String = when (order) {
        SortOrder.POPULARITY -> "MANA"
        SortOrder.RELEVANCE -> "LIKEIT"
        SortOrder.RATING -> "LIKEIT"
        SortOrder.UPDATED -> "UPDATE"
        else -> "MANA"
    }

    private suspend fun handleDirectSearch(query: String, currentContent: String): List<Manga> {
        var directTitleNo: Long? = null
        var directType: String? = null

        when {
            query.startsWith("https://") -> {
                val url = query.toHttpUrl()
                if (!url.host.endsWith("webtoons.com")) throw Exception("Unsupported domain: ${url.host}")
                directTitleNo = url.queryParameter("title_no")?.toLongOrNull()
                    ?: throw Exception("Missing 'title_no' parameter in URL")
                val path = url.pathSegments.filter(String::isNotEmpty)
                if (path.size < 2) throw Exception("Invalid URL path")
                if (path[0] != languageCode) {
                    throw Exception("URL language (${path[0]}) does not match parser language ($languageCode)")
                }
                directType = if (path.getOrNull(1) == "canvas") "canvas" else "webtoon"
            }
            query.startsWith(ID_SEARCH_PREFIX) -> {
                val idPart = query.removePrefix(ID_SEARCH_PREFIX)
                val parts = idPart.split(":")
                val (type, lang, titleNo) = when (parts.size) {
                    1 -> Triple(currentContent, languageCode, parts[0].toLongOrNull()
                        ?: throw Exception("Invalid title number: ${parts[0]}"))
                    2 -> Triple(parts[0], languageCode, parts[1].toLongOrNull()
                        ?: throw Exception("Invalid title number: ${parts[1]}"))
                    3 -> Triple(parts[0], parts[1], parts[2].toLongOrNull()
                        ?: throw Exception("Invalid title number: ${parts[2]}"))
                    else -> throw Exception("Invalid id search format")
                }
                if (lang != languageCode) return emptyList()
                if (type !in listOf("webtoons", "canvas")) throw Exception("Unknown type '$type'. Use 'webtoons' or 'canvas'.")
                directTitleNo = titleNo
                directType = type
            }
            query.toLongOrNull() != null -> {
                directTitleNo = query.toLong()
                directType = currentContent
            }
        }

        if (directTitleNo != null && directType != null) {
            val detailsUrl = "https://$domain/$languageCode/$directType/placeholder/list?title_no=$directTitleNo"
            val manga = getDetails(
                Manga(
                    id = generateUid(directTitleNo),
                    title = "",
                    altTitles = emptySet(),
                    url = directTitleNo.toString(),
                    publicUrl = detailsUrl,
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = null,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            )
            return listOf(manga)
        }
        return emptyList()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val absUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = try {
            webClient.httpGet(absUrl).parseHtml()
        } catch (e: Exception) {
            throw ParseException("Failed to get pages for chapter: ${chapter.title}", chapter.url, e)
        }

        val images = doc.select("div#_imageList > img").mapIndexedNotNull { i, element ->
            val rawUrl = element.attr("data-url").takeIf { it.isNotBlank() }
                ?: element.attr("src").takeIf { it.contains(staticDomain) }
                ?: return@mapIndexedNotNull null

            MangaPage(
                id = generateUid("${chapter.id}-$i"),
                url = rawUrl,
                preview = null,
                source = source,
            )
        }.toMutableList()

        if (images.isEmpty()) {
            val motionPages = fetchMotionToonPages(doc)
            if (motionPages.isNotEmpty()) images.addAll(motionPages)
        }

        if (images.isEmpty()) throw ParseException("No images found in chapter.", chapter.url)
        return images
    }

    private suspend fun fetchMotionToonPages(doc: org.jsoup.nodes.Document): List<MangaPage> {
        val docString = doc.toString()
        val docUrlRegex = Regex("documentURL:.*?'(.*?)'")
        val motionToonPathRegex = Regex("jpg:.*?'(.*?)\\{")

        val docUrl = docUrlRegex.find(docString)?.groupValues?.get(1) ?: return emptyList()
        val motionToonPath = motionToonPathRegex.find(docString)?.groupValues?.get(1) ?: return emptyList()

        return try {
            val motionToonResponse = webClient.httpGet(docUrl).parseJson()
            val imagesMap = motionToonResponse.optJSONObject("assets")?.optJSONObject("images")
            imagesMap?.let { map ->
                map.keySet().filter { it.contains("layer") }.mapIndexedNotNull { i, key ->
                    val relPath = map.optString(key, null) ?: return@mapIndexedNotNull null
                    MangaPage(
                        id = generateUid("motion-$i"),
                        url = motionToonPath + relPath,
                        preview = null,
                        source = source,
                    )
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun availableTags() = setOf(
        MangaTag("Action", "action", source),
        MangaTag("Comedy", "comedy", source),
        MangaTag("Drama", "drama", source),
        MangaTag("Fantasy", "fantasy", source),
        MangaTag("Horror", "horror", source),
        MangaTag("Romance", "romance", source),
        MangaTag("Sci-Fi", "sf", source),
        MangaTag("Slice of Life", "slice_of_life", source),
        MangaTag("Sports", "sports", source),
        MangaTag("Supernatural", "supernatural", source),
        MangaTag("Thriller", "thriller", source),
        MangaTag("Historical", "historical", source),
        MangaTag("Mystery", "mystery", source),
        MangaTag("Superhero", "super_hero", source),
        MangaTag("Heartwarming", "heartwarming", source),
        MangaTag("Graphic Novel", "graphic_novel", source),
        MangaTag("Informative", "tiptoon", source),
    )

    private val genreUrlMap: Map<String, String> = availableTags().associate {
        it.title.lowercase() to it.key
    }

    @MangaSourceParser("WEBTOONS_EN", "Webtoons (English)", "en", type = ContentType.MANGA)
    class English(context: MangaLoaderContext) : WebtoonsParser(context, MangaParserSource.WEBTOONS_EN)

    @MangaSourceParser("WEBTOONS_ID", "Webtoons (Indonesia)", "id", type = ContentType.MANGA)
    class Indonesian(context: MangaLoaderContext) : WebtoonsParser(context, MangaParserSource.WEBTOONS_ID)

    @MangaSourceParser("WEBTOONS_ES", "Webtoons (Spanish)", "es", type = ContentType.MANGA)
    class Spanish(context: MangaLoaderContext) : WebtoonsParser(context, MangaParserSource.WEBTOONS_ES)

    @MangaSourceParser("WEBTOONS_FR", "Webtoons (French)", "fr", type = ContentType.MANGA)
    class French(context: MangaLoaderContext) : WebtoonsParser(context, MangaParserSource.WEBTOONS_FR)

    @MangaSourceParser("WEBTOONS_TH", "Webtoons (Thai)", "th", type = ContentType.MANGA)
    class Thai(context: MangaLoaderContext) : WebtoonsParser(context, MangaParserSource.WEBTOONS_TH)

    @MangaSourceParser("WEBTOONS_ZH", "Webtoons (Chinese)", "zh", type = ContentType.MANGA)
    class Chinese(context: MangaLoaderContext) : WebtoonsParser(context, MangaParserSource.WEBTOONS_ZH)

    @MangaSourceParser("WEBTOONS_DE", "Webtoons (German)", "de", type = ContentType.MANGA)
    class German(context: MangaLoaderContext) : WebtoonsParser(context, MangaParserSource.WEBTOONS_DE)
}
