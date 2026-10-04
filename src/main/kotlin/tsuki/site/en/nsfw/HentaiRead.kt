package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.GalleryAdultsParser
import tsuki.network.CommonHeaders

import tsuki.model.ContentRating
import tsuki.model.ContentType
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
import tsuki.model.YEAR_UNKNOWN

import tsuki.util.attrAsRelativeUrl
import tsuki.util.generateUid
import tsuki.util.mapNotNullToSet
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.parseSafe
import tsuki.util.selectFirstOrThrow
import tsuki.util.src
import tsuki.util.toAbsoluteUrl
import tsuki.util.toTitleCase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("HENTAIREAD", "HentaiRead", "en", ContentType.HENTAI)
internal class HentaiRead(context: MangaLoaderContext) :
    GalleryAdultsParser(context, MangaParserSource.HENTAIREAD, "hentairead.com", 24) {

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add(CommonHeaders.REFERER, "https://$domain/")
        .build()

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isAuthorSearchSupported = true,
            isYearSupported = true,
            isTagsExclusionSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = tagCache().values.toSet(),
        availableContentTypes = EnumSet.of(
            ContentType.DOUJINSHI,
            ContentType.HENTAI,
            ContentType.COMICS,
            ContentType.ARTIST_CG,
        ),
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.UPDATED_ASC,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_ASC,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.RATING,
        SortOrder.RATING_ASC,
    )

    override val selectGallery = ".manga-item"
    override val selectGalleryLink = "a.manga-item__link"
    override val selectGalleryTitle = "a.manga-item__link"
    override val selectTitle = ".manga-titles h1"
    override val selectTag = "div.text-primary:contains(Tags:)"
    override val selectAuthor = "div.text-primary:contains(Artist:)"
    override val selectTotalPage = ".chapter-image-item[data-page]"

    private val selectAltTitle = ".manga-titles h2"
    private val selectParody = "div.text-primary:contains(Parody:)"
    private val selectUploadedDate = "div.text-primary:contains(Uploaded:)"
    private val selectDetailsRating = ".rating__current"
    private val selectCover = "#mangaSummary a.image--hover img"


    private var tagCacheRef: Map<String, MangaTag>? = null
    private val tagMutex = Mutex()

    private suspend fun tagCache(): Map<String, MangaTag> = tagMutex.withLock {
        tagCacheRef?.let { return@withLock it }

        val totalTags = runCatching {
            webClient.httpGet("https://$domain/?s=")
                .parseHtml()
                .selectFirst("ul.tags-list[data-tax=manga_tag]")
                ?.attr("data-total")
                ?.toIntOrNull()
        }.getOrNull() ?: DEFAULT_TAG_COUNT

        val tags = LinkedHashMap<String, MangaTag>(totalTags)
        var offset = 0

        while (offset < totalTags) {
            val url = "https://$domain/wp-admin/admin-ajax.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "search_manga_terms")
                .addQueryParameter("search", "")
                .addQueryParameter("taxonomy", "manga_tag")
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("extra_fields", "")
                .addQueryParameter("hide_empty", "1")
                .build()

            val results = runCatching {
                webClient.httpGet(url).parseJson().optJSONArray("results")
            }.getOrNull()

            if (results == null || results.length() == 0) break

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                if (id < 0) continue
                val text = item.optString("text").takeIf { it.isNotEmpty() } ?: continue
                val title = text.toTitleCase()
                tags[title] = MangaTag(title = title, key = id.toString(), source = source)
            }
            offset += results.length()
        }

        tagCacheRef = tags
        tags
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val hasFilters = filter.query != null ||
                filter.tags.isNotEmpty() ||
                filter.types.isNotEmpty() ||
                filter.year != YEAR_UNKNOWN ||
                filter.tagsExclude.isNotEmpty() ||
                !filter.author.isNullOrEmpty()

        val url = if (hasFilters) {
            buildSearchUrl(page, order, filter)
        } else {
            buildBrowseUrl(page, order)
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    private fun buildBrowseUrl(page: Int, order: SortOrder): HttpUrl {
        val builder = "https://$domain/hentai/".toHttpUrl().newBuilder()
        if (page > 1) {
            builder.addPathSegment("page")
            builder.addPathSegment(page.toString())
        }
        applySortOrder(builder, order)
        return builder.build()
    }

    private suspend fun buildSearchUrl(page: Int, order: SortOrder, filter: MangaListFilter): HttpUrl {
        val builder = "https://$domain/".toHttpUrl().newBuilder()
        builder.addPathSegment("page")
        builder.addPathSegment(page.toString())

        builder.addQueryParameter("s", filter.query?.trim().orEmpty())
        builder.addQueryParameter("title-type", "contains")
        builder.addQueryParameter("search-mode", "AND")
        builder.addQueryParameter("release-type", "in")
        builder.addQueryParameter(
            "release",
            if (filter.year != YEAR_UNKNOWN) filter.year.toString() else "",
        )

        val types = filter.types.ifEmpty { setOf(ContentType.DOUJINSHI, ContentType.HENTAI, ContentType.ARTIST_CG, ContentType.COMICS) }
        for (type in types) {
            typeToCategoryId(type)?.let { builder.addEncodedQueryParameter("categories[]", it) }
        }

        filter.author?.takeIf { it.isNotBlank() }?.let { author ->
            getAuthorId(author)?.let { builder.addEncodedQueryParameter("artists[]", it) }
        }

        for (tag in filter.tags) {
            builder.addEncodedQueryParameter("including[]", tag.key)
        }
        for (tag in filter.tagsExclude) {
            builder.addEncodedQueryParameter("excluding[]", tag.key)
        }

        builder.addQueryParameter("pages", "0-1000")
        applySortOrder(builder, order)
        return builder.build()
    }

    private fun applySortOrder(builder: HttpUrl.Builder, order: SortOrder) {
        when (order) {
            SortOrder.UPDATED -> {
                builder.addQueryParameter("sortby", "new")
                builder.addQueryParameter("order", "desc")
            }
            SortOrder.UPDATED_ASC -> {
                builder.addQueryParameter("sortby", "new")
                builder.addQueryParameter("order", "asc")
            }
            SortOrder.POPULARITY -> {
                builder.addQueryParameter("sortby", "views")
                builder.addQueryParameter("order", "desc")
            }
            SortOrder.POPULARITY_ASC -> {
                builder.addQueryParameter("sortby", "views")
                builder.addQueryParameter("order", "asc")
            }
            SortOrder.ALPHABETICAL -> {
                builder.addQueryParameter("sortby", "alphabet")
                builder.addQueryParameter("order", "asc")
            }
            SortOrder.ALPHABETICAL_DESC -> {
                builder.addQueryParameter("sortby", "alphabet")
                builder.addQueryParameter("order", "desc")
            }
            SortOrder.RATING -> {
                builder.addQueryParameter("sortby", "rating")
                builder.addQueryParameter("order", "desc")
            }
            SortOrder.RATING_ASC -> {
                builder.addQueryParameter("sortby", "rating")
                builder.addQueryParameter("order", "asc")
            }
            else -> Unit
        }
    }

    private fun typeToCategoryId(type: ContentType): String? = when (type) {
        ContentType.DOUJINSHI -> "4"
        ContentType.HENTAI -> "52"
        ContentType.ARTIST_CG -> "4798"
        ContentType.COMICS -> "36278"
        else -> null
    }

    private suspend fun getAuthorId(authorName: String): String? {
        val url = "https://$domain/wp-admin/admin-ajax.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "search_manga_terms")
            .addQueryParameter("search", authorName)
            .addQueryParameter("taxonomy", "manga_artist")
            .build()

        val results = runCatching {
            webClient.httpGet(url).parseJson().optJSONArray("results")
        }.getOrNull() ?: return null

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            if (authorName.equals(item.optString("text"), ignoreCase = true)) {
                return item.optString("id").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    override fun parseMangaList(doc: Document): List<Manga> =
        doc.select(selectGallery).map { div ->
            val href = div.selectFirstOrThrow(selectGalleryLink).attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                title = div.selectFirst(selectGalleryTitle)?.text()?.trim().orEmpty(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = div.selectFirst(selectGalleryImg)?.src(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val tags = tagCache()

        val resolvedTags = doc.selectFirst(selectTag)
            ?.parent()
            ?.select("a")
            ?.mapNotNullToSet { link ->
                tags[link.selectFirst("span")?.text()?.toTitleCase()]
            }
            .orEmpty()

        val authors = doc.selectFirst(selectAuthor)
            ?.nextElementSibling()
            ?.parent()
            ?.select("a")
            ?.mapNotNull { it.selectFirst("span")?.text()?.takeIf { s -> s.isNotBlank() } }
            ?.toSet()
            .orEmpty()

        val parody = doc.selectFirst(selectParody)
            ?.nextElementSibling()
            ?.selectFirst("span")
            ?.text()

        val description = if (!parody.isNullOrEmpty() && !parody.equals("Original", ignoreCase = true)) {
            "Parody: $parody"
        } else {
            ""
        }

        val uploadDate = doc.selectFirst(selectUploadedDate)
            ?.nextElementSibling()
            ?.text()

        return manga.copy(
            title = doc.selectFirst(selectTitle)?.text()?.trim().orEmpty(),
            altTitles = doc.selectFirst(selectAltTitle)?.text()
                ?.split("|")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet(),
            contentRating = ContentRating.ADULT,
            largeCoverUrl = doc.selectFirst(selectCover)?.src(),
            tags = resolvedTags,
            rating = doc.selectFirst(selectDetailsRating)
                ?.text()
                ?.toFloatOrNull()
                ?.div(5f)
                ?: RATING_UNKNOWN,
            authors = authors,
            description = description,
            chapters = listOf(
                MangaChapter(
                    id = manga.id,
                    title = manga.title,
                    number = 0f,
                    volume = 0,
                    url = manga.url,
                    scanlator = null,
                    uploadDate = DATE_FORMAT.parseSafe(uploadDate),
                    branch = "English",
                    source = source,
                ),
            ),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select(selectTotalPage).mapNotNull { li ->
            val previewUrl = li.selectFirst("img")?.src() ?: return@mapNotNull null
            val index = li.attr("data-page")
            MangaPage(
                id = generateUid("${chapter.url}#$index"),
                url = previewUrl,
                preview = previewUrl,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        val preview = page.preview ?: page.url
        return preview.replace(PREVIEW_PREFIX, CDN_PREFIX)
    }

    private companion object {
        private const val DEFAULT_TAG_COUNT = 710

        private const val PREVIEW_PREFIX = "https://hencover.xyz/preview/"
        private const val CDN_PREFIX = "https://henread.xyz/"

        private val DATE_FORMAT = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.ENGLISH)
    }
}
