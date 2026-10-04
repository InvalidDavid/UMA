package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.mapNotNullToSet
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.selectLast
import tsuki.util.src
import tsuki.util.textOrNull
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl
import tsuki.util.toTitleCase
import tsuki.util.urlEncoded

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.EnumSet

@MangaSourceParser("TOONILY", "Toonily", "en", ContentType.HENTAI)
internal class Toonily(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.TOONILY, "toonily.com", pageSize = 18) {

    override val listUrl = "search/"
    override val datePattern = "MMM d, yy"
    override val withoutAjax = true
    override val authorSearchSupported = true
    override val selectGenre = "div.genres-content a, div.wp-manga-tags-list a"

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    override suspend fun getFilterOptions() = super.getFilterOptions().copy(
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.ABANDONED,
            MangaState.PAUSED,
        ),
    )

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/search/").parseHtml()
        val tags = doc.select(".genres-dropdown .genre-item").mapNotNullToSet { label ->
            val input = label.selectFirst("input[name='genre[]']") ?: return@mapNotNullToSet null
            val key = input.attr("value")
            val title = label.selectFirst("span")?.text() ?: key.toTitleCase()
            MangaTag(
                key = key,
                title = title,
                source = source,
            )
        }
        return tags.ifEmpty { super.fetchAvailableTags() }
    }

    override suspend fun createMangaTag(a: Element): MangaTag? {
        val title = a.text().removePrefix("#").trim()
        return super.createMangaTag(a)?.copy(title = title.toTitleCase())
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            if (page > 1) {
                append("/search/page/")
                append(page)
                append('/')
            } else {
                append("/search/")
            }

            val queryParams = mutableListOf<String>()

            if (!filter.query.isNullOrEmpty()) {
                queryParams.add("s=${filter.query.urlEncoded()}")
                queryParams.add("post_type=wp-manga")
            }

            if (!filter.author.isNullOrEmpty()) {
                queryParams.add("author=${filter.author.urlEncoded()}")
            }

            filter.tags.forEachIndexed { i, tag ->
                queryParams.add("genre%5B$i%5D=${tag.key.urlEncoded()}")
            }

            filter.states.forEachIndexed { i, state ->
                val value = when (state) {
                    MangaState.ONGOING -> "on-going"
                    MangaState.FINISHED -> "end"
                    MangaState.ABANDONED -> "canceled"
                    MangaState.PAUSED -> "on-hold"
                    else -> null
                }
                if (value != null) {
                    queryParams.add("status%5B$i%5D=$value")
                }
            }

            when (filter.contentRating.oneOrThrowIfMany()) {
                ContentRating.SAFE -> queryParams.add("adult=0")
                ContentRating.ADULT -> queryParams.add("adult=1")
                else -> Unit
            }

            val orderParam = when (order) {
                SortOrder.UPDATED -> "latest"
                SortOrder.POPULARITY -> "views"
                SortOrder.RATING -> "rating"
                SortOrder.NEWEST -> "new-manga"
                SortOrder.ALPHABETICAL -> "alphabet"
                SortOrder.RELEVANCE -> ""
                else -> "latest"
            }
            if (orderParam.isNotEmpty()) {
                queryParams.add("m_orderby=$orderParam")
            }

            if (queryParams.isNotEmpty()) {
                append('?')
                append(queryParams.joinToString("&"))
            }
        }
        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        val items = doc.select(".page-item-detail, div.page-item-detail.manga").ifEmpty {
            doc.select("div.row.c-tabs-item__content")
        }
        return items.mapNotNull { div ->
            val a = div.selectFirst(".post-title a") ?: div.selectFirst(".item-thumb a") ?: div.selectFirst("a") ?: return@mapNotNull null
            val rawHref = a.attr("href").trim()
            if (rawHref.isEmpty()) return@mapNotNull null
            val href = rawHref.toRelativeUrl(domain)
            val title = (div.selectFirst(".post-title a") ?: div.selectFirst(".post-title") ?: div.selectFirst("h3, h4"))?.text().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val rating = (div.selectFirst("#averagerate") ?: div.selectFirst("[property=ratingValue]") ?: div.selectFirst("span.total_votes"))
                ?.text()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = div.selectFirst("img")?.src(),
                title = title,
                altTitles = emptySet(),
                rating = rating,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = null,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val href = doc.selectFirst("head meta[property='og:url']")?.attr("content")?.toRelativeUrl(domain) ?: manga.url

        val chaptersDeferred = if (doc.select(selectTestAsync).isEmpty()) {
            async { loadChapters(href, doc) }
        } else {
            async { getChapters(manga, doc) }
        }

        val titleEl = doc.selectFirst(".post-title h1, h1")
        titleEl?.select(".manga-title-badges")?.remove()
        val title = titleEl?.textOrNull() ?: manga.title

        val desc = doc.select(selectDesc).html()

        val alt = doc.selectFirst(".post-content_item:contains(Alt) .summary-content")?.textOrNull()
            ?: doc.body().select(selectAlt).firstOrNull()?.textOrNull()

        val authors = doc.select(".author-content a, .artist-content a").mapNotNullToSet { it.textOrNull() }

        val tags = doc.body().select(selectGenre).mapNotNullToSet { createMangaTag(it) }

        val stateDiv = doc.selectFirst(selectState)?.selectLast("div.summary-content, .summary-content")
        val state = stateDiv?.let {
            when (it.text().lowercase()) {
                in ongoing -> MangaState.ONGOING
                in finished -> MangaState.FINISHED
                in abandoned -> MangaState.ABANDONED
                in paused -> MangaState.PAUSED
                in upcoming -> MangaState.UPCOMING
                else -> null
            }
        }

        val isAdult = doc.selectFirst(".adult-confirm, .manga-title-badges.adult, .manga-title-badges:contains(18+)") != null ||
                tags.any { it.key.contains("adult", ignoreCase = true) || it.key.contains("mature", ignoreCase = true) }

        val rating = (doc.selectFirst("#averagerate") ?: doc.selectFirst("[property=ratingValue]") ?: doc.selectFirst(".post-total-rating .total_votes"))
            ?.text()?.toFloatOrNull()?.div(5f) ?: manga.rating

        manga.copy(
            title = title,
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            rating = rating,
            tags = tags,
            description = desc,
            altTitles = setOfNotNull(alt),
            authors = authors,
            state = state,
            chapters = chaptersDeferred.await(),
            contentRating = if (isAdult || isNsfwSource) ContentRating.ADULT else ContentRating.SAFE,
        )
    }
}
