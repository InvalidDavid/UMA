package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.*

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.EnumSet

@MangaSourceParser("MANGAPILL", "MangaPill", "en")
internal class MangaPill(context: MangaLoaderContext):
    PagedMangaParser(context, MangaParserSource.MANGAPILL, 50) {

    override val configKeyDomain = ConfigKey.Domain("mangapill.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED, MangaState.UPCOMING),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.NOVEL,
            ContentType.DOUJINSHI,
            ContentType.ONE_SHOT,
            ContentType.OTHER,
        ),
    )
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty() || filter.tags.isNotEmpty() || filter.types.isNotEmpty() || filter.states.isNotEmpty()) {
            return fetchSearch(page, filter)
        }
        return when (order) {
            SortOrder.POPULARITY -> fetchPopular()
            SortOrder.UPDATED   -> fetchLatest()
            else                -> fetchSearch(page, filter)
        }
    }

    private suspend fun fetchPopular(): List<Manga> {
        val doc = webClient.httpGet("https://$domain/").parseHtml()
        return doc.select("div:has(h4:contains(Trending)) > .grid > div:not([class])")
            .map { latestUpdatesFromElement(it) }
    }

    private suspend fun fetchLatest(): List<Manga> {
        val doc = webClient.httpGet("https://$domain/chapters").parseHtml()
        return doc.select(".grid > div:not([class])")
            .map { latestUpdatesFromElement(it) }
    }

    private suspend fun fetchSearch(page: Int, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply {
                if (!filter.query.isNullOrEmpty()) addQueryParameter("q", filter.query)
                filter.types.firstOrNull()?.let {
                    addQueryParameter("type", when (it) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        ContentType.NOVEL -> "novel"
                        ContentType.DOUJINSHI -> "doujinshi"
                        ContentType.ONE_SHOT -> "one-shot"
                        ContentType.OTHER -> "oel"
                        else -> ""
                    })
                }
                filter.states.firstOrNull()?.let {
                    addQueryParameter("status", when (it) {
                        MangaState.ONGOING -> "publishing"
                        MangaState.FINISHED -> "finished"
                        MangaState.ABANDONED -> "discontinued"
                        MangaState.PAUSED -> "on hiatus"
                        MangaState.UPCOMING -> "not yet published"
                        else -> ""
                    })
                }
                filter.tags.forEach { tag ->
                    addQueryParameter("genre", tag.key)
                }
            }
            .build()
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".grid > div:not([class])")
            .map { latestUpdatesFromElement(it) }
    }

    private fun latestUpdatesFromElement(element: org.jsoup.nodes.Element): Manga {
        val a = element.selectFirst("a[href^='/manga/']") ?: return Manga(
            id = generateUid(""),
            url = "",
            publicUrl = "",
            title = "",
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
        val href = a.attr("href").removeSuffix("/")
        val title = element.selectFirst("div.line-clamp-2")?.text() ?: ""
        val img = element.selectFirst("img") ?: return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = "",
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
        val cover = img.attr("data-src")
        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            coverUrl = cover,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }


    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val cover = doc.selectFirst("div.container > div:first-child > div:first-child > img")
            ?.attr("data-src") ?: manga.coverUrl
        val altTitle = doc.selectFirst("div.text-sm.text-secondary")?.text()?.nullIfEmpty()
        val description = doc.select("div.container > div:first-child > div:last-child > div:nth-child(2) > p").text()
        val statusText = doc.select("label.text-secondary").firstOrNull { it.text() == "Status" }
            ?.nextElementSibling()?.text()
        val state = when (statusText) {
            "publishing" -> MangaState.ONGOING
            "finished" -> MangaState.FINISHED
            "discontinued" -> MangaState.ABANDONED
            "on hiatus" -> MangaState.PAUSED
            "not yet published" -> MangaState.UPCOMING
            else -> null
        }
        val tags = doc.select("div").firstOrNull {
            it.selectFirst("label.text-secondary")?.text() == "Genres"
        }?.select("a.text-sm.mr-1.text-brand")?.mapNotNullToSet { element ->
            val key = element.attr("href").substringAfter("/search?genre=")
            if (key.isBlank()) null
            else MangaTag(key = key, title = element.text(), source = source)
        } ?: emptySet()

        val chapters = doc.select("div#chapters a").mapNotNull { element ->
            val href = element.attrAsRelativeUrl("href")
            val rawTitle = element.text()
            val chapterNumber = rawTitle.substringAfter("Chapter ").toFloatOrNull() ?: 0f

            MangaChapter(
                id = generateUid(href),
                title = rawTitle,
                url = href,
                number = chapterNumber,
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }.reversed()

        return manga.copy(
            coverUrl = cover ?: manga.coverUrl,
            altTitles = altTitle?.let { setOf(it) } ?: emptySet(),
            description = description.nullIfEmpty(),
            state = state,
            tags = tags,
            chapters = chapters,
        )
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("picture img").map { img ->
            val url = img.attr("data-src")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
    
    private suspend fun fetchTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/search").parseHtml()
        return doc.select("div.m-1 label input").mapNotNull { input ->
            val title = input.attr("value")
            val key = title.replace(" ", "+")
            MangaTag(key = key, title = title, source = source)
        }.toSet()
    }
}
