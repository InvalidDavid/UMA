package org.koitharu.kotatsu.parsers.site.kotatsu.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("UTOON", "UToon", "en")
internal class UToon(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.UTOON, pageSize = 28) {

    override val configKeyDomain = ConfigKey.Domain("utoon.net")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = genreTags,
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {

        val url = buildUrl(page, order, filter)
        val doc = webClient.httpGet(url).parseHtml()

        return doc.select(".agrid .acard").map { it.toManga() }
    }

    private fun buildUrl(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): String {
        return buildString {
            append("https://")
            append(domain)
            append("/manga/")
            if (page > 1) {
                append("page/")
                append(page)
                append("/")
            }
        }.toHttpUrl().newBuilder().apply {

            filter.query?.takeIf { it.isNotBlank() }?.let {
                addQueryParameter("q", it)
            }

            when (order) {
                SortOrder.POPULARITY -> addQueryParameter("orderby", "popular")
                SortOrder.NEWEST -> addQueryParameter("orderby", "new")
                SortOrder.ALPHABETICAL -> addQueryParameter("orderby", "alphabet")
                else -> Unit
            }

            filter.states.firstOrNull()?.let { state ->
                val value = when (state) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    else -> null
                }
                if (value != null) addQueryParameter("status", value)
            }

            filter.tags.firstOrNull()?.key?.let {
                addQueryParameter("genre", it)
            }

        }.build().toString()
    }

    private fun Element.toManga(): Manga {
        val href = attrAsRelativeUrl("href")

        return Manga(
            id = generateUid(href),
            title = selectFirst(".ac-t")?.text().orEmpty(),
            altTitles = emptySet(),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            rating = selectFirst(".ac-rate")
                ?.text()
                ?.toFloatOrNull()
                ?.div(5f) ?: RATING_UNKNOWN,
            contentRating = null,
            coverUrl = selectFirst("img")?.src(),
            tags = emptySet(),
            state = parseState(selectFirst(".ac-status")?.text()),
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        var author: String? = null
        var artist: String? = null
        var state: MangaState? = null

        doc.select(".sinfo-grid .sir").forEach { row ->
            val label = row.selectFirst(".l")?.text()?.trim().orEmpty()
            val value = row.selectFirst(".v")?.text()?.trim().orEmpty()

            when (label.lowercase(Locale.ROOT)) {
                "author" -> author = value.nullIfEmpty()
                "artist" -> artist = value.nullIfEmpty()
                "status" -> state = parseState(value)
            }
        }

        if (state == null) {
            val fallback = doc.select(".hinfo .hi")
                .joinToString(" ") { it.text() }
            state = parseState(fallback)
        }

        val base = manga.copy(
            title = doc.selectFirst(".htitle")?.text() ?: manga.title,
            coverUrl = doc.selectFirst(".poster img")?.src() ?: manga.coverUrl,
            description = doc.selectFirst(".syn")?.text(),
            tags = doc.select(".genres .genre").mapNotNullToSet { el ->
                val title = el.text().trim().nullIfEmpty() ?: return@mapNotNullToSet null
                val key = el.attr("href")
                    .trimEnd('/')
                    .substringAfterLast('/')
                    .nullIfEmpty()
                    ?: return@mapNotNullToSet null

                MangaTag(key = key, title = title, source = source)
            },
            authors = setOfNotNull(author ?: artist),
            state = state,
        )

        val chapters = parseChapters(doc)

        return base.copy(chapters = chapters)
    }

    private fun parseChapters(doc: Document): List<MangaChapter> {
        val script = doc.selectFirst("script:containsData(var CH=)")?.data()
            ?: return emptyList()

        val json = script.substringAfter("var CH=").substringBefore(";").trim()
        val array = runCatching { JSONArray(json) }.getOrNull()
            ?: return emptyList()

        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            if (obj.optBoolean("locked", false)) return@mapNotNull null

            val url = obj.optString("url").nullIfEmpty()
                ?.toRelativeUrl(domain) ?: return@mapNotNull null

            val label = obj.optString("label").nullIfEmpty()
                ?: return@mapNotNull null

            MangaChapter(
                id = generateUid(url),
                title = label,
                number = obj.optDouble("num", 0.0).toFloat(),
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = parseChapterDate(obj.optString("ago")),
                branch = null,
                source = source,
            )
        }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        if (doc.selectFirst(".zx-locked__card") != null) {
            throw IllegalStateException("Locked premium chapter")
        }

        return doc.select("div.reading-content img").mapNotNull { img ->
            val src = img.src() ?: return@mapNotNull null
            MangaPage(
                id = generateUid(src),
                url = src,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    private fun parseState(status: String?): MangaState? = when {
        status.isNullOrBlank() -> null
        status.contains("ongoing", true) -> MangaState.ONGOING
        status.contains("completed", true) -> MangaState.FINISHED
        status.contains("hiatus", true) || status.contains("on hold", true) -> MangaState.PAUSED
        status.contains("cancel", true) -> MangaState.ABANDONED
        else -> null
    }

    private fun parseChapterDate(value: String?): Long {
        val v = value?.lowercase(Locale.ROOT)?.nullIfEmpty() ?: return 0L
        if (!v.contains("ago")) return 0L

        val amount = Regex("\\d+").find(v)?.value?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()

        when {
            "second" in v -> cal.add(Calendar.SECOND, -amount)
            "minute" in v -> cal.add(Calendar.MINUTE, -amount)
            "hour" in v -> cal.add(Calendar.HOUR_OF_DAY, -amount)
            "day" in v -> cal.add(Calendar.DAY_OF_YEAR, -amount)
            "week" in v -> cal.add(Calendar.WEEK_OF_YEAR, -amount)
            "month" in v -> cal.add(Calendar.MONTH, -amount)
            "year" in v -> cal.add(Calendar.YEAR, -amount)
            else -> return 0L
        }

        return cal.timeInMillis
    }

    private val genreTags: Set<MangaTag>
        get() = setOf(
            "fantasy" to "Fantasy",
            "drama" to "Drama",
            "adventure" to "Adventure",
            "action" to "Action",
            "comedy" to "Comedy",
            "shounen" to "Shounen",
            "comic" to "Comic",
            "manhwa" to "Manhwa",
            "fight" to "Fight",
            "magic" to "Magic",
            "supernatural" to "Supernatural",
            "manga" to "Manga",
            "romance" to "Romance",
            "martial-arts" to "Martial Arts",
            "crime" to "Crime",
            "mystery" to "Mystery",
            "isekai" to "Isekai",
            "historical" to "Historical",
            "reincarnation" to "Reincarnation",
            "slice-of-life" to "Slice of Life",
            "manhua" to "Manhua",
        ).mapToSet { (k, t) -> MangaTag(k, t, source) }
}
