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

@MangaSourceParser("MANGAKATANATESTTT", "MangaKatana FIXED", "en")
internal class MangaKatana(context: MangaLoaderContext):
    PagedMangaParser(context, MangaParserSource.MANGAKATANATESTTT, pageSize = 24) {

    private val baseUrl = "https://mangakatana.com"

    override val configKeyDomain = ConfigKey.Domain("mangakatana.com")

    override val availableSortOrders = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
    )!!

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = availableTags(),
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.ABANDONED,
            ),
        )
    }

    private fun MangaState.toMKStatus(): Int {
        return when (this) {
            MangaState.ONGOING -> 1
            MangaState.FINISHED -> 2
            MangaState.ABANDONED -> 0
            else -> 1
        }
    }

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true
    )

    private val dateFormat = SimpleDateFormat("MMM-dd-yyyy", Locale.US)

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {

        val url = buildUrl(page, order, filter)
        val doc = webClient.httpGet(url).parseHtml()

        return doc.select("div#book_list > div.item").map { element ->

            val link = element.selectFirst("div.text > h3 > a")!!
            val thumb = element.selectFirst("img")!!

            Manga(
                id = generateUid(link.absUrl("href")),
                title = link.ownText().ifEmpty { link.text() },
                url = link.absUrl("href"),
                publicUrl = link.absUrl("href"),

                coverUrl = thumb.absUrl("src"),

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

    private fun buildUrl(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): String {

        val query = filter.query?.trim().orEmpty()
        val tags = filter.tags.map { it.key }.filter { it.isNotBlank() }

        val status = filter.states.firstOrNull()

        return when {

            query.isNotEmpty() -> "$baseUrl/page/$page?search=${query.urlEncoded()}&search_by=m_name"

            status != null -> {
                "$baseUrl/genres/?" +
                        "filter=1" +
                        "&include_mode=and" +
                        "&bookmark_opts=off" +
                        "&chapters=1" +
                        "&order=latest" +
                        "&status=${status.toMKStatus()}" +
                        "&page=$page"
            }

            tags.isNotEmpty() ->
                "$baseUrl/manga/page/$page?filter=1&include=${tags.joinToString("_")}"

            else -> when (order) {
                SortOrder.NEWEST -> "$baseUrl/new-manga?page=$page"
                SortOrder.UPDATED -> "$baseUrl/latest/page/$page"
                else -> "$baseUrl/latest/page/$page"
            }
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {

        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(baseUrl)).parseHtml()

        val chapters = doc.select("tr:has(.chapter)")
            .mapNotNull { tr ->
                val a = tr.selectFirst("a") ?: return@mapNotNull null
                val href = a.absUrl("href")

                if (href.isBlank() || href == "#" || !href.startsWith("http")) return@mapNotNull null

                MangaChapter(
                    id = generateUid(href),
                    title = a.text(),
                    number = extractChapterNumber(href),
                    volume = 0,
                    url = href,
                    uploadDate = parseDate(tr.selectFirst(".update_time")?.text()),
                    scanlator = null,
                    branch = null,
                    source = source
                )
            }
            .sortedBy { it.number }

        return manga.copy(
            title = doc.selectFirst("h1")?.text()
                ?: doc.selectFirst("h1.heading")?.text()
                ?: manga.title,

            authors = doc.select(".author").eachText().toSet(),

            tags = doc.select(".genres a").map {
                MangaTag(
                    key = it.text().lowercase(),
                    title = it.text(),
                    source = source
                )
            }.toSet(),

            description = buildString {
                append(doc.select(".summary > p").text())
                val alt = doc.select(".alt_name").text()
                if (alt.isNotBlank()) {
                    append("\n\nAlt name(s): $alt")
                }
            }.trim(),

            state = parseStatus(doc.select(".value.status").text()),

            coverUrl = doc.select("div.media div.cover img").attr("abs:src"),
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

    private fun extractChapterNumber(url: String): Float {
        return Regex("""/c(\d+(\.\d+)?)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
            ?: 0f
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return runCatching {
            dateFormat.parse(text)?.time ?: 0L
        }.getOrDefault(0L)
    }


    private fun availableTags() = setOf(
        MangaTag("action", "Action", source),
        MangaTag("adult", "Adult", source),
        MangaTag("adventure", "Adventure", source),
        MangaTag("comedy", "Comedy", source),
        MangaTag("drama", "Drama", source),
        MangaTag("fantasy", "Fantasy", source),
        MangaTag("horror", "Horror", source),
        MangaTag("mystery", "Mystery", source),
        MangaTag("romance", "Romance", source),
        MangaTag("school-life", "School Life", source),
        MangaTag("shounen", "Shounen", source),
        MangaTag("slice-of-life", "Slice of Life", source),
        MangaTag("supernatural", "Supernatural", source),
    )

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        parseJsImageArray(doc)?.let { return it }
        return parseHtmlImages(doc)
    }

    private fun parseJsImageArray(doc: Document): List<MangaPage>? {

        val script = doc.select("script")
            .firstOrNull { it.data().contains("data-src") }
            ?.data()
            ?: return null

        val arrayName = Regex("""data-src['"]\s*,\s*(\w+)""")
            .find(script)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        val startKey = "var $arrayName"
        val startIndex = script.indexOf(startKey)
        if (startIndex == -1) return null

        val arrayStart = script.indexOf('[', startIndex)
        if (arrayStart == -1) return null

        var depth = 0
        var endIndex = -1

        for (i in arrayStart until script.length) {
            when (script[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        endIndex = i
                        break
                    }
                }
            }
        }

        if (endIndex == -1) return null

        val arrayContent = script.substring(arrayStart, endIndex)

        val images = Regex("""'([^']*)'""")
            .findAll(arrayContent)
            .map { it.groupValues[1] }
            .filter { it.startsWith("http") }
            .toList()

        if (images.isEmpty()) return null

        return images.map {
            MangaPage(
                id = generateUid(it),
                url = it,
                preview = null,
                source = source
            )
        }
    }

    private fun parseHtmlImages(doc: Document): List<MangaPage> {
        return doc.select("#imgs img[data-src], #imgs img[src]").mapNotNull { img ->
            val url = img.attr("data-src").ifBlank { img.attr("src") }

            if (url.isBlank() || !url.startsWith("http")) return@mapNotNull null

            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
