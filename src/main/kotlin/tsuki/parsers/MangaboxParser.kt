@file:Suppress("DEPRECATION")

package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.FlexiblePagedMangaParser

import tsuki.model.RATING_UNKNOWN
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.MangaState
import tsuki.model.SortOrder
import tsuki.model.WordSet
import tsuki.model.search.MangaSearchQuery
import tsuki.model.search.MangaSearchQueryCapabilities
import tsuki.model.search.QueryCriteria
import tsuki.model.search.SearchCapability
import tsuki.model.search.SearchableField

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.mapToSet
import tsuki.util.parseHtml
import tsuki.util.selectFirstOrThrow
import tsuki.util.src
import tsuki.util.toTitleCase
import tsuki.util.mapChapters
import tsuki.util.nullIfEmpty
import tsuki.util.parseSafe
import tsuki.util.removeSuffix
import tsuki.util.requireSrc
import tsuki.util.attrAsRelativeUrlOrNull
import tsuki.util.toRelativeUrl

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet

internal abstract class MangaboxParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    pageSize: Int = 24,
) : FlexiblePagedMangaParser(context, source, pageSize) {

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    @Deprecated("Use availableSortOrders instead", ReplaceWith("availableSortOrders"))
    override val searchQueryCapabilities: MangaSearchQueryCapabilities
        get() = MangaSearchQueryCapabilities(
            SearchCapability(
                field = SearchableField.TAG,
                criteriaTypes = setOf(QueryCriteria.Include::class, QueryCriteria.Exclude::class),
                isMultiple = true,
            ),
            SearchCapability(
                field = SearchableField.TITLE_NAME,
                criteriaTypes = setOf(QueryCriteria.Match::class),
                isMultiple = false,
            ),
            SearchCapability(
                field = SearchableField.STATE,
                criteriaTypes = setOf(QueryCriteria.Include::class),
                isMultiple = true,
            ),
            SearchCapability(
                field = SearchableField.AUTHOR,
                criteriaTypes = setOf(QueryCriteria.Include::class),
                isMultiple = false,
                isExclusive = true,
            ),
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    @JvmField
    protected val ongoing: Set<String> = setOf(
        "ongoing",
    )

    @JvmField
    protected val finished: Set<String> = setOf(
        "completed",
    )

    protected open val listUrl = "/manga-list/latest-manga"
    protected open val searchUrl = "/search/story/"
    protected open val datePattern = "MMM dd,yy"

    override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
        val titleQuery = query.criteria.filterIsInstance<QueryCriteria.Match<*>>()
            .firstOrNull { it.field == SearchableField.TITLE_NAME }
            ?.value
            ?.toString()
            ?.trim()

        if (!titleQuery.isNullOrBlank()) {
            val url = "https://$domain${searchUrl.trimEnd('/')}/${normalizeSearchQuery(titleQuery)}?page=$page"
            val doc = webClient.httpGet(url).parseHtml()
            return parseSearchResults(doc)
        }

        val tagCriterion = query.criteria.filterIsInstance<QueryCriteria.Include<*>>()
            .firstOrNull { it.field == SearchableField.TAG }

        val tagKey = tagCriterion?.values?.firstOrNull()?.let { value ->
            when (value) {
                is MangaTag -> value.key
                else -> value.toString().replace(" ", "-").lowercase(sourceLocale)
            }
        }

        if (!tagKey.isNullOrBlank()) {
            val url = "https://$domain/genre/$tagKey?page=$page"
            val doc = webClient.httpGet(url).parseHtml()
            return parseSearchResults(doc)
        }

        val url = "https://$domain$listUrl?page=$page"
        val doc = webClient.httpGet(url).parseHtml()
        return parseSearchResults(doc)
    }

    protected open fun parseSearchResults(doc: Document): List<Manga> {
        val elements = doc.select(".panel_story_list .story_item")
            .ifEmpty { doc.select(".story_item") }
            .ifEmpty { doc.select("div.list-truyen-item-wrap") }
            .ifEmpty { doc.select("div.list-comic-item-wrap") }
            .ifEmpty { doc.select("div.content-genres-item") }
            .ifEmpty { doc.select("div.itemupdate") }
            .ifEmpty { doc.select("div.manga-item") }
            .ifEmpty { doc.select(".item") }
            .ifEmpty { doc.select("a[href*='/manga/']").mapNotNull { it.parent() ?: it } }

        return elements.mapNotNull { div ->
            val linkElement = div.selectFirst(".story_name a")
                ?: div.selectFirst("h3 a")
                ?: div.selectFirst("h2 a")
                ?: div.selectFirst(".slide-caption h3 a")
                ?: div.selectFirst("a.cover")
                ?: div.selectFirst("a[href*='/manga/']")
                ?: if (div.tagName() == "a") div else null

            linkElement ?: return@mapNotNull null

            val href = linkElement.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            if (!href.contains("/manga/")) return@mapNotNull null

            val title = linkElement.text().trim()
                .takeIf { it.isNotEmpty() }
                ?: linkElement.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: div.selectFirst("h3, h2, h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            val img = linkElement.selectFirst("img") ?: div.selectFirst("img")
            val coverUrl = img?.attr("data-src")?.nullIfEmpty()
                ?: img?.attr("data-lazy-src")?.nullIfEmpty()
                ?: img?.src()

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = coverUrl,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = sourceContentRating,
            )
        }
    }

    protected open fun normalizeSearchQuery(query: String): String {
        return query.lowercase(sourceLocale)
            .replace("[àáạảãâầấậẩẫăằắặẳẵ]".toRegex(), "a")
            .replace("[èéẹẻẽêềếệểễ]".toRegex(), "e")
            .replace("[ìíịỉĩ]".toRegex(), "i")
            .replace("[òóọỏõôồốộổỗơờớợởỡ]".toRegex(), "o")
            .replace("[ùúụủũưừứựửữ]".toRegex(), "u")
            .replace("[ỳýỵỷỹ]".toRegex(), "y")
            .replace("đ".toRegex(), "d")
            .replace("""[^\p{L}\p{N}]+""".toRegex(), "_")
            .replace("_+".toRegex(), "_")
            .trim('_')
    }

    protected open val selectTagMap = "div.panel-genres-list a:not(.genres-select)"

    protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain$listUrl").parseHtml()
        val tags = doc.select(selectTagMap).drop(1)

        return tags.mapToSet { a ->
            val key = a.attr("href").removeSuffix('/').substringAfterLast('/')
            val name = a.attr("title").replace(" Manga", "")
            MangaTag(
                key = key,
                title = name,
                source = source,
            )
        }
    }

    protected open val selectDesc = "div#noidungm, div#panel-story-info-description"
    protected open val selectState = "li:contains(status), td:containsOwn(status) + td"
    protected open val selectAlt = ".story-alternative, tr:has(.info-alternative) h2"
    protected open val selectAut = "li:contains(author) a, td:contains(author) + td a"
    protected open val selectTag = "div.manga-info-top li:contains(genres) a , td:containsOwn(genres) + td a"

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val chaptersDeferred = async { getChapters(doc) }
        val desc = doc.selectFirst(selectDesc)?.html()
        val stateDiv = doc.select(selectState).text()
        val state = stateDiv.let {
            when (it.lowercase()) {
                in ongoing -> MangaState.ONGOING
                in finished -> MangaState.FINISHED
                else -> null
            }
        }
        val alt = doc.body().select(selectAlt).text().replace("Alternative : ", "").nullIfEmpty()
        val authors = doc.body().select(selectAut).mapToSet { it.text() }

        manga.copy(
            tags = doc.body().select(selectTag).mapToSet { a ->
                val href = a.attr("href")
                MangaTag(
                    key = href.substringAfterLast("category=").substringBefore("&")
                        .takeIf { it != href }
                        ?: href.removeSuffix("/").substringAfterLast("/"),
                    title = a.text().toTitleCase(),
                    source = source,
                )
            },
            description = desc,
            altTitles = setOfNotNull(alt),
            authors = authors,
            state = state,
            chapters = chaptersDeferred.await(),
        )
    }

    protected open val selectDate = "span"
    protected open val selectChapter = "div.chapter-list div.row, ul.row-content-chapter li"

    protected open suspend fun getChapters(doc: Document): List<MangaChapter> {
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
        return doc.body().select(selectChapter).mapChapters(reversed = true) { i, li ->
            val a = li.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val dateText = li.select(selectDate).last()?.text()

            MangaChapter(
                id = generateUid(href),
                title = a.text(),
                number = i + 1f,
                volume = 0,
                url = href,
                uploadDate = parseChapterDate(
                    dateFormat,
                    dateText,
                ),
                source = source,
                scanlator = null,
                branch = null,
            )
        }
    }

    protected open val selectPage = "div#vungdoc img, div.container-chapter-reader img"
    protected open val otherDomain = ""

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val scriptPages = parseScriptPages(doc)
        if (scriptPages.isNotEmpty()) {
            return scriptPages
        }

        val pages = doc.select(selectPage).mapNotNull { img ->
            val url = img.requireSrc().toRelativeUrl(domain)
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }

        if (pages.isNotEmpty() || otherDomain.isBlank()) {
            return pages
        }

        val fallbackUrl = fullUrl.replace(domain, otherDomain)
        val fallbackDoc = webClient.httpGet(fallbackUrl).parseHtml()

        return fallbackDoc.select(selectPage).map { img ->
            val url = img.requireSrc().toRelativeUrl(domain)
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseScriptPages(doc: Document): List<MangaPage> {
        val content = doc.select("script:containsData(cdns =), script:containsData(chapterImages =)")
            .joinToString("\n") { it.data() }

        if (content.isBlank()) {
            return emptyList()
        }

        val cdns = extractArray(content, cdnsRegex) + extractArray(content, backupImageRegex)
        val chapterImages = extractArray(content, chapterImagesRegex)

        if (cdns.isEmpty() || chapterImages.isEmpty()) {
            return emptyList()
        }

        val baseUrl = cdns.first().toHttpUrlOrNull() ?: return emptyList()

        return chapterImages.map { imagePath ->
            val url = baseUrl.newBuilder()
                .encodedPath("/$imagePath".replace("//", "/"))
                .build()
                .toString()

            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun extractArray(scriptContent: String, regex: Regex): List<String> {
        val match = regex.find(scriptContent) ?: return emptyList()
        return match.groupValues[1]
            .split(",")
            .mapNotNull { raw ->
                raw.trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                    .replace("\\/", "/")
                    .removeSuffix("/")
                    .nullIfEmpty()
            }
    }

    protected fun parseChapterDate(dateFormat: DateFormat, date: String?): Long {
        val d = date?.lowercase() ?: return 0
        return when {
            WordSet(" ago", " h", " d").endsWith(d) -> {
                parseRelativeDate(d)
            }

            WordSet("today").startsWith(d) -> {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }

            date.contains(Regex("""\d(st|nd|rd|th)""")) -> date.split(" ").map {
                if (it.contains(Regex("""\d\D\D"""))) {
                    it.replace(Regex("""\D"""), "")
                } else {
                    it
                }
            }.let { dateFormat.parseSafe(it.joinToString(" ")) }

            else -> dateFormat.parseSafe(date)
        }
    }

    private fun parseRelativeDate(date: String): Long {
        val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
        val cal = Calendar.getInstance()
        return when {
            WordSet("second")
                .anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis

            WordSet("min", "minute", "minutes")
                .anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis

            WordSet("hour", "hours", "h")
                .anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis

            WordSet("day", "days")
                .anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis

            WordSet("month", "months")
                .anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis

            WordSet("year")
                .anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis

            else -> 0
        }
    }

    @Deprecated("Use getRequestHeaders() instead", ReplaceWith("getRequestHeaders()"))
    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
        .add("Accept-Encoding", "gzip, deflate, br")
        .add("Accept-Language", "en-US,en;q=0.9")
        .build()

    companion object {

        private val cdnsRegex = Regex("""cdns\s*=\s*\[([^]]+)]""")
        private val backupImageRegex = Regex("""backupImage\s*=\s*\[([^]]+)]""")
        private val chapterImagesRegex = Regex("""chapterImages\s*=\s*\[([^]]+)]""")
    }
}