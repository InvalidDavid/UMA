package tsuki.site.galleryadults

import androidx.collection.ArraySet
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.ErrorMessages
import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.*
import tsuki.util.*
import java.util.*

internal data class GalleryAdultsSiteConfig(
    val domain: String,
    val pageSize: Int = 20,
    val popularTagsPath: String = "/tags/popular/?page=",
    val selectors: GalleryAdultsSelectors = GalleryAdultsSelectors(),
    val availableLocales: Set<Locale> = DEFAULT_AVAILABLE_LOCALES,
    val availableSortOrders: Set<SortOrder> = setOf(SortOrder.UPDATED),
    val supportsMultipleTags: Boolean = false,
    val supportsAuthorSearch: Boolean = false,
) {
    init {
        require(domain.isNotBlank()) { "Gallery site domain must not be blank" }
        require(pageSize > 0) { "Gallery page size must be positive" }
        require(popularTagsPath.startsWith('/')) { "Popular tags path must be relative to the site domain" }
        require(availableSortOrders.isNotEmpty()) { "At least one sort order is required" }
    }
}

internal data class GalleryAdultsSelectors(
    val gallery: String = ".thumb",
    val galleryLink: String = ".inner_thumb a",
    val galleryImage: String = "img",
    val galleryTitle: String = "h2",
    val tagsRoot: String = ".tags_page ul.tags li",
    val detailsTitle: String = "h1.title",
    val detailsTags: String = "div.tags:contains(Tags:) .tag_list",
    val detailsAuthor: String = "ul.artists a.tag_btn",
    val detailsLanguage: String = "div.tags:contains(Languages:) .tag_list a span.tag",
    val detailsChapterUrl: String = "#cover a, .cover a, .left_cover a, .g_thumb a, .gallery_left a, .gt_left a",
    val totalPages: String = ".total_pages, .num-pages, .tp",
    val pageImage: String = "#gimg",
)

private val DEFAULT_AVAILABLE_LOCALES = setOf(
    Locale.ENGLISH,
    Locale.FRENCH,
    Locale.JAPANESE,
    Locale.CHINESE,
    Locale("es"),
    Locale("ru"),
    Locale("ko"),
    Locale.GERMAN,
    Locale("id"),
    Locale.ITALIAN,
    Locale("pt"),
    Locale("tr"),
    Locale("th"),
    Locale("vi"),
)

internal abstract class GalleryAdultsParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    private val siteConfig: GalleryAdultsSiteConfig,
) : PagedMangaParser(context, source, siteConfig.pageSize) {

    override val configKeyDomain = ConfigKey.Domain(siteConfig.domain)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = siteConfig.availableSortOrders

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = siteConfig.supportsMultipleTags,
            isAuthorSearchSupported = siteConfig.supportsAuthorSearch,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableLocales = siteConfig.availableLocales,
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !filter.query.isNullOrEmpty() -> {
                    append("/search/?q=")
                    append(filter.query.urlEncoded())
                    append("&")
                }

                else -> {
                    val tag = filter.tags.oneOrThrowIfMany()
                    val lang = filter.locale
                    if (tag != null && lang != null) {
                        throw IllegalArgumentException(ErrorMessages.FILTER_BOTH_LOCALE_GENRES_NOT_SUPPORTED)
                    }
                    if (tag != null) {
                        append("/tag/")
                        append(tag.key)
                        append("/?")
                    } else if (filter.locale != null) {
                        val locale = filter.locale
                        append("/language/")
                        append(locale?.toLanguagePath())
                        append("/?")
                    } else {
                        append("/?")
                    }
                }
            }
            append("page=")
            append(page)
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    protected val selectGallery: String
        get() = siteConfig.selectors.gallery
    protected val selectGalleryLink: String
        get() = siteConfig.selectors.galleryLink
    protected val selectGalleryImg: String
        get() = siteConfig.selectors.galleryImage
    protected val selectGalleryTitle: String
        get() = siteConfig.selectors.galleryTitle
    protected val selectTitle: String
        get() = siteConfig.selectors.detailsTitle
    protected val selectTag: String
        get() = siteConfig.selectors.detailsTags
    protected val selectAuthor: String
        get() = siteConfig.selectors.detailsAuthor
    protected val selectTotalPage: String
        get() = siteConfig.selectors.totalPages

    private val regexBrackets = Regex("\\[[^]]+]|\\([^)]+\\)")
    private val regexSpaces = Regex("\\s+")

    protected open fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(selectGallery).map { div ->
            val href = div.selectFirstOrThrow(selectGalleryLink).attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                title = div.select(selectGalleryTitle).text().cleanupTitle(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = div.selectFirst(selectGalleryImg)?.src(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    //Tags are deliberately reduced because there are too many and this slows down the application.
    //only the most popular ones are taken.
    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        return coroutineScope {
            (1..3).map { page ->
                async { getTags(page) }
            }
        }.awaitAll().flattenTo(ArraySet(360))
    }

    private suspend fun getTags(page: Int): Set<MangaTag> {
        val url = "https://$domain${siteConfig.popularTagsPath}$page"
        val root = webClient.httpGet(url).parseHtml().selectFirstOrThrow(siteConfig.selectors.tagsRoot)
        return root.parseTags()
    }

    protected open fun Element.parseTags() = select("a").mapToSet {
        val key = it.attr("href").removeSuffix('/').substringAfterLast('/')
        val name = it.html().substringBefore("<")
        MangaTag(
            key = key,
            title = name.toTitleCase(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val urlChapters = doc.selectFirstOrThrow(siteConfig.selectors.detailsChapterUrl).attr("href")
        val tag = doc.selectFirst(siteConfig.selectors.detailsTags)?.parseTags()
        val branch = doc.select(siteConfig.selectors.detailsLanguage).joinToString(separator = " / ") {
            it.html().substringBefore("<")
        }
        val author = doc.selectFirst(siteConfig.selectors.detailsAuthor)?.html()?.substringBefore("<span")
        return manga.copy(
            tags = tag.orEmpty(),
            title = doc.selectFirst(siteConfig.selectors.detailsTitle)?.textOrNull()?.cleanupTitle() ?: manga.title,
            authors = setOfNotNull(author),
            chapters = listOf(
                MangaChapter(
                    id = manga.id,
                    title = manga.title,
                    number = 1f,
                    volume = 0,
                    url = urlChapters,
                    scanlator = null,
                    uploadDate = 0,
                    branch = branch,
                    source = source,
                ),
            ),
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        return parseMangaList(webClient.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml())
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val totalPages = doc.selectFirstOrThrow(siteConfig.selectors.totalPages).text().toInt()
        val rawUrl = chapter.url.removeSuffix("/").substringBeforeLast("/") + "/"
        return (1..totalPages).map {
            val url = "$rawUrl$it/"
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.selectFirstOrThrow(siteConfig.selectors.pageImage).requireSrc()
    }

    protected fun String.cleanupTitle() = replace(regexBrackets, "")
        .replace(regexSpaces, " ")
        .trim()

    protected open fun Locale.toLanguagePath() = getDisplayLanguage(Locale.ENGLISH).lowercase()
}
