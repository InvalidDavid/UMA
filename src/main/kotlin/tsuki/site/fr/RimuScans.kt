package tsuki.site.fr

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
import tsuki.util.json.extractNextJs

import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("RIMUSCANS", "Rimu Scans", "fr")
class RimuScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.RIMUSCANS, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("rimuscan.fr")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.FRENCH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isSearchWithFiltersSupported = true,
    )

    private val genresCache = mutableListOf<String>()
    private var genresLoaded = false

    private suspend fun loadGenres() {
        if (genresLoaded) return
        runCatching {
            val json = webClient.httpGet("https://$domain/api/admin/genres").parseJson()
            val arr = json.optJSONArray("genres") ?: return
            genresCache.clear()
            for (i in 0 until arr.length()) {
                genresCache.add(arr.getString(i))
            }
            genresLoaded = true
        }
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = run {
            loadGenres()
            genresCache.map { MangaTag(key = it, title = it, source = source) }.toSet()},
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, order, filter)
        val json = webClient.httpGet(url).parseJson()
        return parseSeriesList(json)
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        return "https://$domain/api/series".toHttpUrl().newBuilder().apply {
            val query = filter.query
            if (!query.isNullOrEmpty()) {
                addQueryParameter("search", query)
            } else {
                when (order) {
                    SortOrder.POPULARITY -> addQueryParameter("sort", "popular")
                    SortOrder.RATING -> addQueryParameter("sort", "rating")
                    SortOrder.ALPHABETICAL -> addQueryParameter("sort", "az")
                    SortOrder.UPDATED -> addQueryParameter("sort", "updated")
                    SortOrder.NEWEST -> addQueryParameter("sort", "new")
                    else -> addQueryParameter("sort", "updated")
                }
                if (filter.types.isNotEmpty()) {
                    val typeParam = filter.types.mapNotNull { type ->
                        when (type) {
                            ContentType.MANHWA -> "webtoon"
                            ContentType.MANGA -> "manga"
                            else -> null
                        }
                    }.joinToString(",")
                    if (typeParam.isNotEmpty()) addQueryParameter("types", typeParam)
                }
                if (filter.states.isNotEmpty()) {
                    val statusParam = filter.states.mapNotNull { state ->
                        when (state) {
                            MangaState.ONGOING -> "ongoing"
                            MangaState.FINISHED -> "completed"
                            MangaState.PAUSED -> "hiatus"
                            else -> null
                        }
                    }.joinToString(",")
                    if (statusParam.isNotEmpty()) addQueryParameter("status", statusParam)
                }
                if (filter.tags.isNotEmpty()) {
                    addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
                }
            }
            addQueryParameter("page", page.toString())
        }.build().toString()
    }

    private fun parseSeriesList(json: JSONObject): List<Manga> {
        val seriesArr = json.optJSONArray("series") ?: return emptyList()
        return (0 until seriesArr.length()).mapNotNull { i ->
            val obj = seriesArr.getJSONObject(i)
            val slug = obj.optString("slug").takeIf { it.isNotEmpty() }
            val title = obj.optString("title").takeIf { it.isNotEmpty() }
            val cover = obj.optString("cover_url").takeIf { it.isNotEmpty() }
            if (slug == null || title == null) {
                null
            } else {
                Manga(
                    id = generateUid(slug),
                    title = title,
                    altTitles = emptySet(),
                    contentRating = null,
                    url = "/manga/$slug",
                    publicUrl = "https://$domain/manga/$slug",
                    coverUrl = cover?.toAbsoluteUrl(),
                    rating = RATING_UNKNOWN,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            }
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl()).parseHtml()
        val details = parseDetailsFromLd(doc, manga)
        val chapters = extractChapters(doc, manga.url.substringAfterLast("/"))
        details.copy(
            chapters = chapters,
        )
    }

    private fun parseDetailsFromLd(doc: Document, fallback: Manga): Manga {
        val ldScripts = doc.select("script[type=application/ld+json]")
        for (script in ldScripts) {
            val data = script.data()
            if (data.contains("\"ComicSeries\"")) {
                val json = JSONObject(data)
                val name = json.optString("name").ifEmpty { fallback.title }
                val description = json.optString("description")
                val image = json.optString("image")
                val author = json.optJSONObject("author")?.optString("name")
                val illustrator = json.optJSONObject("illustrator")?.optString("name")
                val genres = json.optJSONArray("genre")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                val altNames = json.optJSONArray("alternateName")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()

                val h1 = doc.selectFirst("h1")
                val badges = h1?.previousElementSibling()?.select("span")?.map { it.text().trim() } ?: emptyList()
                val typeLabel = badges.getOrNull(0)
                val statusLabel = badges.getOrNull(1)
                val state = when (statusLabel?.lowercase()) {
                    "en cours", "ongoing" -> MangaState.ONGOING
                    "terminé", "termine", "completed" -> MangaState.FINISHED
                    "en pause", "hiatus", "on hiatus" -> MangaState.PAUSED
                    "annulé", "annule", "abandonné", "abandonne", "cancelled" -> MangaState.ABANDONED
                    else -> null
                }
                val contentType = when (typeLabel?.lowercase()) {
                    "webtoon", "manhwa" -> ContentType.MANHWA
                    "manhua" -> ContentType.MANHUA
                    "manga" -> ContentType.MANGA
                    else -> null
                }
                val allGenres = buildSet {
                    if (contentType != null) add(contentType.toString().lowercase())
                    genres.forEach { add(it) }
                }
                val tags = allGenres.map { MangaTag(key = it.lowercase(), title = it, source = source) }.toSet()
                val fullDescription = buildString {
                    if (description.isNotEmpty()) append(description)
                    if (altNames.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append("Titres alternatifs : ")
                        append(altNames.joinToString(", "))
                    }
                }.ifEmpty { null }

                return fallback.copy(
                    title = name,
                    description = fullDescription,
                    coverUrl = image.toAbsoluteUrl(),
                    authors = setOfNotNull(author, illustrator).filter { it.isNotEmpty() }.toSet(),
                    tags = tags,
                    state = state,
                )
            }
        }
        return fallback
    }

    private fun extractChapters(doc: Document, mangaSlug: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        val seenNumbers = mutableSetOf<Double>()

        doc.extractNextJs { element ->
            if (element is JSONObject && element.has("number") && element.has("type")) {
                runCatching {
                    val number = element.optDouble("number", Double.NaN)
                    if (!number.isNaN() && seenNumbers.add(number)) {
                        val type = element.optString("type", "NORMAL")
                        val title = element.optString("title", "").trim()
                        val releaseDate = element.optString("releaseDate").ifEmpty { null }
                        val numberString = number.toString().substringBefore(".0")
                        val chapterName = buildString {
                            append("Chapitre $numberString")
                            if (title.isNotEmpty() && title != "Chapitre $numberString") {
                                if (title.contains("Chapitre", true) || title.contains("Chapter", true)) {
                                    clear()
                                    append(title)
                                } else {
                                    append(" : $title")
                                }
                            }
                        }.let { if (type.equals("PREMIUM", true)) "🔒 $it" else it }
                        val date = releaseDate?.let { dateFormat.parse(it)?.time } ?: 0L
                        chapters.add(
                            MangaChapter(
                                id = generateUid("$mangaSlug-$number"),
                                url = "/read/$mangaSlug/$numberString",
                                title = chapterName,
                                number = number.toFloat(),
                                volume = 0,
                                uploadDate = date,
                                scanlator = null,
                                branch = null,
                                source = source,
                            )
                        )
                    }
                }
            }
            false
        }

        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl()).parseHtml()
        val chapterNumber = chapter.number.toDouble()
        val chapterObjects = mutableListOf<JSONObject>()

        doc.extractNextJs { element ->
            if (element is JSONObject && element.has("number") && element.has("images")) {
                chapterObjects.add(element)
            }
            false
        }

        val chapterObj = chapterObjects.firstOrNull {
            it.optDouble("number") == chapterNumber && (it.optJSONArray("images")?.length() ?: 0) > 0
        } ?: chapterObjects.firstOrNull { it.optDouble("number") == chapterNumber }
        ?: return emptyList()

        if (chapterObj.optString("type").equals("PREMIUM", true)) {
            throw ParseException("Ce chapitre est premium. Lisez-le sur le site.", chapter.url.toAbsoluteUrl())
        }

        val imagesArr = chapterObj.optJSONArray("images") ?: return emptyList()
        val images = mutableListOf<Pair<Int, String>>()
        for (i in 0 until imagesArr.length()) {
            val imgObj = imagesArr.getJSONObject(i)
            val order = imgObj.optInt("order")
            val url = imgObj.optString("url").takeIf { it.isNotEmpty() } ?: continue
            images.add(order to url)
        }
        return images.sortedBy { it.first }.mapIndexed { _, (_, url) ->
            MangaPage(
                id = generateUid(url),
                url = url.toAbsoluteUrl(),
                preview = null,
                source = source,
            )
        }
    }

    private fun okhttp3.Response.parseJson(): JSONObject {
        val body = body?.string() ?: throw ParseException("Empty response body", request.url.toString())
        return JSONObject(body)
    }

    private fun String.toAbsoluteUrl(): String = when {
        startsWith("http") -> this
        startsWith("/") -> "https://$domain$this"
        else -> "https://$domain/$this"
    }
}
