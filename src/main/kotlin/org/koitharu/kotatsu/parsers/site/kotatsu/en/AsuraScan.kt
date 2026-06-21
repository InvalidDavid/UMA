package org.koitharu.kotatsu.parsers.site.kotatsu.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.toJSONArrayOrNull
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ASURASCANS", "AsuraComic", "en")
internal class AsuraScansParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ASURASCANS, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("asurascans.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RATING,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags,
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.ABANDONED,
            MangaState.PAUSED,
            MangaState.UPCOMING,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/browse".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())

            if (!filter.query.isNullOrBlank()) {
                addQueryParameter("search", filter.query)
            }

            if (filter.tags.isNotEmpty()) {
                addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
            }

            filter.states.oneOrThrowIfMany()?.let {
                addQueryParameter(
                    "status",
                    when (it) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.ABANDONED -> "dropped"
                        MangaState.PAUSED -> "hiatus"
                        else -> throw IllegalArgumentException("$it not supported")
                    },
                )
            }

            filter.types.oneOrThrowIfMany()?.let {
                addQueryParameter(
                    "types",
                    when (it) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        else -> throw IllegalArgumentException("$it not supported")
                    },
                )
            }

            addQueryParameter(
                "sort",
                when (order) {
                    SortOrder.RATING -> "rating"
                    SortOrder.UPDATED -> ""
                    SortOrder.POPULARITY -> "popular"
                    SortOrder.ALPHABETICAL_DESC -> "desc"
                    SortOrder.ALPHABETICAL -> "asc"
                    else -> "update"
                },
            )
        }.build()
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("#series-grid .series-card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=/comics/]") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = card.selectFirst("img")?.src(),
                title = card.selectFirst("h3")?.text()?.trim().orEmpty(),
                altTitles = emptySet(),
                rating = card.selectFirst("div.absolute.top-2.right-2 span")?.text()?.toFloatOrNull() ?: RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = when (card.select("div.p-3 span").lastOrNull()?.text()?.trim()?.lowercase(Locale.ENGLISH)) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    "hiatus" -> MangaState.PAUSED
                    "dropped" -> MangaState.ABANDONED
                    "coming soon" -> MangaState.UPCOMING
                    else -> null
                },
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    private val availableTags by lazy(LazyThreadSafetyMode.NONE) {
        ASURA_GENRES.mapTo(LinkedHashSet(ASURA_GENRES.size)) { title ->
            MangaTag(
                key = title.toAsuraGenreKey(),
                title = title,
                source = source,
            )
        }
    }

    private val tagMap by lazy(LazyThreadSafetyMode.NONE) {
        availableTags.associateByTo(LinkedHashMap(availableTags.size)) {
            it.title.lowercase(Locale.ENGLISH)
        }
    }

    private val regexDate = """(\d+)(st|nd|rd|th)""".toRegex()
    private val chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val selectTag = doc.select("div[class^=space] > div.flex > button.text-white")
        val tags = selectTag.mapNotNullToSet { element ->
            val title = element.text().trim().nullIfEmpty() ?: return@mapNotNullToSet null
            tagMap[title.lowercase(Locale.ENGLISH)] ?: MangaTag(
                key = title.toAsuraGenreKey(),
                title = title,
                source = source,
            )
        }
        val author = doc.selectFirst("div.grid > div:has(h3:eq(0):containsOwn(Author)) > h3:eq(1)")?.text().orEmpty()
        val cutoffTime = System.currentTimeMillis() - CHAPTER_HIDE_WINDOW_MS
        return manga.copy(
            title = doc.selectFirst("article h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: manga.title,
            altTitles = doc.selectFirst("#alt-titles")
                ?.text()
                .orEmpty()
                .split('•', '\n')
                .mapNotNullToSet { it.trim().nullIfEmpty() },
            description = doc.selectFirst("#description-text")
                ?.html()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("span.font-medium.text-sm")?.text().orEmpty(),
            tags = tags,
            authors = setOf(author),
            chapters = doc.select("a.group[href*=/chapter/]").mapChapters(reversed = true) { i, a ->
                val urlRelative = a.attrAsRelativeUrl("href")
                val titleElement = a.selectFirst("span.font-medium") ?: a.selectFirst("span")
                val chapterLabel = titleElement?.text()?.trim()?.takeIf { it.isNotEmpty() }
                val chapterTitle = a.selectFirst("span.text-sm.text-white\\/50")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val fullTitle = when {
                    chapterLabel != null && chapterTitle != null -> "$chapterLabel - $chapterTitle"
                    chapterLabel != null -> chapterLabel
                    else -> chapterTitle
                }
                val chapterNumber = chapterNumberRegex.find(chapterLabel.orEmpty())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toFloatOrNull()
                    ?: i + 1f
                val dateText = a.selectFirst("span.text-sm.text-white\\/40")
                    ?.text()
                    ?.trim()
                MangaChapter(
                    id = generateUid(urlRelative),
                    title = fullTitle,
                    number = chapterNumber,
                    volume = 0,
                    url = urlRelative,
                    scanlator = null,
                    uploadDate = parseChapterDate(chapterDateFormat, dateText),
                    branch = null,
                    source = source,
                )
            }.filter { chapter ->
                chapter.uploadDate == 0L || chapter.uploadDate <= cutoffTime
            },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        doc.selectFirst("astro-island[component-url*='ChapterReader']")?.attr("props")?.let { props ->
            pageUrlRegex.findAll(props.replace("&quot;", "\""))
                .map { it.groupValues[1] }
                .distinct()
                .toList()
                .takeIf { it.isNotEmpty() }
                ?.let { urls ->
                    return urls.map { url ->
                        MangaPage(
                            id = generateUid(url),
                            url = url,
                            preview = null,
                            source = source,
                        )
                    }
                }
        }

        val scripts = doc.selectOrThrow("script")
        val sb = StringBuilder()
        for (script in scripts) {
            val raw = script.data().substringBetween("self.__next_f.push(", ")", "").trim()
            if (raw.isEmpty()) continue
            val ja = raw.toJSONArrayOrNull() ?: continue
            for (i in 0 until ja.length()) {
                (ja.opt(i) as? String)?.let { sb.append(it) }
            }
        }
        val lines = sb.toString().split('\n')
        val pages = TreeMap<Int, String>()
        for (line in lines) {
            val obj = line.substringAfter(':').toJSONObjectOrNull() ?: continue
            if (obj.has("order") && obj.has("url")) {
                pages[obj.getInt("order")] = obj.getString("url")
            }
        }
        return pages.values.map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun String.toAsuraGenreKey(): String {
        return trim()
            .lowercase(Locale.ENGLISH)
            .replace(asuraGenreKeyRegex, "-")
            .trim('-')
    }

    private fun parseChapterDate(dateFormat: DateFormat, date: String?): Long {
        val value = date?.trim().orEmpty()
        if (value.isEmpty()) return 0L
        val lower = value.lowercase(Locale.ENGLISH)
        return when {
            lower == "last week" -> Calendar.getInstance().apply {
                add(Calendar.WEEK_OF_YEAR, -1)
            }.timeInMillis

            lower == "yesterday" -> Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, -1)
            }.timeInMillis

            lower.endsWith(" ago") -> parseRelativeDate(lower)
            else -> synchronized(dateFormat) {
                dateFormat.parseSafe(value.replace(regexDate, "$1"))
            }
        }
    }

    private fun parseRelativeDate(date: String): Long {
        val number = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        return when {
            WordSet("second", "seconds").anyWordIn(date) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
            WordSet("minute", "minutes", "min", "mins").anyWordIn(date) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
            WordSet("hour", "hours").anyWordIn(date) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
            WordSet("day", "days").anyWordIn(date) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
            WordSet("week", "weeks").anyWordIn(date) -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
            WordSet("month", "months").anyWordIn(date) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
            WordSet("year", "years").anyWordIn(date) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
            else -> 0L
        }
    }

    private companion object {
        private const val CHAPTER_HIDE_WINDOW_MS = 6L * 60L * 60L * 1000L
        private val chapterNumberRegex = Regex("""Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val pageUrlRegex = Regex(""""url":\s*\[0,\s*"([^"]+)"""")
        private val ASURA_GENRES = listOf(
            "Action",
            "Adventure",
            "Comedy",
            "Crazy MC",
            "Demon",
            "Dungeons",
            "Fantasy",
            "Game",
            "Genius MC",
            "Isekai",
            "Magic",
            "Murim",
            "Mystery",
            "Necromancer",
            "Overpowered",
            "Regression",
            "Reincarnation",
            "Revenge",
            "Romance",
            "School Life",
            "Sci-fi",
            "Shoujo",
            "Shounen",
            "System",
            "Tower",
            "Tragedy",
            "Villain",
            "Violence",
        )
        private val asuraGenreKeyRegex = Regex("[^a-z0-9]+")
    }
}