package org.koitharu.kotatsu.parsers.site.kotatsu.all

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("PROJECTSUKI", "ProjectSuki")
internal class ProjectSuki(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.PROJECTSUKI, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("projectsuki.com")

    override val defaultSortOrder: SortOrder = SortOrder.UPDATED

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RELEVANCE,
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
            order == SortOrder.UPDATED -> parseBookList(webClient.httpGet("https://$domain/", getRequestHeaders()).parseHtml())
            else -> parseBookList(
                webClient.httpGet(
                    "https://$domain/browse/${(page - 1).coerceAtLeast(0)}",
                    getRequestHeaders(),
                ).parseHtml(),
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
                },
            )
        }

        return parseBookList(webClient.httpGet(url.build(), getRequestHeaders()).parseHtml())
    }

    private fun parseBookList(document: Document): List<Manga> {
        val result = LinkedHashMap<String, Manga>()
        document.select("div.browse:has(a[href]), div.item:has(a[href])").forEach { container ->
            val titleAnchor = container.select(".details h4 a[href], h4 a[href], .details a[itemprop=title][href], a[itemprop=title][href]")
                .firstOrNull { it.absUrl("href").toBookId() != null && it.text().isValidBookTitle(it.absUrl("href").toBookId().orEmpty()) }
            val bookId = titleAnchor?.absUrl("href")?.toBookId()
                ?: container.select("a[href]").firstNotNullOfOrNull { it.absUrl("href").toBookId() }
                ?: return@forEach
            if (bookId in result) {
                return@forEach
            }
            val anchor = titleAnchor ?: container.select("a[href]").firstOrNull { it.absUrl("href").toBookId() == bookId }
            ?: return@forEach
            result[bookId] = parseBookSummary(bookId, container, anchor)
        }
        return result.values.toList()
    }

    private fun parseBookSummary(bookId: String, container: Element, anchor: Element): Manga {
        val title = sequenceOf(
            container.select(".details h4 a[href], h4 a[href], .details a[itemprop=title][href], a[itemprop=title][href]")
                .firstOrNull { it.absUrl("href").toBookId() == bookId }
                ?.text(),
            container.select("h1, h2, h3, h4, .title, [itemprop=name]").firstOrNull()?.text(),
            container.select("a[href]")
                .firstOrNull { it.absUrl("href").toBookId() == bookId && it.select("img").isEmpty() }
                ?.ownText(),
            anchor.ownText(),
            anchor.text(),
            container.selectFirst("img[title]")?.attr("title"),
            container.selectFirst("img[alt]")?.attr("alt"),
            container.text(),
        ).firstOrNull { it.isValidBookTitle(bookId) }
            ?.trim()
            ?: bookId

        val cover = container.select("img").firstNotNullOfOrNull { it.imageSrc() }
            ?: bookThumbnailUrl(bookId)

        val url = "/book/$bookId"
        return Manga(
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
    }

    private fun String?.isValidBookTitle(bookId: String): Boolean {
        val value = this?.trim().orEmpty()
        return value.isNotEmpty() &&
                !value.equals(bookId, ignoreCase = true) &&
                !value.equals("show more", ignoreCase = true) &&
                !value.all(Char::isDigit)
    }

    private val detailsCache = mutableMapOf<String, Manga>()
    private val chapterCache = mutableMapOf<String, List<MangaChapter>>()

    private fun mangaKey(manga: Manga): String {
        return manga.url.toBookId()
            ?: manga.url
    }


    override suspend fun getDetails(manga: Manga): Manga {

        val key = mangaKey(manga)

        detailsCache[key]?.let {
            return it
        }

        val document = webClient
            .httpGet(
                manga.url.toAbsoluteUrl(domain),
                getRequestHeaders(),
            )
            .parseHtml()

        val bookId = manga.url.toBookId()
            ?: document.location().toBookId()
            ?: manga.url.substringAfterLast('/')

        val details = parseDetails(document)

        val result = manga.copy(
            title = document.selectFirst("h2[itemprop=title]")
                ?.text()
                ?.nullIfEmpty()
                ?: document.selectFirst("h2")
                    ?.text()
                    ?.nullIfEmpty()
                ?: manga.title,

            coverUrl = document.select("img[src], img[data-src], img[data-lazy-src]")
                .asSequence()
                .mapNotNull { it.imageSrc() }
                .firstOrNull { bookId in it }
                ?: manga.coverUrl
                ?: bookThumbnailUrl(bookId),

            description = buildDescription(document, details),

            altTitles = setOfNotNull(
                details["alt titles"]
                    ?: details["alternative titles"]
            ),

            authors = parseAuthors(document),

            state = parseState(
                details["status"]
            ),

            tags = parseGenres(document),

            contentRating = ContentRating.SAFE,

            chapters = chapterCache.getOrPut(manga.url) {
                parseChapters(document)
            },
        )

        detailsCache[key] = result

        return result
    }

    private fun buildDescription(document: Document, details: Map<String, String>): String? {
        val body = document.selectFirst("#descriptionCollapse")?.wholeText()?.trim()
            ?: document.select(".description").joinToString("\n\n") { it.wholeText().trim() }.nullIfEmpty()
        val detailText = details.entries.joinToString("\n") { (key, value) ->
            "${key.replaceFirstChar { it.titlecase(Locale.ROOT) }}: $value"
        }.nullIfEmpty()
        return listOfNotNull(body, detailText).joinToString("\n\n").nullIfEmpty()
    }

    private fun parseDetails(document: Document): Map<String, String> {
        val details = LinkedHashMap<String, String>()
        document.select("div, li, tr").forEach { row ->
            val children = row.children()
            if (children.size < 2) return@forEach
            val key = children[0].text().trim().trim(':').lowercase(Locale.ROOT)
            if (key !in DETAIL_KEYS) return@forEach
            val value = children[1].text().trim().nullIfEmpty() ?: return@forEach
            details[key] = value
        }
        return details
    }

    private fun parseAuthors(document: Document): Set<String> {
        return document.select("a[href]").mapNotNullTo(LinkedHashSet()) { anchor ->
            val href = anchor.absUrl("href")
            if ("author=" in href || "artist=" in href) {
                anchor.text().nullIfEmpty()
            } else {
                null
            }
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

    private fun parseChapters(document: Document): List<MangaChapter> {

        val chapters = LinkedHashMap<String, MangaChapter>()

        document.select("a[href*=/read/]")
            .forEach { anchor ->

                val parts = anchor
                    .absUrl("href")
                    .toChapterParts()
                    ?: return@forEach

                if (chapters.containsKey(parts.key)) {
                    return@forEach
                }

                val parentText = anchor.parent()
                    ?.text()
                    .orEmpty()

                val title = anchor.text()
                    .trim()
                    .nullIfEmpty()

                chapters[parts.key] =
                    MangaChapter(
                        id = generateUid(parts.key),

                        title = title,

                        number = parseChapterNumber(
                            title ?: parentText
                        ),

                        volume = 0,

                        url = "/read/${parts.bookId}/${parts.chapterId}/1",

                        scanlator = null,

                        uploadDate =
                            parseChapterDate(parentText)
                                ?.time
                                ?: 0L,

                        branch = null,

                        source = source,
                    )
            }


        return chapters.values
            .sortedBy {
                it.number
            }
    }


    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.toChapterParts() ?: return emptyList()
        val payload = JSONObject()
            .put("bookid", parts.bookId)
            .put("chapterid", parts.chapterId)
            .put("first", "true")
        val headers = getRequestHeaders().newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Content-Type", "application/json;charset=UTF-8")
            .build()
        val root = webClient.httpPost("https://$domain/callpage".toHttpUrl(), payload, headers).parseJson()
        val src = root.optString("src").nullIfEmpty() ?: return emptyList()
        val fragment = Jsoup.parseBodyFragment(src, "https://$domain/")
        val pages = fragment.select("img").mapNotNull { it.imageSrc() }
            .filter { it.toPageNumber() != null }
            .distinct()
            .sortedBy { it.toPageNumber() }

        return pages.mapIndexed { index, url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private fun parseState(value: String?): MangaState? {
        return when (value?.trim()?.lowercase(Locale.ROOT)) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled", "canceled" -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseChapterNumber(value: String): Float {
        val match = CHAPTER_NUMBER_REGEX.find(value) ?: return 0f
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

    private fun parseChapterDate(text: String): Date? {
        RELATIVE_DATE_REGEX.find(text)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: return null
            val unit = match.groupValues[2].lowercase(Locale.ROOT)
            return Calendar.getInstance(Locale.US).apply {
                when {
                    unit.startsWith("year") -> add(Calendar.YEAR, -amount)
                    unit.startsWith("month") -> add(Calendar.MONTH, -amount)
                    unit.startsWith("week") -> add(Calendar.DAY_OF_MONTH, -amount * 7)
                    unit.startsWith("day") -> add(Calendar.DAY_OF_MONTH, -amount)
                    unit.startsWith("hour") -> add(Calendar.HOUR, -amount)
                    unit.startsWith("min") -> add(Calendar.MINUTE, -amount)
                    unit.startsWith("sec") -> add(Calendar.SECOND, -amount)
                }
            }.time
        }
        ABSOLUTE_DATE_REGEX.find(text)?.value?.let { date ->
            return runCatching { dateFormat.parse(date) }.getOrNull()
        }
        return null
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

    private fun bookThumbnailUrl(bookId: String): String = "https://$domain/images/gallery/$bookId/thumb"

    private fun String.toBookId(): String? {
        val url = toHttpUrlOrNullSafe() ?: return null
        val segments = url.pathSegments.filter { it.isNotBlank() }
        return if (segments.size >= 2 && segments[0].equals("book", ignoreCase = true)) {
            segments[1]
        } else {
            null
        }
    }

    private fun String.toChapterParts(): ChapterParts? {
        val url = runCatching {
            if (startsWith("http")) this else "https://$domain/${trimStart('/')}"
        }.getOrNull()?.toHttpUrlOrNullSafe() ?: return null
        val segments = url.pathSegments.filter { it.isNotBlank() }
        if (segments.size < 3 || !segments[0].equals("read", ignoreCase = true)) return null
        return ChapterParts(segments[1], segments[2])
    }

    private fun String.toPageNumber(): UInt? {
        val url = toHttpUrlOrNullSafe() ?: return null
        val segments = url.pathSegments.filter { it.isNotBlank() }
        if (segments.size < 5 || segments[0] != "images" || segments[1] != "gallery") return null
        return segments[4].toUIntOrNull()
    }

    private fun String.toHttpUrlOrNullSafe(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    private data class ChapterParts(
        val bookId: String,
        val chapterId: String,
    ) {
        val key: String = "$bookId/$chapterId"
    }

    private companion object {
        private val DETAIL_KEYS = setOf(
            "alt titles",
            "alternative titles",
            "author",
            "authors",
            "artist",
            "artists",
            "status",
            "origin",
            "release year",
            "views",
            "official",
            "purchase",
            "genre",
            "genres",
        )
        private val IMAGE_ATTRS = arrayOf("src", "data-src", "data-lazy-src", "srcset")
        private val IMAGE_EXTENSIONS = setOf(".jpg", ".png", ".jpeg", ".webp", ".gif", ".avif", ".tiff")
        private val CHAPTER_NUMBER_REGEX =
            Regex(
                """(?:chapter|ch\.?)\s*(\d+)(?:\s*[.,-]\s*(\d+))?""",
                RegexOption.IGNORE_CASE
            )
        private val RELATIVE_DATE_REGEX = Regex("""(\d+)\s+(years?|months?|weeks?|days?|hours?|mins?|minutes?|seconds?|sec)\s+ago""", RegexOption.IGNORE_CASE)
        private val ABSOLUTE_DATE_REGEX = Regex("""[A-Z][a-z]+\s+\d{1,2},\s+\d{4}""")
        private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
    }
}