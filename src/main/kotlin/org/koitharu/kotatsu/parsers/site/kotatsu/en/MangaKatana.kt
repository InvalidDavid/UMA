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

private const val two = "?sv=mk"
private const val three = "?sv=3"

@MangaSourceParser("MANGAKATANA", "MangaKatana", "en")
internal class MangaKatana(context: MangaLoaderContext):
    PagedMangaParser(context, MangaParserSource.MANGAKATANA, pageSize = 24) {

    private val baseUrl = "https://mangakatana.com"

    override val configKeyDomain = ConfigKey.Domain("mangakatana.com")

    override val availableSortOrders = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
    )!!

    private val preferredServerKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            two to "First server",
            three to "Second server",
        ),
        defaultValue = two,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(preferredServerKey)
    }

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
        MangaTag("4 koma", "4-koma", source),
        MangaTag("Action", "action", source),
        MangaTag("Adult", "adult", source),
        MangaTag("Adventure", "adventure", source),
        MangaTag("Artbook", "artbook", source),
        MangaTag("Award winning", "award-winning", source),
        MangaTag("Comedy", "comedy", source),
        MangaTag("Cooking", "cooking", source),
        MangaTag("Doujinshi", "doujinshi", source),
        MangaTag("Drama", "drama", source),
        MangaTag("Ecchi", "ecchi", source),
        MangaTag("Erotica", "erotica", source),
        MangaTag("Fantasy", "fantasy", source),
        MangaTag("Gender Bender", "gender-bender", source),
        MangaTag("Gore", "gore", source),
        MangaTag("Harem", "harem", source),
        MangaTag("Historical", "historical", source),
        MangaTag("Horror", "horror", source),
        MangaTag("Isekai", "isekai", source),
        MangaTag("Josei", "josei", source),
        MangaTag("Loli", "loli", source),
        MangaTag("Manhua", "manhua", source),
        MangaTag("Manhwa", "manhwa", source),
        MangaTag("Martial Arts", "martial-arts", source),
        MangaTag("Mecha", "mecha", source),
        MangaTag("Medical", "medical", source),
        MangaTag("Music", "music", source),
        MangaTag("Mystery", "mystery", source),
        MangaTag("One shot", "one-shot", source),
        MangaTag("Overpowered MC", "overpowered-mc", source),
        MangaTag("Psychological", "psychological", source),
        MangaTag("Reincarnation", "reincarnation", source),
        MangaTag("Romance", "romance", source),
        MangaTag("School Life", "school-life", source),
        MangaTag("Sci-fi", "sci-fi", source),
        MangaTag("Seinen", "seinen", source),
        MangaTag("Sexual violence", "sexual-violence", source),
        MangaTag("Shota", "shota", source),
        MangaTag("Shoujo", "shoujo", source),
        MangaTag("Shoujo Ai", "shoujo-ai", source),
        MangaTag("Shounen", "shounen", source),
        MangaTag("Shounen Ai", "shounen-ai", source),
        MangaTag("Slice of Life", "slice-of-life", source),
        MangaTag("Sports", "sports", source),
        MangaTag("Super power", "super-power", source),
        MangaTag("Supernatural", "supernatural", source),
        MangaTag("Survival", "survival", source),
        MangaTag("Time Travel", "time-travel", source),
        MangaTag("Tragedy", "tragedy", source),
        MangaTag("Webtoon", "webtoon", source),
        MangaTag("Yaoi", "yaoi", source),
        MangaTag("Yuri", "yuri", source),
    )

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {

        val url = applyServer(chapter.url)
        val doc = webClient.httpGet(url).parseHtml()

        parseThzq(doc)?.let {
            if (it.isNotEmpty()) return it
        }
        parseHtmlImages(doc)?.let {
            if (it.isNotEmpty()) return it
        }
        val fallbackDoc = webClient.httpGet(chapter.url).parseHtml()
        parseThzq(fallbackDoc)?.let {
            if (it.isNotEmpty()) return it
        }

        return emptyList()
    }

    private fun applyServer(url: String): String {
        val server = config[preferredServerKey]
        return url + server
    }

    private fun parseThzq(doc: Document): List<MangaPage>? {

        val script = doc.select("script")
            .asSequence()
            .map { it.data() }
            .firstOrNull { it.contains("thzq") }
            ?: return null

        val start = script.indexOf("var thzq")
        if (start == -1) return null

        val arrStart = script.indexOf('[', start)
        if (arrStart == -1) return null

        var depth = 0
        var end = -1

        for (i in arrStart until script.length) {
            when (script[i]) {
                '[' -> depth++
                ']' -> {
                    if (--depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }

        if (end == -1) return null

        val raw = script.substring(arrStart, end)

        val urls = ArrayList<String>(32)

        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\'' || raw[i] == '"') {
                val quote = raw[i]
                val startIdx = ++i

                while (i < raw.length && raw[i] != quote) {
                    i++
                }

                if (i > startIdx) {
                    val url = raw.substring(startIdx, i)
                    if (url.startsWith("http")) {
                        urls.add(url)
                    }
                }
            }
            i++
        }

        if (urls.isEmpty()) return null

        return urls.map { url ->
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }

    private fun parseHtmlImages(doc: Document): List<MangaPage>? {

        val pages = doc.select("div.wrap_img img")
            .asSequence()
            .mapNotNull { img ->

                val url = img.attr("data-src")
                    .ifEmpty { img.attr("src") }

                if (url.isEmpty() || !url.startsWith("http")) return@mapNotNull null

                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source
                )
            }
            .toList()

        return if (pages.isEmpty()) null else pages
    }
}
