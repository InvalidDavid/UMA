package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.RATING_UNKNOWN
import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.MangaState
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.mapToSet
import tsuki.util.parseHtml
import tsuki.util.selectFirstOrThrow
import tsuki.util.src
import tsuki.util.textOrNull
import tsuki.util.toTitleCase
import tsuki.util.urlEncoded
import tsuki.util.mapChapters
import tsuki.util.mapNotNullToSet
import tsuki.util.nullIfEmpty
import tsuki.util.parseSafe
import tsuki.util.removeSuffix

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.EnumSet

internal abstract class Manga18Parser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize) {

    override val configKeyDomain = ConfigKey.Domain(domain)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    @Volatile
    private var genresCache: Set<MangaTag>? = null
    private val genresMutex = Mutex()

    private suspend fun getOrFetchGenres(): Set<MangaTag> {
        genresCache?.let { return it }
        return genresMutex.withLock {
            genresCache ?: run {
                genresCache = fetchAvailableTags()
                genresCache!!
            }
        }
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/$listUrl/").parseHtml()
        return doc.select("div.grid_cate li").mapNotNullToSet { li ->
            val a = li.selectFirst("a") ?: return@mapNotNullToSet null
            val href = a.attr("href").removeSuffix('/').substringAfterLast('/')
            MangaTag(
                key = href,
                title = a.text(),
                source = source,
            )
        }
    }

    override fun getRequestHeaders() = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getOrFetchGenres(),
    )

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    protected open val ongoing: Set<String> = setOf("On Going")
    protected open val finished: Set<String> = setOf("Completed")

    protected open val listUrl = "list-manga/"
    protected open val tagUrl = "manga-list/"
    protected open val datePattern = "dd-MM-yyyy"

    protected open val selectDesc = "div.detail_reviewContent"
    protected open val selectState = "div.item:contains(Status) div.info_value"
    protected open val selectAlt = "div.item:contains(Other name) div.info_value"
    protected open val selectTag = "div.info_value > a[href*=/manga-list/]"
    protected open val selectAuthor = "div.info_label:contains(author) + div.info_value, div.info_label:contains(autor) + div.info_value"
    protected open val selectArtist = "div.info_label:contains(artist) + div.info_value"
    protected open val selectDate = "div.item p"
    protected open val selectChapter = "div.chapter_box li"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val tag = filter.tags.firstOrNull()

        val url = buildString(120) {
            append("https://")
            append(domain)
            append('/')

            when {
                tag != null && query.isNotEmpty() -> {
                    throw IllegalArgumentException("Search is not supported with tags")
                }
                tag != null -> {
                    append(tagUrl)
                    append(tag.key)
                    append('/')
                    append(page)
                }
                query.isNotEmpty() -> {
                    append(listUrl)
                    append(page)
                    append("?search=")
                    append(query.urlEncoded())
                    append("&order_by=latest")
                }
                else -> {
                    append(listUrl)
                    append(page)
                }
            }
            if (query.isEmpty()) {
                append("?order_by=")
                when (order) {
                    SortOrder.POPULARITY -> append("views")
                    SortOrder.UPDATED -> append("latest")
                    SortOrder.ALPHABETICAL -> append("name")
                    else -> append("latest")
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    protected open fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div.story_item").map { div ->
            val a = div.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val title = div.selectFirst("div.mg_info")
                ?.selectFirst("div.mg_name a")
                ?.text()
                ?: a.attr("title")
            val cover = div.selectFirst("img")?.src()

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = cover,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val info = doc.body().selectFirstOrThrow("div.detail_listInfo")
        val chaptersDeferred = async { getChapters(doc) }
        val desc = doc.selectFirst(selectDesc)?.html()?.nullIfEmpty()
        val state = info.selectFirst(selectState)?.text()?.let {
            when (it) {
                in ongoing -> MangaState.ONGOING
                in finished -> MangaState.FINISHED
                else -> null
            }
        }
        val alt = info.selectFirst(selectAlt)?.textOrNull()?.takeUnless { it == "Updating" }
        val author = info.selectFirst(selectAuthor)?.textOrNull()?.takeUnless { it == "Updating" }
        val artist = info.selectFirst(selectArtist)?.textOrNull()?.takeUnless { it == "Updating" }
        val tags = doc.body().select(selectTag).mapToSet { a ->
            val key = a.attr("href").removeSuffix('/').substringAfterLast('/')
            MangaTag(
                key = key,
                title = a.text().toTitleCase(),
                source = source,
            )
        }
        manga.copy(
            tags = tags,
            description = desc.nullIfEmpty(),
            altTitles = setOfNotNull(alt),
            authors = setOfNotNull(author, artist),
            state = state,
            chapters = chaptersDeferred.await(),
        )
    }

    protected open suspend fun getChapters(doc: Document): List<MangaChapter> {
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
        return doc.body().select(selectChapter).mapChapters(reversed = true) { i, li ->
            val a = li.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val dateText = li.selectFirst(selectDate)?.text()
            MangaChapter(
                id = generateUid(href),
                title = a.textOrNull(),
                number = i + 1f,
                volume = 0,
                url = href,
                uploadDate = dateFormat.parseSafe(dateText),
                source = source,
                scanlator = null,
                branch = null,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val script = doc.selectFirstOrThrow("script:containsData(slides_p_path)")
        val encoded = script.data()
            .substringAfter('[')
            .substringBefore(",]")
            .replace("\"", "")
            .split(",")

        return encoded.map { encodedUrl ->
            val decoded = context.decodeBase64(encodedUrl).toString(Charsets.UTF_8)
            val absoluteUrl = decoded.toAbsoluteUrl(domain)
            MangaPage(
                id = generateUid(absoluteUrl),
                url = absoluteUrl,
                preview = null,
                source = source,
            )
        }
    }
}