package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

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

import tsuki.util.attrAsRelativeUrl
import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.src
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl
import tsuki.util.urlEncoded

import org.jsoup.nodes.Element
import java.util.EnumSet

@MangaSourceParser("WURMZ", "Wurmz", "id")
class Wurmz(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.WURMZ, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("wurmz.net")

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.ADDED,
        SortOrder.ADDED_ASC,
        SortOrder.POPULARITY_TODAY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
        isSearchWithFiltersSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val allGenres = listOf(
            "Fantasy", "Romance", "Drama", "Action", "Comedy", "Shounen", "Adventure", "Shoujo",
            "School", "Slice of Life", "Seinen", "Supernatural", "Historical", "Josei", "Isekai",
            "Webtoons", "Martial Art", "Harem", "Ecchi", "Reincarnation", "Magic", "Mystery",
            "Psychological", "Yaoi", "Mature", "Horror", "Tragedy", "Shounen Ai", "Sci-fi",
            "Demons", "Adaptation", "Gender Bender", "Game", "Yuri", "Shoujo Ai", "Thriller",
            "Sport", "Cooking", "Crime", "Gore", "Royal Family", "Super Power", "Regression",
            "Military", "Royalty", "Vampire", "Office Workers", "Transmigration", "Medical",
            "Time Travel", "Childhood Friends", "Music", "Monsters", "Revenge", "College Life",
            "Villainess", "Kids", "Mecha", "One Shot", "Police", "Omegaverse", "Girls",
            "Reverse Harem", "Animals", "Ghosts", "Project", "Age Gap", "Showbiz", "Violence",
            "Boys", "Shotacon", "Survival", "Beasts", "Bloody", "Crossdressing", "Delinguents",
            "Dungeons", "Gyaru", "Non-human", "Post-Apocalyptic", "Zombies", "Doujinshi",
            "18+", "Bodyswap", "NTR", "Yakuzas", "BDSM", "Lolicon", "Philosophical", "System",
            "Aliens", "Hentai", "Anthology", "Fetish", "Incest", "Virtual Reality", "Dementia",
            "Nakadashi", "Samurai", "4-Koma", "Cheating", "Femdom", "Mafia", "Milf", "Ninja",
            "Cunnilingus", "Guideverse", "Infidelity", "Parodi", "Reverse Isekai", "Villain"
        )

        return MangaListFilterOptions(
            availableTags = allGenres.map { genre ->
                MangaTag(key = genre, title = genre, source = source)
            }.toSet(),
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, order, filter)
        val doc = webClient.httpGet(url).parseHtml()

        val cards = doc.select("article.comic-card")
        if (cards.isEmpty()) {
            return emptyList()
        }
        return cards.mapNotNull { parseMangaCard(it) }
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        return buildString {
            append("https://")
            append(domain)
            append("/semua-komik")

            val params = mutableMapOf<String, String>()

            filter.query?.takeIf { it.isNotEmpty() }?.let { rawQuery ->
                val cleaned = cleanQuery(rawQuery)
                if (cleaned.isNotEmpty()) {
                    params["q"] = cleaned.urlEncoded()
                }
            }

            filter.types.firstOrNull()?.let { type ->
                params["type"] = when (type) {
                    ContentType.MANGA -> "manga"
                    ContentType.MANHWA -> "manhwa"
                    ContentType.MANHUA -> "manhua"
                    else -> ""
                }
            }

            filter.states.firstOrNull()?.let { state ->
                params["status"] = when (state) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "tamat"
                    MangaState.PAUSED -> "hiatus"
                    MangaState.ABANDONED -> "drop"
                    else -> ""
                }
            }

            filter.tags.take(3).forEach { tag ->
                params["genres[]"] = tag.key
            }

            params["sort"] = sortParamForOrder(order)

            if (page > 1) {
                params["page"] = page.toString()
            }

            if (params.isNotEmpty()) {
                append("?")
                append(params.entries.joinToString("&") { (key, value) ->
                    val encodedKey = if (key.endsWith("[]")) key else key.urlEncoded()
                    "$encodedKey=${value.urlEncoded()}"
                })
            }
        }
    }

    private fun cleanQuery(query: String): String {
        return query
            .replace(Regex("Bahasa Indonesia", RegexOption.IGNORE_CASE), "")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun sortParamForOrder(order: SortOrder): String {
        return when (order) {
            SortOrder.UPDATED -> "update"
            SortOrder.ADDED -> "new"
            SortOrder.ADDED_ASC -> "old"
            SortOrder.POPULARITY_TODAY -> "popular_today"
            SortOrder.POPULARITY_WEEK -> "popular_7d"
            SortOrder.POPULARITY_MONTH -> "popular_30d"
            SortOrder.POPULARITY -> "popular_all"
            else -> "update"
        }
    }

    private fun parseMangaCard(card: Element): Manga? {
        val link = card.selectFirst("a[href]") ?: return null
        val href = link.attrAsRelativeUrl("href")
        val titleEl = card.selectFirst("h2.comic-title")
        val title = titleEl?.text() ?: return null
        val coverEl = card.selectFirst("img")
        val coverUrl = coverEl?.src()

        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            altTitles = emptySet(),
            coverUrl = coverUrl,
            authors = emptySet(),
            tags = emptySet(),
            rating = RATING_UNKNOWN,
            state = null,
            contentRating = null,
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val title = doc.selectFirst("h1")?.text() ?: manga.title
        val cover = doc.selectFirst(".cover-frame img")?.src() ?: manga.coverUrl

        val desc = doc.selectFirst(".mt-5 p.text-muted")?.text() ?: ""

        val genreElements = doc.select(".flex.flex-wrap.gap-1.5 .chip")
        val tags = genreElements.mapNotNull { a ->
            val genreName = a.text()
            MangaTag(key = genreName, title = genreName, source = source)
        }.toSet()

        val statusText = doc.selectFirst(".status-badge")?.text()
        val state = when (statusText?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "cancelled" -> MangaState.ABANDONED
            else -> null
        }

        val authorText = doc.selectFirst(".text-xs.text-muted.mt-2.5")?.text()
        val authors = authorText?.substringAfter("Oleh ")?.substringBefore(" ·")?.split('|')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "-?" }
            ?.toSet() ?: emptySet()

        val altText = doc.selectFirst(".text-sm.text-faint.mt-1")?.text()
        val altTitles = altText?.split("·")?.map { it.trim() }?.toSet() ?: emptySet()

        val chapterLinks = doc.select(".chap-cell")
        val chapters = chapterLinks.map { a ->
            val href = a.attrAsRelativeUrl("href")
            val label = a.text().trim()
            val number = label.extractChapterNumber()
            MangaChapter(
                id = generateUid(href),
                url = href,
                title = label,
                number = number,
                volume = 0,
                uploadDate = 0,
                source = source,
                scanlator = null,
                branch = null,
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = title,
            publicUrl = fullUrl,
            coverUrl = cover,
            description = desc,
            tags = tags,
            state = state,
            authors = authors,
            altTitles = altTitles,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val imgElements = doc.select(".reader-page img")
        if (imgElements.isEmpty()) {
            val lazyImgs = doc.select("img[data-src]")
            if (lazyImgs.isNotEmpty()) {
                return lazyImgs.map { img ->
                    val url = img.attr("data-src").toRelativeUrl(domain)
                    MangaPage(id = generateUid(url), url = url, preview = null, source = source)
                }
            }
            throw ParseException("No images found on chapter page", fullUrl)
        }

        return imgElements.map { img ->
            val url = img.attr("src").ifEmpty { img.attr("data-src") }.toRelativeUrl(domain)
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }
}
