package tsuki.site.en

import tsuki.network.OkHttpWebClient
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.*

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

// Todo: preloading chapter image fix

@MangaSourceParser("MISTSCANS", "Mist Scans", "en")
internal class MistScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MISTSCANS, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("mistscans.com")
    private val baseUrl = "https://$domain"

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

    override val webClient by lazy {
        OkHttpWebClient(context.httpClient.newBuilder().build(), source)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    private var cachedGenres: List<Genre>? = null

    private data class Genre(
        val name: String,
        val id: String
    )

    private suspend fun fetchGenres(): List<Genre> {
        if (cachedGenres != null) return cachedGenres!!
        val html = webClient.httpGet("$baseUrl/series/", getRequestHeaders()).body.string()
        val doc = Jsoup.parse(html, baseUrl)
        val genres = doc.select("#series_tags_page > button").map { btn ->
            Genre(btn.text(), btn.attr("tag"))
        }
        cachedGenres = genres
        return genres
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.ADDED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = fetchGenres()
        return MangaListFilterOptions(
            availableTags = genres.map { MangaTag(it.name, it.id, source) }.toSet(),
            availableStates = emptySet(),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val genreIds = filter.tags.map { it.key }

        if (query.isNotEmpty() || genreIds.isNotEmpty()) {
            return searchManga(query, genreIds)
        }

        return when (order) {
            SortOrder.UPDATED   -> fetchLatest()
            SortOrder.ADDED     -> fetchAdded()
            else                -> fetchPopular()
        }
    }

    private suspend fun fetchPopular(): List<Manga> {
        val html = webClient.httpGet(baseUrl, getRequestHeaders()).body.string()
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select(".series-splide .splide__slide").map { element ->
            popularMangaFromElement(element)
        }
    }

    private suspend fun fetchLatest(): List<Manga> {
        val html = webClient.httpGet("$baseUrl/latest/", getRequestHeaders()).body.string()
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("div.grid > div.group").map { element ->
            popularMangaFromElement(element)
        }
    }

    private suspend fun fetchAdded(): List<Manga> {
        val html = webClient.httpGet("$baseUrl/series/", getRequestHeaders()).body.string()
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("#searched_series_page > button").map { element ->
            popularMangaFromElement(element)
        }
    }

    private suspend fun searchManga(query: String, genreIds: List<String>): List<Manga> {
        val urlBuilder = "$baseUrl/series/".toHttpUrlOrNull()?.newBuilder()?.apply {
            if (query.isNotEmpty()) addQueryParameter("q", query)
            genreIds.forEach { addQueryParameter("genre", it) }
        }?.build()?.toString() ?: return emptyList()

        val html = webClient.httpGet(urlBuilder, getRequestHeaders()).body.string()
        val doc = Jsoup.parse(html, baseUrl)

        return doc.select("#searched_series_page > button")
            .filter { element ->
                val title = element.attr("title")
                val matchesQuery = query.isEmpty() || title.contains(query, true)

                val entryGenres = try {
                    element.attr("tags").parseAsList()
                } catch (_: Exception) { emptyList() }
                val matchesGenres = genreIds.isEmpty() ||
                        genreIds.all { genre -> entryGenres.any { it.equals(genre, true) } }

                matchesQuery && matchesGenres
            }
            .map { element -> popularMangaFromElement(element) }
    }

    private fun popularMangaFromElement(element: Element): Manga {
        val a = element.selectFirst("a[href]") ?: throw Exception("Link not found")
        val title = a.attr("title")
        val url = a.attr("abs:href")
        val cover = element.getImageUrl("*[style*=background-image]") ?: ""
        return Manga(
            id = generateUid(url),
            url = url,
            publicUrl = url,
            title = title,
            coverUrl = cover,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = fetchDocument(manga.url)

        val title = doc.selectFirst("div.grid > h1")?.text() ?: manga.title
        val cover = doc.getImageUrl("div[class*=photoURL]") ?: manga.coverUrl ?: ""
        val status = doc.selectFirst("div:has(span:containsOwn(Status)) ~ div").parseStatus()
        val author = doc.selectFirst("div:has(span:containsOwn(Author)) ~ div")?.text()
        val artist = doc.selectFirst("div:has(span:containsOwn(Artist)) ~ div")?.text()
        val type = doc.selectFirst("div:has(span:containsOwn(Type)) ~ div")?.text()
        val genres = doc.select("div.grid:has(>h1) > div > a:not([title='Status'])").map { it.text() }
        val altNames = doc.select("div.font-medium:containsOwn(Alternative titles) ~ div span").mapToSet { it.text() }
        val description = buildString {
            val synopsis = doc.selectFirst("#expand_content p")?.text().orEmpty()
            append(synopsis)
        }.takeIf { it.isNotEmpty() }

        val chapters = fetchChapters(doc)

        return manga.copy(
            title = title,
            altTitles = altNames,
            coverUrl = cover,
            description = description,
            authors = listOfNotNull(author, artist).toSet(),
            tags = (genres + listOfNotNull(type)).map { MangaTag(it.lowercase(), it, source) }.toSet(),
            state = status,
            chapters = chapters,
        )
    }

    private suspend fun fetchDocument(url: String): Document {
        val fullUrl = if (url.startsWith("http")) url else "$baseUrl$url"
        val html = webClient.httpGet(fullUrl, getRequestHeaders()).body.string()
        return Jsoup.parse(html, fullUrl)
    }

    private fun Element?.parseStatus(): MangaState? = when (this?.text()?.lowercase()) {
        "ongoing" -> MangaState.ONGOING
        "dropped" -> MangaState.ABANDONED
        "paused" -> MangaState.PAUSED
        "completed" -> MangaState.FINISHED
        else -> null
    }

    private fun fetchChapters(document: Document): List<MangaChapter> {
        val selector = "#chapters > a:not(:has(.text-sm span:matches(Upcoming))):not(:has(img[alt~=Coin]))"
        return document.select(selector).map { element ->
            val link = element.selectFirst("a[href]") ?: return@map null
            val url = link.attr("abs:href")
            val name = element.selectFirst(".text-sm")?.text() ?: ""
            val dateStr = element.selectFirst(".text-xs")?.text()?.trim() ?: ""
            val uploadDate = dateStr.parseDate()

            MangaChapter(
                id = generateUid(url),
                title = name,
                number = 0f,
                volume = 0,
                url = url,
                uploadDate = uploadDate,
                scanlator = null,
                branch = null,
                source = source,
            )
        }.filterNotNull().sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = fetchDocument(chapter.url)

        val cdnUrl = getCdnUrl(doc)
        val uidPages = doc.select("#pages > img")
            .map { it.attr("uid") }
            .filter { it.isNotEmpty() }

        if (uidPages.isNotEmpty() && cdnUrl != null) {
            return uidPages.mapIndexed { _, uid ->
                MangaPage(
                    id = generateUid(uid),
                    url = "$cdnUrl/$uid",
                    preview = null,
                    source = source,
                )
            }
        }

        return doc.select("#pages > img").map { it.imgAttr() }
            .filter { it.contains(Regex("""^(https?:)?//cdn\d*\.keyoapp\.com""")) }
            .mapIndexed { _, imgUrl ->
                MangaPage(
                    id = generateUid(imgUrl),
                    url = imgUrl,
                    preview = null,
                    source = source,
                )
            }
    }

    private fun getCdnUrl(document: Document): String? {
        val script = document.select("script").firstOrNull {
            CDN_HOST_REGEX.containsMatchIn(it.html())
        } ?: return null
        val cdnHost = CDN_HOST_REGEX.find(script.html())?.groups?.get(1)?.value
            ?.replace(CDN_CLEAN_REGEX, "") ?: return null
        return "https://$cdnHost/uploads"
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private fun Element.getImageUrl(selector: String): String? {
        val element = selectFirst(selector) ?: return null
        val style = element.attr("style")
        val match = IMG_REGEX.find(style) ?: return null
        val rawUrl = match.groups[1]?.value ?: return null
        val url = rawUrl.toHttpUrlOrNull() ?: return null
        return url.newBuilder().setQueryParameter("w", "480").build().toString()
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    private fun String.parseDate(): Long {
        val trimmed = trim()
        if (trimmed.contains("ago")) return trimmed.parseRelativeDate()
        return try { dateFormat.parse(trimmed)?.time ?: 0L } catch (_: Exception) { 0L }
    }

    private fun String.parseRelativeDate(): Long {
        val now = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val number = split(" ").firstOrNull()
            ?.replace("one", "1")
            ?.replace("a", "1")
            ?.toIntOrNull() ?: return 0L
        val text = this.lowercase()
        when {
            "second" in text -> now.add(Calendar.SECOND, -number)
            "minute" in text -> now.add(Calendar.MINUTE, -number)
            "hour" in text   -> now.add(Calendar.HOUR, -number)
            "day" in text    -> now.add(Calendar.DAY_OF_YEAR, -number)
            "week" in text   -> now.add(Calendar.WEEK_OF_YEAR, -number)
            "month" in text  -> now.add(Calendar.MONTH, -number)
            "year" in text   -> now.add(Calendar.YEAR, -number)
        }
        return now.timeInMillis
    }

    private fun String.parseAsList(): List<String> {
        return removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
    }

    companion object {
        val CDN_HOST_REGEX = """realUrl\s*=\s*`[^`]+//([^/]+)""".toRegex()
        val CDN_CLEAN_REGEX = """\$\{[^}]*\}""".toRegex()
        val IMG_REGEX = """url\(['"]?([^(['")])]+)""".toRegex()
    }
}
