package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAFOXTEST", "MangaFox TEST", "en")
internal class MangaFox(
    context: MangaLoaderContext
) : PagedMangaParser(
    context,
    MangaParserSource.MANGAFOXTEST,
    pageSize = 24
) {

    private val baseUrl = "fanfox.net"
    private val mobileUrl = "https://m.fanfox.net"
    private val dateFormat = SimpleDateFormat("MMM d,yyyy", Locale.ENGLISH)

    override val configKeyDomain: ConfigKey.Domain =
        ConfigKey.Domain(baseUrl)

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

    override val filterCapabilities =
        MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {

        val url = buildUrl(page, filter, order)
        val doc = webClient.httpGet(url).parseHtml()

        return parseMangaList(doc)
    }

    private fun buildUrl(page: Int, filter: MangaListFilter, order: SortOrder): String {

        val query = filter.query?.trim().orEmpty()
        val tag = filter.tags.firstOrNull()?.key // IMPORTANT FIX

        return when {

            // TAG SEARCH (Fanfox mobile endpoint)
            !tag.isNullOrBlank() && query.isEmpty() ->
                "https://m.fanfox.net/search/cate/$tag"

            // TEXT SEARCH
            query.isNotEmpty() ->
                "https://m.fanfox.net/search?k=${query.urlEncoded()}"

            // DEFAULT DIRECTORY
            else ->
                buildString {
                    append("https://$baseUrl/directory/")
                    if (page > 1) append("$page.html")
                    if (order == SortOrder.UPDATED) append("?latest")
                }
        }
    }
    
    private fun parseMangaList(doc: Document): List<Manga> {

        val nodes = when {
            doc.select("ul.manga-list-1-list li").isNotEmpty() ->
                doc.select("ul.manga-list-1-list li")

            doc.select("ul.manga-list-4-list li").isNotEmpty() ->
                doc.select("ul.manga-list-4-list li")

            doc.select("ul.manga-list-2-list li").isNotEmpty() ->
                doc.select("ul.manga-list-2-list li")

            else ->
                doc.select("li")
        }

        return nodes.mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")

            Manga(
                id = generateUid(href),
                title = a.attr("title").ifBlank { a.text() },
                url = href,
                publicUrl = a.absUrl("href"),
                coverUrl = el.selectFirst("img")?.attr("abs:src"),

                rating = RATING_UNKNOWN,
                contentRating = null,

                tags = emptySet(),
                authors = emptySet(),

                state = null,

                altTitles = emptySet(),
                largeCoverUrl = null,
                description = null,

                source = source
            )
        }
    }
    
    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(baseUrl)).parseHtml()
        val info = doc.selectFirst(".detail-info-right")!!

        val chapters = doc.select("ul.detail-main-list li a").mapNotNull { el ->
            val href = el.attrAsRelativeUrl("href")
            val name = el.selectFirst("p")?.text() ?: el.ownText()

            MangaChapter(
                id = generateUid(href),
                title = name,
                number = extractChapterNumber(name),
                volume = 0,
                url = href,
                uploadDate = parseChapterDate(
                    el.selectFirst(".detail-main-list-main p")?.text()
                ),
                scanlator = null,
                branch = null,
                source = source
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = doc.selectFirst("h1")?.text() ?: manga.title,

            authors = info.select(".detail-info-right-say a")
                .mapToSet { it.text() },

            tags = info.select(".detail-info-right-tag-list a")
                .mapToSet {
                    MangaTag(
                        key = it.text().lowercase(),
                        title = it.text(),
                        source = source
                    )
                },

            description = info.selectFirst("p.fullcontent")?.text(),

            state = parseStatus(info.selectFirst(".detail-info-right-title-tip")?.text().orEmpty()),

            coverUrl = doc.selectFirst(".detail-info-cover-img")?.attr("abs:src"),

            chapters = chapters
        )
    }

    private fun parseStatus(status: String): MangaState? {
        return when {
            status.contains("Ongoing", true) -> MangaState.ONGOING
            status.contains("Completed", true) -> MangaState.FINISHED
            else -> null
        }
    }
    
    private fun extractChapterNumber(name: String): Float {
        return Regex("""(?i)(chapter|ch\.)\s*(\d+(\.\d+)?)""")
            .find(name)
            ?.groupValues
            ?.getOrNull(2)
            ?.toFloatOrNull()
            ?: 0f
    }

    private fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L

        return when {
            "Today" in date -> Calendar.getInstance().timeInMillis
            "Yesterday" in date -> Calendar.getInstance().apply {
                add(Calendar.DATE, -1)
            }.timeInMillis

            else -> runCatching {
                dateFormat.parse(date)?.time ?: 0L
            }.getOrDefault(0L)
        }
    }
    
    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = getTags()
        )
    }

    private fun getTags(): Set<MangaTag> {
        return listOf(
            "action", "adventure", "comedy", "drama", "fantasy",
            "harem", "romance", "school-life", "shounen", "shoujo",
            "seinen", "sci-fi", "slice-of-life", "supernatural",
            "ecchi", "mystery", "psychological", "historical",
            "martial-arts", "mecha", "sports"
        ).map {
            MangaTag(
                key = it,
                title = it.replace("-", " "),
                source = source
            )
        }.toSet()
    }
    
    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val mobilePath = chapter.url.replace("/manga/", "/roll_manga/")
        val url = "$mobileUrl$mobilePath"

        val doc = webClient.httpGet(url).parseHtml()

        return doc.select("#viewer img").map {
            val imgUrl = it.attr("abs:data-original")

            MangaPage(
                id = generateUid(imgUrl),
                url = imgUrl,
                preview = null,
                source = source
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
}
