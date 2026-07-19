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
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.attrAsRelativeUrlOrNull
import tsuki.util.attrOrNull
import tsuki.util.attrOrThrow
import tsuki.util.mapChapters
import tsuki.util.mapNotNullToSet
import tsuki.util.parseHtml
import tsuki.util.parseSafe
import tsuki.util.selectFirstOrThrow
import tsuki.util.urlBuilder

import java.text.SimpleDateFormat
import java.util.EnumSet

internal abstract class GalleryParser(
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

    override val availableSortOrders: Set<SortOrder>
        get() = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        val url = urlBuilder().apply {
            when {
                !query.isNullOrEmpty() -> addQueryParameter("search", query)
                filter.tags.isNotEmpty() -> addPathSegments(filter.tags.first().key)
                order == SortOrder.POPULARITY -> addPathSegment("hot")
            }

            addQueryParameter("start", (page*pageSize).toString())
        }.build()

        val content = webClient.httpGet(url).parseHtml()
        return content.select("div.items-row").map { el ->
            val titleEl = el.selectFirstOrThrow("div.page-header a.item-link")
            val relUrl = titleEl.attrOrThrow("href")
            Manga(
                id = generateUid(relUrl),
                url = relUrl,
                title = titleEl.text(),
                altTitles = emptySet(),
                publicUrl = relUrl.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = el.selectFirst("div.item-thumb img")?.attr("src"),
                tags = el.select("div.item-tags > a.tag").mapNotNullToSet { tagEl ->
                    MangaTag(
                        title = tagEl.text(),
                        key = tagEl.attrAsRelativeUrlOrNull("href")
                            ?.removePrefix("/") ?: return@mapNotNullToSet null,
                        source = source,
                    )
                },
                state = MangaState.FINISHED,
                authors = emptySet(),
                largeCoverUrl = null,
                description = null,
                chapters = null,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val content = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val description = content.selectFirst("div.article-info")?.text()?.trim() ?: ""
        val df = SimpleDateFormat("HH:mm dd-MM-yyyy")
        val time = content.selectFirst("div.article-info > small")?.text()?.trim()
        val chapters = content.selectFirst("nav.pagination")?.select("a.pagination-link")
            ?.mapChapters { index, element ->
                val relUrl = element.attrAsRelativeUrl("href")
                MangaChapter(
                    id = generateUid(relUrl),
                    title = null,
                    number = index + 1f,
                    volume = 0,
                    url = relUrl,
                    scanlator = null,
                    uploadDate = df.parseSafe(time),
                    branch = null,
                    source = source,
                )
            }.orEmpty()
        return manga.copy(chapters = chapters, description = description)
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val content = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return content.selectFirstOrThrow("div.article-fulltext").select("p > img").mapNotNull { el ->
            val url = el.attrOrNull("src") ?: return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    protected suspend fun fetchTags(): Set<MangaTag> {
        val root = webClient.httpGet("https://$domain").parseHtml()
        return root.select("div#navbar-main a.navbar-item").map { a ->
            MangaTag(
                title = a.text(),
                key = a.attr("href").removePrefix("/"),
                source = source,
            )
        }.toSet()
    }
}