package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.OkHttpWebClient

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

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

abstract class KeyoApp(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    pageSize: Int = 18,
) : PagedMangaParser(context, source, pageSize) {

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain(domain)

    override val webClient = OkHttpWebClient(context.httpClient, source)

    protected val baseUrl get() = "https://$domain"
    protected open val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

    protected open val showPaidChapters: Boolean = false

    open val popularMangaTitleSelector = listOf(
        "Popular",
        "Popularie",
        "Trending",
    )

    open fun popularMangaSelector(): String =
        popularMangaTitleSelector.joinToString {
            "div:contains($it) + div .group.overflow-hidden.grid"
        }

    open fun popularMangaFromElement(element: Element): Manga {
        val link = element.selectFirst("a[href]")!!
        val url = link.attr("abs:href")
        return Manga(
            id = generateUid(url),
            title = link.attr("title"),
            altTitles = emptySet(),
            url = url,
            publicUrl = url,
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = element.getImageUrl("*[style*=background-image]") ?: "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            largeCoverUrl = null,
            description = null,
            source = source,
        )
    }

    open fun latestUpdatesSelector(): String = "div.grid > div.group"

    open fun latestUpdatesFromElement(element: Element): Manga = popularMangaFromElement(element)

    open fun searchMangaSelector(): String = "#searched_series_page > button"

    open fun searchMangaFromElement(element: Element): Manga = popularMangaFromElement(element)

    open fun Element.isNovel(): Boolean =
        select("""[data-type="novel" i], span:matchesOwn((?i)^novel$)""").isNotEmpty()

    open fun Element.matchesQuery(query: String): Boolean =
        attr("title").contains(query, ignoreCase = true)

    open fun Element.matchesGenres(genres: List<String>): Boolean {
        val tagsAttr = attr("tags").replace("___", "\"")
        val entryGenres = Regex(""""([^"]+)"""").findAll(tagsAttr)
            .map { it.groupValues[1] }
            .toList()
        return genres.all { g -> entryGenres.any { it.equals(g, ignoreCase = true) } }
    }

    open fun Element.matchesTypes(types: List<String>): Boolean =
        types.any { it.equals(attr("data-type"), ignoreCase = true) }

    open fun Element.matchesStatuses(statuses: List<String>): Boolean =
        statuses.any { it.equals(attr("data-status"), ignoreCase = true) }

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.POPULARITY, SortOrder.NEWEST, SortOrder.RELEVANCE)

    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = getAvailableTags(),
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
            ContentType.OTHER,
        ),
    )

    @Volatile
    private var cachedGenres: Set<MangaTag>? = null

    protected open suspend fun getAvailableTags(): Set<MangaTag> {
        cachedGenres?.let { return it }
        return fetchGenres().also { cachedGenres = it }
    }

    private suspend fun fetchGenres(): Set<MangaTag> {
        return try {
            val doc = webClient.httpGet("$baseUrl/series/").parseHtml()
            parseGenres(doc)
        } catch (_: Exception) {
            emptySet()
        }
    }

    protected open fun parseGenres(document: Document): Set<MangaTag> {
        val script = document
            .select("script:containsData(initializeDropdownMenu)")
            .firstOrNull()?.data()
            ?: return emptySet()

        val genreStart = script.indexOf("""type: "genre"""")
        if (genreStart == -1) return emptySet()

        val itemsStart = script.indexOf("items:", genreStart)
        if (itemsStart == -1) return emptySet()

        val itemsEnd = script.indexOf(']', itemsStart)
        if (itemsEnd == -1) return emptySet()

        val genreBlock = script.substring(itemsStart, itemsEnd)
        if (genreBlock.isBlank()) return emptySet()

        val itemRegex = Regex(
            """value\s*:\s*["']([^"']+)["']\s*,\s*displayName\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )

        return itemRegex.findAll(genreBlock)
            .mapNotNull { match ->
                val value = match.groupValues[1].trim()
                val name = match.groupValues[2].trim()
                MangaTag(title = name, key = value, source = source)
                    .takeIf { name.isNotEmpty() && value.isNotEmpty() }
            }
            .toSet()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // Search / filtered browse → /series with params
        if (!filter.query.isNullOrBlank() || filter.tags.isNotEmpty() ||
            filter.states.isNotEmpty() || filter.types.isNotEmpty()
        ) {
            val url = "$baseUrl/series".toHttpUrl().newBuilder().apply {
                if (page > 1) addQueryParameter("page", page.toString())
                filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
                filter.tags.forEach { addQueryParameter("genre", it.key) }
                filter.states.firstOrNull()?.let {
                    addQueryParameter("status", it.toSiteStatus())
                }
                filter.types.firstOrNull()?.let {
                    addQueryParameter("type", it.toSiteType())
                }
            }.build()

            val doc = webClient.httpGet(url.toString()).parseHtml()
            return doc.select(searchMangaSelector())
                .filter { entry ->
                    !entry.isNovel() &&
                            (filter.query.isNullOrBlank() || entry.matchesQuery(filter.query!!)) &&
                            (filter.tags.isEmpty() || entry.matchesGenres(filter.tags.map { it.key })) &&
                            (filter.types.isEmpty() ||
                                    entry.matchesTypes(filter.types.map { it.toSiteType() })) &&
                            (filter.states.isEmpty() ||
                                    entry.matchesStatuses(filter.states.map { it.toSiteStatus() }))
                }
                .map { searchMangaFromElement(it) }
        }

        return when (order) {
            SortOrder.POPULARITY -> {
                val doc = webClient.httpGet(baseUrl).parseHtml()
                doc.select(popularMangaSelector())
                    .filter { !it.isNovel() }
                    .map { popularMangaFromElement(it) }
            }

            SortOrder.RELEVANCE -> {
                val url = if (page > 1) "$baseUrl/series/?page=$page" else "$baseUrl/series/"
                val doc = webClient.httpGet(url).parseHtml()
                doc.select(searchMangaSelector())
                    .filter { !it.isNovel() }
                    .map { searchMangaFromElement(it) }
            }

            else -> {
                val url = if (page > 1) "$baseUrl/latest/?page=$page" else "$baseUrl/latest/"
                val doc = webClient.httpGet(url).parseHtml()
                doc.select(latestUpdatesSelector())
                    .filter { !it.isNovel() }
                    .map { latestUpdatesFromElement(it) }
            }
        }
    }

    private fun MangaState.toSiteStatus(): String = when (this) {
        MangaState.ONGOING -> "ongoing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        MangaState.ABANDONED -> "dropped"
        else -> name.lowercase()
    }

    private fun ContentType.toSiteType(): String = when (this) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        else -> name.lowercase()
    }

    protected open val descriptionSelector: String = "#expand_content p"
    protected open val altNameSelector: String = "div.font-medium:containsOwn(Alternative titles) ~ div span"
    protected open val altNamePrefix: String = "Alternative names:"
    protected open val statusSelector: String = "div:has(span:containsOwn(Status)) ~ div"
    protected open val authorSelector: String = "div:has(span:containsOwn(Author)) ~ div"
    protected open val artistSelector: String = "div:has(span:containsOwn(Artist)) ~ div"
    protected open val genreSelector: String = "div:has(>h1) a[href*='genre=']"
    protected open val typeSelector: String = "div:has(span:containsOwn(Type)) ~ div"
    protected open val dateSelector: String = ".text-xs"
    protected open val paidChapterSelector: String = "img[alt~=Coin]"

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()

        val title = doc.selectFirst("div.grid > h1")?.text() ?: manga.title
        val thumbnail = doc.getImageUrl("div[class*=photoURL], div[style*=photoURL]")
            ?: manga.coverUrl
        val state = doc.selectFirst(statusSelector).parseStatus()

        val author = doc.selectFirst(authorSelector)?.text()
        val artist = doc.selectFirst(artistSelector)?.text()

        val genres = buildList {
            doc.selectFirst(typeSelector)?.text()?.replaceFirstChar {
                it.titlecase(Locale.ENGLISH)
            }?.let(::add)
            doc.select(genreSelector).forEach { add(it.text().trim(',', ' ')) }
        }.filter { it.isNotBlank() }

        val synopsis = doc.selectFirst(descriptionSelector)?.text().orEmpty()
        val altNames = doc.select(altNameSelector)
            .map { it.text() }
            .filter { it.isNotEmpty() && it != "No alternative titles." }

        val description = buildString {
            append(synopsis)
            if (altNames.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(altNamePrefix.trim())
                append("\n")
                altNames.joinTo(this, "\n") { "- $it" }
            }
        }.takeIf { it.isNotEmpty() }

        val chapters = parseChapterList(doc)

        return manga.copy(
            title = title,
            coverUrl = thumbnail,
            description = description,
            authors = setOfNotNull(author, artist).ifEmpty { null } ?: emptySet(),
            tags = genres.map { MangaTag(it, it.lowercase(), source) }.toSet(),
            state = state,
            chapters = chapters.reversed(),
        )
    }

    open fun chapterListSelector(): String {
        val base = "#chapters > :is(a, div):not(:has(.text-sm span:matches(Upcoming)))"
        return if (!showPaidChapters) {
            "$base:not(:has($paidChapterSelector))"
        } else {
            base
        }
    }

    open fun parseChapterList(document: Document): List<MangaChapter> =
        document.select(chapterListSelector()).map { chapterFromElement(it) }

    open fun chapterFromElement(element: Element): MangaChapter {
        val link = element.selectFirst("a[href]")!!
        val url = link.attr("abs:href")
        var name = element.selectFirst(".text-sm")!!.text()

        if (element.select(paidChapterSelector).isNotEmpty()) {
            name = "🔒 $name"
        }

        val uploadDate = element.selectFirst(dateSelector)
            ?.text()?.trim()?.parseDate()
            ?: 0L

        return MangaChapter(
            id = generateUid(url),
            title = name,
            number = Regex("""(\d+(?:\.\d+)?)""").find(name)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
            volume = 0,
            url = url,
            scanlator = null,
            uploadDate = uploadDate,
            branch = null,
            source = source,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()
        val cdnUrl = getCdnUrl(doc)

        val uids = doc.select("#pages > img")
            .map { it.attr("uid") }
            .filter { it.isNotEmpty() }

        if (uids.isNotEmpty() && cdnUrl != null) {
            return uids.mapIndexed { index, uid ->
                MangaPage(
                    id = generateUid("${chapter.id}-$index"),
                    url = "$cdnUrl/$uid",
                    preview = null,
                    source = source,
                )
            }
        }

        return doc.select("#pages > img")
            .map { it.imgAttr() }
            .filter { OLD_IMG_CDN_REGEX.containsMatchIn(it) }
            .mapIndexed { index, img ->
                MangaPage(
                    id = generateUid("${chapter.id}-$index"),
                    url = img,
                    preview = null,
                    source = source,
                )
            }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    protected open fun getCdnUrl(document: Document): String? {
        val script = document.select("script")
            .firstOrNull { CDN_HOST_REGEX.containsMatchIn(it.html()) }
            ?: return null
        val cdnHost = CDN_HOST_REGEX.find(script.html())
            ?.groups?.get(1)?.value
            ?.replace(CDN_CLEAN_REGEX, "")
            ?: return null
        return "https://$cdnHost/uploads"
    }

    protected fun Element?.parseStatus(): MangaState? = when (this?.text()?.lowercase()) {
        "ongoing" -> MangaState.ONGOING
        "dropped" -> MangaState.ABANDONED
        "paused" -> MangaState.PAUSED
        "completed" -> MangaState.FINISHED
        else -> null
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }

    protected open fun Element.getImageUrl(selector: String): String? =
        selectFirst(selector)?.let { element ->
            IMG_REGEX.find(element.attr("style"))?.groups?.get(1)?.value
                ?.toHttpUrlOrNull()?.newBuilder()
                ?.setQueryParameter("w", "480")
                ?.build()?.toString()
        }

    protected open fun String.parseDate(): Long {
        val text = trim()
        if (text.isEmpty()) return 0L
        if (text.contains("ago", ignoreCase = true)) return parseRelativeDate()
        return try {
            LocalDate.parse(text, dateFormat)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private fun String.parseRelativeDate(): Long {
        val now = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val relativeDate = split(" ").firstOrNull()
            ?.replace("one", "1")
            ?.replace("a", "1")
            ?.toIntOrNull()
            ?: return 0L

        when {
            "second" in this -> now.add(Calendar.SECOND, -relativeDate)
            "minute" in this -> now.add(Calendar.MINUTE, -relativeDate)
            "hour" in this -> now.add(Calendar.HOUR, -relativeDate)
            "day" in this -> now.add(Calendar.DAY_OF_YEAR, -relativeDate)
            "week" in this -> now.add(Calendar.WEEK_OF_YEAR, -relativeDate)
            "month" in this -> now.add(Calendar.MONTH, -relativeDate)
            "year" in this -> now.add(Calendar.YEAR, -relativeDate)
        }
        return now.timeInMillis
    }

    companion object {
        val CDN_HOST_REGEX = """realUrl\s*=\s*`[^`]+//([^/]+)""".toRegex()
        val CDN_CLEAN_REGEX = """\$\{[^}]*\}""".toRegex()
        val IMG_REGEX = """url\(['"]?([^(['")])]+)""".toRegex()
        val OLD_IMG_CDN_REGEX = Regex("""^(https?:)?//cdn\d*\.keyoapp\.com""")
    }
}
