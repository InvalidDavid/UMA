package tsuki.site.id

import okhttp3.Headers
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaReaderParser
import tsuki.model.*
import tsuki.util.*

import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet

@MangaSourceParser("KOMIKU", "Komiku", "id")
internal class Komiku(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.KOMIKU, "komiku.org", 10, 10) {

    private val apiDomain = "api.komiku.org"
    override val datePattern = "dd/MM/yyyy"
    override val selectPage = "#Baca_Komik img"
    override val selectTestScript = "script:containsData(thisIsNeverFound)"
    override val listUrl = "/manga/"
    override val selectMangaList = "div.bge"
    override val selectMangaListImg = "div.bgei img"
    override val selectMangaListTitle = "div.kan h3"
    override val selectChapter = "#Daftar_Chapter tr:has(td.judulseries)"
    override val detailsDescriptionSelector = "#Sinopsis > p"

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    private val allGenres = listOf(
        "action" to "Action", "adult" to "Adult", "adventure" to "Adventure",
        "comedy" to "Comedy", "cooking" to "Cooking", "drama" to "Drama",
        "ecchi" to "Ecchi", "fantasy" to "Fantasy", "gender-bender" to "Gender Bender",
        "harem" to "Harem", "historical" to "Historical", "horror" to "Horror",
        "isekai" to "Isekai", "josei" to "Josei", "magic" to "Magic",
        "manga" to "Manga", "manhwa" to "Manhwa", "martial-arts" to "Martial Arts",
        "mature" to "Mature", "mecha" to "Mecha", "medical" to "Medical",
        "military" to "Military", "mystery" to "Mystery", "one-shot" to "One Shot",
        "psychological" to "Psychological", "romance" to "Romance",
        "school-life" to "School Life", "sci-fi" to "Sci-fi", "seinen" to "Seinen",
        "shoujo" to "Shoujo", "shounen" to "Shounen", "slice-of-life" to "Slice of Life",
        "sports" to "Sports", "supernatural" to "Supernatural", "tragedy" to "Tragedy",
        "yaoi" to "Yaoi", "yuri" to "Yuri",
        "academy" to "Academy", "adaptation" to "Adaptation", "apocalypse" to "Apocalypse",
        "beasts" to "Beasts", "blacksmith" to "Blacksmith", "comic" to "Comic",
        "crime" to "Crime", "crossdressing" to "Crossdressing",
        "dark-fantasy" to "Dark Fantasy", "demons" to "Demons",
        "doujinshi" to "Doujinshi", "entertainment" to "Entertainment",
        "game" to "Game", "genderswap" to "Genderswap", "genius" to "Genius",
        "ghosts" to "Ghosts", "gore" to "Gore", "gyaru" to "Gyaru",
        "hentai" to "Hentai", "knight" to "Knight", "long-strip" to "Long Strip",
        "magical-girls" to "Magical Girls", "mangatoon" to "Mangatoon",
        "martial-art" to "Martial Art", "mc-rebirth" to "MC Rebirth",
        "monster" to "Monster", "monster-girls" to "Monster Girls",
        "monsters" to "Monsters", "murim" to "Murim", "music" to "Music",
        "office-workers" to "Office Workers", "oneshot" to "Oneshot",
        "police" to "Police", "regression" to "Regression",
        "reincarnation" to "Reincarnation", "revenge" to "Revenge",
        "school" to "School", "sexual-violence" to "Sexual Violence",
        "shotacon" to "Shotacon", "shoujo-ai" to "Shoujo Ai",
        "shounen-ai" to "Shounen Ai", "slow-life" to "Slow Life",
        "smut" to "Smut", "sport" to "Sport", "strategy" to "Strategy",
        "super-power" to "Super Power", "survival" to "Survival",
        "sword-fight" to "Sword Fight", "sword-master" to "Sword Master",
        "swormanship" to "Swormanship", "system" to "System",
        "thriller" to "Thriller", "trauma" to "Trauma", "vampire" to "Vampire",
        "villainess" to "Villainess", "violence" to "Violence",
        "web-comic" to "Web Comic", "webtoon" to "Webtoon",
        "webtoons" to "Webtoons", "xianxia" to "Xianxia", "xuanhuan" to "Xuanhuan"
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = allGenres.map { (title, key) -> MangaTag(key, title, source) }.toSet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val url = buildString {
            append("https://")
            append(apiDomain)
            if (query.isNotEmpty()) {
                append("/?post_type=manga&s=")
                append(query.urlEncoded())
                if (page > 1) {
                    append("&page=")
                    append(page)
                }
            } else {
                append("/manga/page/")
                append(page)
                append("/")
                val params = mutableListOf<String>()
                val genres = filter.tags.map { it.key }
                genres.getOrNull(0)?.let { params += "genre=$it" }
                genres.getOrNull(1)?.let { params += "genre2=$it" }
                val orderParam = when (order) {
                    SortOrder.UPDATED -> "modified"
                    SortOrder.NEWEST -> "date"
                    SortOrder.POPULARITY -> "meta_value_num"
                    SortOrder.ALPHABETICAL -> "title"
                    else -> null
                }
                if (orderParam != null) params += "orderby=$orderParam"
                filter.types.oneOrThrowIfMany()?.let {
                    when (it) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        else -> null
                    }
                }?.let { params += "tipe=$it" }
                filter.states.oneOrThrowIfMany()?.let {
                    when (it) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "end"
                        else -> null
                    }
                }?.let { params += "statusmanga=$it" }
                if (params.isNotEmpty()) {
                    append("?")
                    append(params.joinToString("&"))
                }
            }
        }
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    override fun parseMangaList(docs: Document): List<Manga> {
        return docs.select(selectMangaList).mapNotNull { element ->
            val a = element.selectFirst("div.bgei a[href*=/manga/]")
                ?: element.selectFirst("a[href*=/manga/]")
                ?: return@mapNotNull null

            val href = a.attr("href")
            val relativeUrl = href.toRelativeUrl(domain)

            val thumbnailUrl = element.selectFirst(selectMangaListImg)?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.substringBeforeLast("?")?.toAbsoluteUrl(domain)

            val title = element.selectFirst(selectMangaListTitle)?.text()
                ?: return@mapNotNull null

            Manga(
                id = generateUid(relativeUrl),
                url = relativeUrl,
                title = title,
                altTitles = emptySet(),
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = thumbnailUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
                description = element.selectFirst("div.kan p")?.text(),
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val chapters = docs.select(selectChapter).mapChapters(reversed = true) { index, element ->
            val a = element.selectFirst("td.judulseries a") ?: return@mapChapters null
            val url = a.attrAsRelativeUrl("href")
            val dateText = element.selectFirst("td.tanggalseries")?.text()
            val date = if (dateText != null && dateText.contains("lalu")) {
                parseRelativeDate(dateText)
            } else {
                SimpleDateFormat(datePattern, sourceLocale).parseSafe(dateText)
            }
            MangaChapter(
                id = generateUid(url),
                title = a.selectFirst("span")?.text() ?: a.text(),
                url = url,
                number = index + 1f,
                volume = 0,
                scanlator = null,
                uploadDate = date,
                branch = null,
                source = source,
            )
        }

        return parseInfo(docs, manga, chapters)
    }

    override suspend fun parseInfo(docs: Document, manga: Manga, chapters: List<MangaChapter>): Manga {
        val tags = docs.select("ul.genre li.genre a, table.inftable a[href*=/genre/]").mapNotNullToSet { element ->
            val href = element.attr("href")
            val genreKey = href.substringAfter("/genre/").substringBefore("/").ifBlank { return@mapNotNullToSet null }
            val genreTitle = element.selectFirst("span[itemprop='genre']")?.text() ?: element.text()
            MangaTag(
                key = genreKey,
                title = genreTitle.toTitleCase(sourceLocale),
                source = source,
            )
        }

        val statusText = docs.selectFirst("table.inftable tr > td:contains(Status) + td")?.text().orEmpty()
        val state = when {
            statusText.contains("Ongoing", true) -> MangaState.ONGOING
            statusText.contains("Completed", true) || statusText.contains("Tamat", true) || statusText.contains("End", true) -> MangaState.FINISHED
            else -> null
        }

        val author = docs.selectFirst("table.inftable tr:has(td:contains(Pengarang)) td:last-child")?.text()
        val altTitle = docs.selectFirst("table.inftable tr:has(td:contains(Judul Indonesia)) td:last-child")?.text()

        val thumbnail = docs.selectFirst("div.ims > img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.substringBeforeLast("?")?.toAbsoluteUrl(domain)

        return manga.copy(
            altTitles = setOfNotNull(altTitle),
            description = docs.selectFirst(detailsDescriptionSelector)?.text() ?: manga.description,
            state = state ?: manga.state,
            authors = setOfNotNull(author),
            tags = tags,
            chapters = chapters,
            coverUrl = thumbnail ?: manga.coverUrl,
        )
    }

    private fun parseRelativeDate(date: String): Long {
        val trimmedDate = date.substringBefore(" lalu").removeSuffix("s").split(" ")
        val calendar = Calendar.getInstance()
        when (trimmedDate[1]) {
            "jam" -> calendar.add(Calendar.HOUR_OF_DAY, -trimmedDate[0].toInt())
            "menit" -> calendar.add(Calendar.MINUTE, -trimmedDate[0].toInt())
            "detik" -> calendar.add(Calendar.SECOND, 0)
        }
        return calendar.timeInMillis
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select(selectPage).mapIndexed { _, img ->
            val src = img.attr("abs:src")
            MangaPage(
                id = generateUid(src),
                url = src,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/")
        .set("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()
}
