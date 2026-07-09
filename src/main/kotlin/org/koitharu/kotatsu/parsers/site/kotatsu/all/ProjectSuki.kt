package org.koitharu.kotatsu.parsers.site.kotatsu.all

import android.util.LruCache
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Calendar

@MangaSourceParser("PROJECTSUKI", "ProjectSuki")
internal class ProjectSuki(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.PROJECTSUKI, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("projectsuki.com")
    private val pagesCache = LruCache<String, List<MangaPage>>(50)
    override val defaultSortOrder: SortOrder = SortOrder.UPDATED

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/")
        .build()

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        return when {
            !filter.query.isNullOrBlank() || !filter.isEmpty() -> search(page, filter)
            order == SortOrder.UPDATED -> parseBookList(
                webClient.httpGet("https://$domain/", getRequestHeaders()).parseHtml()
            )
            else -> parseBookList(
                webClient.httpGet(
                    "https://$domain/browse/${(page - 1).coerceAtLeast(0)}",
                    getRequestHeaders()
                ).parseHtml()
            )
        }
    }

    private suspend fun search(page: Int, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page - 1).coerceAtLeast(0).toString())
            .addQueryParameter("q", filter.query.orEmpty())

        filter.states.firstOrNull()?.let { state ->
            url.addQueryParameter("adv", "1")
            url.addQueryParameter(
                "status",
                when (state) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    MangaState.PAUSED -> "hiatus"
                    MangaState.ABANDONED -> "cancelled"
                    else -> return@let
                }
            )
        }

        return parseBookList(
            webClient.httpGet(url.build(), getRequestHeaders()).parseHtml()
        )
    }

    private fun parseBookList(document: Document): List<Manga> {
        val result = LinkedHashMap<String, Manga>()

        document.select("div.browse, div.item, div.book-item, .row .col-md-3, .row .col-sm-4, .row .col-xs-6")
            .forEach { container ->
                val bookAnchor = container.select("a[href]").firstOrNull { anchor ->
                    anchor.absUrl("href").toBookId() != null
                } ?: return@forEach

                val bookId = bookAnchor.absUrl("href").toBookId() ?: return@forEach
                if (bookId in result) return@forEach

                val title = extractBookTitle(container, bookAnchor, bookId)
                val cover = extractBookCover(container, bookId)
                val url = "/book/$bookId"

                val manga = Manga(
                    id = generateUid(url),
                    title = title,
                    altTitles = emptySet(),
                    url = url,
                    publicUrl = url.toAbsoluteUrl(domain),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = cover,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
                result[bookId] = manga
            }

        return result.values.toList()
    }

    private fun extractBookTitle(container: Element, anchor: Element, bookId: String): String {
        return sequenceOf(
            container.select(".details h4 a[href], h4 a[href], .details a[itemprop=title], a[itemprop=title]")
                .firstOrNull { it.absUrl("href").toBookId() == bookId }
                ?.text(),
            container.select("h1, h2, h3, h4, .title, [itemprop=name]").firstOrNull()?.text(),
            anchor.ownText(),
            anchor.text(),
            container.selectFirst("img[title]")?.attr("title"),
            container.selectFirst("img[alt]")?.attr("alt"),
        ).firstOrNull { it != null && it.isValidBookTitle(bookId) }
            ?.trim()
            ?: bookId
    }

    private fun extractBookCover(container: Element, bookId: String): String {
        return container.select("img").firstNotNullOfOrNull { it.imageSrc() }
            ?: bookThumbnailUrl(bookId)
    }

    private fun String.isValidBookTitle(bookId: String): Boolean {
        val value = trim()
        return value.isNotEmpty() &&
                !value.equals(bookId, ignoreCase = true) &&
                !value.equals("show more", ignoreCase = true) &&
                !value.all(Char::isDigit)
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val document = webClient.httpGet(
            manga.url.toAbsoluteUrl(domain),
            getRequestHeaders()
        ).parseHtml()

        val bookId = manga.url.toBookId() ?: document.location().toBookId() ?: manga.url.substringAfterLast('/')

        val fallbackTimestamp = document.selectFirst("meta[property='og:updated_time']")
            ?.attr("content")
            ?.toLongOrNull()
            ?.times(1000L)

        val details = parseDetailsTable(document)
        val title = document.selectFirst("h2[itemprop=title]")?.text()?.nullIfEmpty()
            ?: document.selectFirst("h2")?.text()?.nullIfEmpty()
            ?: manga.title
        val cover = document.select("img").firstNotNullOfOrNull { it.imageSrc() }?.takeIf { bookId in it }
            ?: manga.coverUrl
            ?: bookThumbnailUrl(bookId)

        val description = buildDescription(document, details)
        val authors = parseAuthors(document)
        val state = parseState(details["status"])
        val genres = parseGenres(document)
        val altTitles = setOfNotNull(details["alt titles"] ?: details["alternative titles"])
        val chapters = parseChapters(document, fallbackTimestamp)

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            altTitles = altTitles,
            authors = authors,
            state = state,
            tags = genres,
            contentRating = ContentRating.SAFE,
            chapters = chapters,
        )
    }

    private fun parseDetailsTable(document: Document): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        document.select("div, li, tr, .row .col-md-6, .col-sm-6").forEach { row ->
            val children = row.children()
            if (children.size < 2) return@forEach
            val key = children[0].text().trim().trim(':').lowercase(Locale.ROOT)
            if (key !in DETAIL_KEYS) return@forEach
            val value = children[1].text().trim().nullIfEmpty() ?: return@forEach
            map[key] = value
        }
        return map
    }

    private fun buildDescription(document: Document, details: Map<String, String>): String? {
        val body = document.selectFirst("#descriptionCollapse")?.wholeText()?.trim()
            ?: document.select(".description").joinToString("\n\n") { it.wholeText().trim() }
                .nullIfEmpty()

        val detailText = details.entries.joinToString("\n") { (key, value) ->
            "${key.replaceFirstChar { it.titlecase(Locale.ROOT) }}: $value"
        }.nullIfEmpty()

        return listOfNotNull(body, detailText).joinToString("\n\n").nullIfEmpty()
    }

    private fun parseAuthors(document: Document): Set<String> {
        return document.select("a[href]").mapNotNullTo(LinkedHashSet()) { anchor ->
            val href = anchor.absUrl("href")
            if ("author=" in href || "artist=" in href) {
                anchor.text().nullIfEmpty()
            } else null
        }
    }

    private fun parseGenres(document: Document): Set<MangaTag> {
        return document.select("a[href]").mapNotNullTo(LinkedHashSet()) { anchor ->
            val url = anchor.absUrl("href").toHttpUrlOrNullSafe() ?: return@mapNotNullTo null
            val segments = url.pathSegments.filter { it.isNotBlank() }
            if (segments.firstOrNull() != "genre") return@mapNotNullTo null
            val key = segments.getOrNull(1)?.nullIfEmpty() ?: return@mapNotNullTo null
            MangaTag(
                key = key,
                title = anchor.text().ifBlank { key }.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) },
                source = source,
            )
        }
    }

    private fun parseState(value: String?): MangaState? {
        return when (value?.trim()?.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled", "canceled" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseChapters(document: Document, fallbackTimestamp: Long? = null): List<MangaChapter> {
        val chaptersMap = LinkedHashMap<String, MangaChapter>()

        val table = document.select("table").firstOrNull { table ->
            table.select("tr").firstOrNull()?.let { row ->
                row.select("td, th").any { cell ->
                    cell.text().trim().matches(Regex("added|date", RegexOption.IGNORE_CASE))
                }
            } ?: false
        }

        if (table != null) {
            val headerRow = table.select("tr").first()
            val headers = headerRow!!.select("td, th").map { it.text().trim().lowercase() }

            val dateCol = headers.indexOfFirst { it == "added" || it == "date" }
            val chapterCol = headers.indexOfFirst { it.matches(Regex("chapter|ch\\.|name|#")) }
                .takeIf { it >= 0 } ?: 0

            val dataRows = table.select("tbody tr").ifEmpty { table.select("tr").drop(1) }
            for (row in dataRows) {
                val cells = row.select("td, th")
                if (cells.size <= maxOf(chapterCol, dateCol)) continue

                val chapterCell = cells[chapterCol]
                val link = chapterCell.selectFirst("a[href]") ?: continue
                val parts = link.absUrl("href").toChapterParts() ?: continue
                val key = "${parts.bookId}/${parts.chapterId}"
                if (key in chaptersMap) continue

                val title = link.text().trim().nullIfEmpty()
                    ?: chapterCell.text().trim().nullIfEmpty()
                val number = parseChapterNumber(title ?: "")

                val dateText = if (dateCol >= 0) cells[dateCol].text().trim() else ""
                val uploadDate = parseChapterDate(dateText) ?: fallbackTimestamp ?: 0L

                chaptersMap[key] = MangaChapter(
                    id = generateUid(key),
                    title = title,
                    number = number,
                    volume = 0,
                    url = "/read/${parts.bookId}/${parts.chapterId}/1",
                    scanlator = null,
                    uploadDate = uploadDate,
                    branch = null,
                    source = source,
                )
            }
        } else {
            document.select("a[href*=/read/]").forEach { anchor ->
                val parts = anchor.absUrl("href").toChapterParts() ?: return@forEach
                val key = "${parts.bookId}/${parts.chapterId}"
                if (key in chaptersMap) return@forEach

                val title = anchor.text().trim().nullIfEmpty()
                val number = parseChapterNumber(title ?: "")
                chaptersMap[key] = MangaChapter(
                    id = generateUid(key),
                    title = title,
                    number = number,
                    volume = 0,
                    url = "/read/${parts.bookId}/${parts.chapterId}/1",
                    scanlator = null,
                    uploadDate = fallbackTimestamp ?: 0L,
                    branch = null,
                    source = source,
                )
            }
        }

        return chaptersMap.values.sortedBy { it.number }
    }

    private fun parseChapterDate(text: String): Long? {
        if (text.isBlank()) return null

        val relativeRegex = Regex("""(\d+)\s+(years?|months?|weeks?|days?|hours?|mins?|minutes?|seconds?|sec)\s+ago""", RegexOption.IGNORE_CASE)
        relativeRegex.matchEntire(text.trim())?.let { match ->
            val number = match.groupValues[1].toInt()
            val unit = match.groupValues[2].lowercase()
            val cal = Calendar.getInstance()
            when {
                unit.startsWith("year") -> cal.add(Calendar.YEAR, -number)
                unit.startsWith("month") -> cal.add(Calendar.MONTH, -number)
                unit.startsWith("week") -> cal.add(Calendar.DAY_OF_MONTH, -number * 7)
                unit.startsWith("day") -> cal.add(Calendar.DAY_OF_MONTH, -number)
                unit.startsWith("hour") -> cal.add(Calendar.HOUR, -number)
                unit.startsWith("min") -> cal.add(Calendar.MINUTE, -number)
                unit.startsWith("sec") -> cal.add(Calendar.SECOND, -number)
            }
            return cal.timeInMillis
        }

        val formats = listOf(
            SimpleDateFormat("MMMM dd, yyyy", Locale.US),
            SimpleDateFormat("MMM dd, yyyy", Locale.US),
            SimpleDateFormat("MMM d, yyyy", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
        )
        for (fmt in formats) {
            try {
                return fmt.parse(text)?.time
            } catch (_: Exception) {}
        }

        return null
    }

    private fun parseChapterNumber(text: String): Float {
        val match = CHAPTER_NUMBER_REGEX.find(text) ?: return 0f
        val main = match.groupValues.getOrNull(1)?.toFloatOrNull() ?: return 0f
        val sub = match.groupValues.getOrNull(2)?.toFloatOrNull() ?: 0f
        return main + if (sub > 0f) sub / 10f.powDigits() else 0f
    }

    private fun Float.powDigits(): Float {
        var value = this
        var divisor = 1f
        while (value >= 1f) {
            divisor *= 10f
            value /= 10f
        }
        return divisor
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val cached = pagesCache.get(chapter.url)
        if (cached != null) return cached

        val parts = chapter.url.toChapterParts() ?: return emptyList()

        val payload = JSONObject()
            .put("bookid", parts.bookId)
            .put("chapterid", parts.chapterId)
            .put("first", true)

        val headers = getRequestHeaders().newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Content-Type", "application/json;charset=UTF-8")
            .build()

        val response = webClient.httpPost(
            "https://$domain/callpage".toHttpUrl(),
            payload,
            headers
        ).parseJson()

        val src = response.optString("src")
        if (src.isEmpty()) return emptyList()

        val base = "https://$domain/images/gallery/"
        val regex = Regex("""$base[^"'\s]+""")

        val seen = HashSet<String>(16)
        val pages = ArrayList<MangaPage>(16)

        regex.findAll(src).forEach { match ->
            val url = match.value
            if (!seen.add(url)) return@forEach

            pages.add(
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            )
        }

        val result = pages.toList()
        pagesCache.put(chapter.url, result)

        return result
    }


    private fun Element.imageSrc(): String? {
        for (attr in IMAGE_ATTRS) {
            val value = attr("abs:$attr").nullIfEmpty() ?: continue
            return value.substringBefore(' ')
        }
        return attributes().firstOrNull { attr ->
            "src" in attr.key && IMAGE_EXTENSIONS.any { ext -> ext in attr.value.lowercase(Locale.ROOT) }
        }?.value?.substringBefore(' ')?.toAbsoluteUrl(domain)
    }

    private fun bookThumbnailUrl(bookId: String): String =
        "https://$domain/images/gallery/$bookId/thumb"

    private fun String.toBookId(): String? {
        val url = toHttpUrlOrNullSafe() ?: return null
        val segments = url.pathSegments.filter { it.isNotBlank() }
        return if (segments.size >= 2 && segments[0].equals("book", ignoreCase = true)) {
            segments[1]
        } else null
    }

    private fun String.toChapterParts(): ChapterParts? {
        val url = runCatching {
            if (startsWith("http")) this else "https://$domain/${trimStart('/')}"
        }.getOrNull()?.toHttpUrlOrNullSafe() ?: return null
        val segments = url.pathSegments.filter { it.isNotBlank() }
        if (segments.size < 3 || !segments[0].equals("read", ignoreCase = true)) return null
        return ChapterParts(segments[1], segments[2])
    }

    private fun String.toHttpUrlOrNullSafe(): HttpUrl? =
        runCatching { toHttpUrl() }.getOrNull()

    private data class ChapterParts(
        val bookId: String,
        val chapterId: String,
    )

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()


    private companion object {
        private val DETAIL_KEYS = setOf(
            "alt titles", "alternative titles", "author", "authors",
            "artist", "artists", "status", "origin", "release year",
            "views", "official", "purchase", "genre", "genres"
        )
        private val IMAGE_ATTRS = arrayOf("src", "data-src", "data-lazy-src", "srcset")
        private val IMAGE_EXTENSIONS = setOf(".jpg", ".png", ".jpeg", ".webp", ".avif", ".tiff")
        private val CHAPTER_NUMBER_REGEX = Regex("""(?:chapter|ch\.?)\s*(\d+)(?:\s*[.,-]\s*(\d+))?""", RegexOption.IGNORE_CASE)
    }
}
