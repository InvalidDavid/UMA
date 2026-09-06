package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.mapNotNullToSet
import tsuki.util.parseFailed
import tsuki.util.parseHtml
import tsuki.util.selectFirstOrThrow
import tsuki.util.src
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl
import tsuki.util.urlEncoded

import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("ROCKSMANGA", "Rocks Manga", "ar")
internal class RocksManga(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.ROCKSMANGA, "rocksmanga.com") {

    override val datePattern = "MMMM d, yyyy"
    override val withoutAjax = true
    override val stylePage = ""
    override val selectBodyPage = "div.reading-content"
    override val selectPage = "img"
    override val selectDesc = ".description"
    override val selectGenre = "div.genres-content a"
    override val selectChapter = "ul.scroll-sm li.item"

    override val availableSortOrders: EnumSet<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val pages = page + 1
        val url = buildString {
            append("https://")
            append(domain)

            if (!filter.query.isNullOrEmpty()) {
                // Text search
                if (pages > 1) {
                    append("/page/")
                    append(pages)
                }
                append("/?s=")
                append(filter.query!!.urlEncoded())
                append("&post_type=wp-manga")
            } else {
                // Browse with optional genre / type filter
                val genreTag = filter.tags.firstOrNull()
                val type = filter.types.firstOrNull()

                if (genreTag != null) {
                    append("/manga-genre/")
                    append(genreTag.key)
                    append("/")
                    if (pages > 1) {
                        append("page/")
                        append(pages)
                        append("/")
                    }
                } else if (type != null) {
                    val typeKey = when (type) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        ContentType.COMICS -> "comic"
                        ContentType.ONE_SHOT -> "one-shot"
                        else -> null
                    }
                    if (typeKey != null) {
                        append("/manga-type/")
                        append(typeKey)
                        append("/")
                        if (pages > 1) {
                            append("page/")
                            append(pages)
                            append("/")
                        }
                    }
                } else {
                    // Default browse – only one sort order is available, so hardcode it
                    append("/manga")
                    if (pages > 1) {
                        append("/page/")
                        append(pages)
                    }
                    append("/?m_orderby=latest")
                }
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        if (doc.location().removeSuffix("/").equals("https://$domain", ignoreCase = true)) {
            return emptyList()
        }
        return parseMangaList(doc)
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        val items = doc.select("div.original.card-lg div.unit")
        return items.map { unit ->
            val posterLink = unit.selectFirstOrThrow("a.poster")
            val href = posterLink.attr("href").toRelativeUrl(domain)
            val img = posterLink.selectFirst("img")
            val info = unit.selectFirst("div.info")
            val titleLink = info?.selectFirst("a")
            val title = titleLink?.text()?.trim().orEmpty()

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = img?.src(),
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

        val title = doc.selectFirst("div.info h1")?.text() ?: manga.title
        val coverUrl = doc.selectFirst("div.poster img")?.src() ?: manga.coverUrl
        val description = doc.selectFirst("div.description")?.html()?.takeIf { it.isNotBlank() }

        val metaElements = doc.select("div.meta > div")
        var author: String? = null
        var status: MangaState? = null
        val altTitles = mutableSetOf<String>()

        metaElements.forEach { element ->
            val label = element.selectFirst("span")?.text()?.trim() ?: return@forEach
            val value = element.selectFirst("a")?.text()?.trim() ?: element.ownText().trim()

            when {
                label.contains("المؤلف") || label.contains("الكاتب") -> author = value
                label.contains("الحالة") || label.contains("الوضع") -> {
                    status = when (value.lowercase()) {
                        "مستمر", "مستمرة", "ongoing" -> MangaState.ONGOING
                        "مكتمل", "مكتملة", "completed", "complete" -> MangaState.FINISHED
                        "متوقف", "متوقفة", "hiatus" -> MangaState.PAUSED
                        "ملغي", "ملغية", "cancelled", "dropped" -> MangaState.ABANDONED
                        else -> null
                    }
                }
                label.contains("الأسماء البديلة") || label.contains("أسماء أخرى") -> {
                    value.split(",", "،", ";").forEach { name ->
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) altTitles.add(trimmed)
                    }
                }
            }
        }

        val tags = doc.select("div.genres a, div.tags a").mapNotNullToSet { a ->
            val href = a.attr("href").removeSuffix("/").substringAfterLast("/")
            val name = a.text().trim()
            if (href.isNotEmpty() && name.isNotEmpty()) {
                MangaTag(key = href, title = name, source = source)
            } else null
        }

        val ratingText = doc.selectFirst("div.rating span.score")?.text()
        val rating = ratingText?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN

        val chapters = getChapters(manga, doc)

        val isAdult = doc.selectFirst("div.adult-content") != null ||
                tags.any { it.key in setOf("adult", "mature", "18+", "ecchi", "smut") }

        manga.copy(
            title = title,
            altTitles = manga.altTitles + altTitles,
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            description = description,
            tags = tags,
            state = status,
            authors = setOfNotNull(author),
            rating = rating,
            chapters = chapters,
            contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
            publicUrl = fullUrl,
        )
    }

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale("ar"))
        fun SimpleDateFormat.parseSafeOrZero(date: String): Long =
            try { parse(date)?.time ?: 0L } catch (_: Exception) { 0L }

        return doc.body().select("ul.scroll-sm li.item").map { li ->
            val a = li.selectFirstOrThrow("a")
            val href = a.attr("href").toRelativeUrl(domain)
            val link = href + stylePage

            val chapterNum = a.attr("title").toFloatOrNull() ?: 0f
            val name = "Chapter ${chapterNum.toInt().toString().removeSuffix(".0")}"

            val dateText = li.selectFirst("span.time")?.text().orEmpty()
            val scanlator = li.selectFirst("span.user span")?.text()

            MangaChapter(
                id = generateUid(href),
                url = link,
                title = name,
                number = chapterNum,
                volume = 0,
                branch = null,
                uploadDate = dateFormat.parseSafeOrZero(dateText),
                scanlator = scanlator,
                source = source,
            )
        }.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val container = doc.selectFirst("#ch-images")
            ?: doc.parseFailed("Container #ch-images not found")
        val images = container.select("img.preload-image")

        return images.mapNotNull { img ->
            val url = img.attr("data-src").ifEmpty { img.attr("src") }
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val relativeUrl = url.toRelativeUrl(domain)
            MangaPage(
                id = generateUid(relativeUrl),
                url = relativeUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/").parseHtml()
        val menu = doc.selectFirst("#nav-menu li:contains(التصنيفات) ul")
            ?: return emptySet()
        return menu.select("li a").mapNotNull { a ->
            val href = a.attr("href")
            val slug = href.substringAfter("/manga-genre/").substringBefore("/")
            if (slug.isEmpty()) null
            else MangaTag(key = slug, title = a.text().trim(), source = source)
        }.toSet()
    }
}
