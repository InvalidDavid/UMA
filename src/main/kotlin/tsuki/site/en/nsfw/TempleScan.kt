package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
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
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.NextJsData

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

import tsuki.Broken
@Broken("no time to fix this")
@MangaSourceParser("TEMPLESCAN", "Temple Scan", "en", ContentType.HENTAI)
internal class TempleScan(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.TEMPLESCAN, 20) {

    override val configKeyDomain = ConfigKey.Domain("templetoons.com")

    override fun getRequestHeaders(): Headers =
        super.getRequestHeaders().newBuilder()
            .set(CommonHeaders.REFERER, "https://$domain/")
            .set(CommonHeaders.ORIGIN, "https://$domain")
            .set(CommonHeaders.USER_AGENT, config[userAgentKey])
            .set(CommonHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .set(CommonHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
            .set(CommonHeaders.SEC_FETCH_DEST, "same-site")
            .set(CommonHeaders.SEC_FETCH_MODE, "no-cors")
            .set(CommonHeaders.SEC_FETCH_SITE, "image")
            .set(CommonHeaders.UPGRADE_INSECURE_REQUESTS, "1")
            .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableContentTypes = emptySet(),
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val doc = webClient.httpGet("https://$domain/comics").parseHtml()

        val list = NextJsData.findLargestArray(doc.outerHtml()) { obj ->
            obj.has("iw7kl7l") && obj.has("ke8ivdg")
        } ?: return parseCards(doc)

        data class Entry(
            val manga: Manga,
            val status: String,
            val title: String,
            val altNames: String?,
            val views: Long,
            val updatedAt: Long,
            val createdAt: Long,
        )

        val entries = buildList {
            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                val slug = obj.optString("ke8ivdg").takeIf { it.isNotBlank() } ?: continue
                val title = obj.optString("iw7kl7l").takeIf { it.isNotBlank() } ?: continue
                val cover = obj.optString("thumbnail").takeIf { it.isNotBlank() }
                val badge = obj.optString("badge").takeIf { it.isNotBlank() }

                add(
                    Entry(
                        manga = Manga(
                            id = generateUid(slug),
                            url = "/comic/$slug",
                            publicUrl = "https://$domain/comic/$slug",
                            title = title,
                            altTitles = emptySet(),
                            coverUrl = cover,
                            authors = emptySet(),
                            state = null,
                            contentRating = null,
                            tags = badge?.let { setOf(MangaTag(it.lowercase(), it, source)) } ?: emptySet(),
                            rating = RATING_UNKNOWN,
                            source = source,
                        ),
                        status = obj.optString("status"),
                        title = title,
                        altNames = obj.optString("alternative_names").takeIf { it.isNotBlank() },
                        views = obj.optLong("total_views"),
                        updatedAt = parseDate(obj.optString("update_chapter")),
                        createdAt = parseDate(obj.optString("created_at")),
                    ),
                )
            }
        }
        if (entries.isEmpty()) return parseCards(doc)

        val byState = filter.states.firstOrNull()?.let { state ->
            val value = when (state) {
                MangaState.ONGOING -> "Ongoing"
                MangaState.FINISHED -> "Completed"
                MangaState.PAUSED -> "Hiatus"
                MangaState.ABANDONED -> "Canceled"
                else -> null
            }
            if (value != null) entries.filter { it.status.equals(value, ignoreCase = true) } else entries
        } ?: entries

        val query = filter.query?.trim()
        val bySearch = if (!query.isNullOrBlank()) {
            byState.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.altNames?.contains(query, ignoreCase = true) == true
            }
        } else byState

        val sorted = when (order) {
            SortOrder.UPDATED -> bySearch.sortedByDescending { it.updatedAt }
            SortOrder.NEWEST -> bySearch.sortedByDescending { it.createdAt }
            SortOrder.POPULARITY -> bySearch.sortedByDescending { it.views }
            SortOrder.ALPHABETICAL -> bySearch.sortedBy { it.title.lowercase() }
            else -> bySearch.sortedByDescending { it.updatedAt }
        }

        val pageSize = 15
        val from = (page - 1) * pageSize
        if (from >= sorted.size) return emptyList()
        val to = minOf(from + pageSize, sorted.size)
        return sorted.subList(from, to).map { it.manga }
    }

    private fun parseCards(doc: Document): List<Manga> {
        val fromGrid = doc.select("div.grid > div")
            .mapNotNull { it.toCardManga() }
            .distinctBy { it.url }
        if (fromGrid.isNotEmpty()) return fromGrid

        return doc.select("a[href*=/comic/]")
            .filter { "/chapter-" !in it.attr("href") }
            .mapNotNull { link ->
                val container = link.closest("div, article, li") ?: link.parent() ?: link
                container.toCardManga()
            }
            .distinctBy { it.url }
    }

    private fun Element.toCardManga(): Manga? {
        val link = if (tagName() == "a" && "/comic/" in attr("href") && "/chapter-" !in attr("href")) {
            this
        } else {
            selectFirst("a[href*=/comic/]")?.takeIf { "/chapter-" !in it.attr("href") }
        } ?: return null

        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null

        val title = selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("[class*=title]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val cover = selectFirst("img")?.let { img ->
            img.absUrl("src")
                .ifBlank { img.absUrl("data-src") }
                .ifBlank { img.absUrl("data-lazy-src") }
                .takeIf { it.isNotBlank() }
        }

        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = "https://$domain$href",
            title = title,
            altTitles = emptySet(),
            coverUrl = cover,
            authors = emptySet(),
            state = null,
            contentRating = null,
            tags = emptySet(),
            rating = RATING_UNKNOWN,
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removePrefix("/comic/").trimEnd('/')
        val doc = webClient.httpGet("https://$domain/comic/$slug").parseHtml()

        val seriesLd = doc.extractComicSeriesLd()

        val genres = seriesLd?.optJSONArray("genre")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }.orEmpty()
        val adult = genres.any { it.equals("+18", ignoreCase = true) }

        val altName = seriesLd?.optString("alternateName")?.takeIf { it.isNotBlank() }
        val authorName = seriesLd?.optJSONObject("author")?.optString("name")?.takeIf { it.isNotBlank() }

        val rawDescription = doc.synopsis()
            ?: seriesLd?.optString("description")?.takeIf { it.isNotBlank() }

        val description = buildString {
            append(rawDescription.orEmpty())
            if (altName != null) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Name: ").append(altName)
            }
        }

        val statusText = doc.select("ul[aria-label=\"Series stats\"] li span")
            .map { it.text().trim() }
            .firstOrNull { text ->
                text.equals("Ongoing", ignoreCase = true) ||
                        text.equals("Completed", ignoreCase = true) ||
                        text.equals("Hiatus", ignoreCase = true) ||
                        text.equals("Canceled", ignoreCase = true) ||
                        text.equals("Dropped", ignoreCase = true)
            }

        val state = when (statusText?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "canceled", "dropped" -> MangaState.ABANDONED
            else -> null
        }

        val chapters = parseChapters(doc, slug)

        return manga.copy(
            title = seriesLd?.optString("name")?.takeIf { it.isNotBlank() } ?: slug,
            altTitles = altName?.let { setOf(it) } ?: emptySet(),
            coverUrl = seriesLd?.optString("image")?.takeIf { it.isNotBlank() } ?: manga.coverUrl,
            description = description,
            authors = authorName?.let { setOf(it) } ?: emptySet(),
            state = state,
            contentRating = if (adult) ContentRating.ADULT else ContentRating.SAFE,
            tags = buildSet {
                if (adult) add(MangaTag("adult", "Adult", source))
                genres.filterNot { it.equals("+18", ignoreCase = true) }.forEach {
                    add(MangaTag(it.lowercase(), it, source))
                }
            },
            chapters = chapters,
        )
    }

    private fun parseChapters(doc: Document, slug: String): List<MangaChapter> {
        parseChaptersFromNextData(doc, slug).takeIf { it.isNotEmpty() }?.let { return it }
        return parseChaptersFromHtml(doc)
    }

    private fun parseChaptersFromNextData(doc: Document, slug: String): List<MangaChapter> {
        val seriesData = NextJsData.findObject(doc.outerHtml(), "ke8ivdg", slug, *arrayOf("fpqwjqw"))
            ?: return emptyList()

        val seasons = seriesData.optJSONArray("fpqwjqw") ?: return emptyList()
        val out = mutableListOf<MangaChapter>()

        for (s in 0 until seasons.length()) {
            val season = seasons.optJSONObject(s) ?: continue
            val chapters = season.optJSONArray("zjb9eix") ?: continue
            for (i in 0 until chapters.length()) {
                val ch = chapters.optJSONObject(i) ?: continue
                val urlSlug = ch.optString("yy1u07t").takeIf { it.isNotBlank() } ?: continue
                val displayName = ch.optString("q8m6vtm").trim().takeIf { it.isNotBlank() } ?: continue
                val subtitle = ch.optString("chapter_title")
                    .takeIf { it.isNotBlank() }
                    ?.takeUnless { it.equals("null", true) || it.equals("undefined", true) }
                val number = ch.optString("index").toFloatOrNull() ?: continue
                val uploadDate = parseDate(ch.optString("created_at"))

                val title = buildString {
                    append(displayName)
                    if (subtitle != null) append(": ").append(subtitle)
                }
                    .replace(Regex("""\s+(?i:null|undefined)\s*$"""), "")
                    .trim()

                val href = "/comic/$slug/$urlSlug"

                out += MangaChapter(
                    id = generateUid(href),
                    title = title,
                    number = number,
                    volume = 0,
                    url = href,
                    uploadDate = uploadDate,
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            }
        }
        return out.distinctBy { it.url }.sortedBy { it.number }
    }

    private fun parseChaptersFromHtml(doc: Document): List<MangaChapter> {
        val links = doc.select("ul#chapter-list li a[href*=chapter-]")
            .filterNot { a ->
                a.select("span").any { span ->
                    span.ownText().trim().equals("Premium", ignoreCase = true)
                }
            }
        if (links.isEmpty()) return emptyList()

        return links.mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val numMatch = Regex("""chapter-([\d.]+)(?:/|$)""").find(href) ?: return@mapNotNull null
            val numStr = numMatch.groupValues[1]
            val number = numStr.toFloatOrNull() ?: return@mapNotNull null

            val title = (a.selectFirst("span.font-semibold")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Chapter $numStr")
                .replace(Regex("""\s+(?i:null|undefined)\s*$"""), "")
                .trim()

            val uploadDate = a.selectFirst("time[datetime]")
                ?.attr("datetime")
                ?.takeIf { it.isNotBlank() }
                ?.let { parseDate(it) }
                ?: 0L

            MangaChapter(
                id = generateUid(href),
                title = title,
                number = number,
                volume = 0,
                url = href,
                uploadDate = uploadDate,
                source = source,
                scanlator = null,
                branch = null,
            )
        }.distinctBy { it.url }.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = "https://$domain${chapter.url}"
        val doc = webClient.httpGet(url).parseHtml()

        return extractPagesFromNextData(doc).map { imageUrl ->
            MangaPage(id = generateUid(imageUrl), url = imageUrl, preview = null, source = source)
        }
    }

    private fun extractPagesFromNextData(doc: Document): List<String> {
        val props = NextJsData.findValue(doc.outerHtml()) { node ->
            node is JSONObject && node.has("k9wavlj")
        } as? JSONObject ?: return emptyList()

        val arr = props.optJSONArray("k9wavlj") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val lower = text.lowercase().trim()

        if (lower.contains("ago")) {
            val num = Regex("""(\d+)""").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: return 0L
            val cal = Calendar.getInstance()
            when {
                lower.contains("second") -> cal.add(Calendar.SECOND, -num)
                lower.contains("minute") -> cal.add(Calendar.MINUTE, -num)
                lower.contains("hour") -> cal.add(Calendar.HOUR, -num)
                lower.contains("day") -> cal.add(Calendar.DAY_OF_MONTH, -num)
                lower.contains("week") -> cal.add(Calendar.DAY_OF_MONTH, -num * 7)
                lower.contains("month") -> cal.add(Calendar.MONTH, -num)
                lower.contains("year") -> cal.add(Calendar.YEAR, -num)
                else -> return 0L
            }
            return cal.timeInMillis
        }

        try {
            return java.time.Instant.parse(text).toEpochMilli()
        } catch (_: Exception) {}

        for (pattern in ABSOLUTE_DATE_PATTERNS) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return fmt.parse(text)?.time ?: continue
            } catch (_: Exception) {}
        }
        return 0L
    }

    private fun Document.extractComicSeriesLd(): JSONObject? =
        select("script[type=application/ld+json]").mapNotNull { script ->
            runCatching { JSONObject(script.data()) }.getOrNull()
        }.firstNotNullOfOrNull { root ->
            when {
                root.optString("@type") == "ComicSeries" -> root
                root.has("@graph") -> root.optJSONArray("@graph")?.let { graph ->
                    (0 until graph.length())
                        .mapNotNull { graph.optJSONObject(it) }
                        .firstOrNull { it.optString("@type") == "ComicSeries" }
                }
                else -> null
            }
        }

    private fun Document.synopsis(): String? =
        selectFirst("#series-synopsis-text")?.let { el ->
            val ps = el.select("p")
            if (ps.isNotEmpty()) ps.joinToString("\n\n") { it.text() } else el.text()
        }?.takeIf { it.isNotBlank() }

    companion object {
        private val ABSOLUTE_DATE_PATTERNS = listOf(
            "MMM d, yyyy",
            "MMMM d, yyyy",
            "yyyy-MM-dd",
            "d MMM yyyy",
            "MM/dd/yyyy",
        )
    }
}
