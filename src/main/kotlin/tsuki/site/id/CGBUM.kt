package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

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

import tsuki.util.attrAsRelativeUrl
import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.src
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded

import org.jsoup.nodes.Element
import java.util.EnumSet

@MangaSourceParser("CGBUM", "CGBUM", "id")
class CGBUM(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.CGBUM, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("cgbum.com")

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,      // latest
        SortOrder.ADDED,        // newest
        SortOrder.ADDED_ASC,    // oldest
        SortOrder.POPULARITY,   // views
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
        isSearchWithFiltersSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = GENRES.map { genre ->
                MangaTag(key = genre, title = genre, source = source)
            }.toSet(),
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.HENTAI
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrBlank()) {
            buildSearchUrl(page, filter.query.urlEncoded())
        } else {
            buildBrowseUrl(page, order, filter)
        }
        val doc = webClient.httpGet(url).parseHtml()

        val cards = doc.select("article.comic-card")
        return if (cards.isEmpty()) emptyList() else cards.mapNotNull { parseMangaCard(it) }
    }

    private fun buildSearchUrl(page: Int, query: String): String {
        return buildString {
            append("https://$domain/cari?q=")
            append(query.urlEncoded().replace("%20", "+"))
            if (page > 1) {
                append("&page=")
                append(page)
            }
        }
    }

    private fun buildBrowseUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        return buildString {
            append("https://$domain/daftar-komik?")
            append("type=")
            filter.types.firstOrNull()?.let { type ->
                append(
                    when (type) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        ContentType.HENTAI -> "pornhwa"
                        else -> {}
                    }
                )
            }
            append("&status=")
            filter.states.firstOrNull()?.let { state ->
                append(
                    when (state) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "tamat"
                        else -> {}
                    }
                )
            }
            append("&sort=")
            append(
                when (order) {
                    SortOrder.UPDATED -> "latest"
                    SortOrder.ADDED -> "newest"
                    SortOrder.ADDED_ASC -> "oldest"
                    SortOrder.POPULARITY -> "views"
                    else -> "latest"
                }
            )
            filter.tags.take(3).forEach { tag ->
                append("&genres%5B%5D=")
                append(tag.key)
            }
            if (page > 1) {
                append("&page=")
                append(page)
            }
        }
    }

    private fun parseMangaCard(card: Element): Manga? {
        val coverLink = card.selectFirst("a.comic-card-cover") ?: return null
        val href = coverLink.attrAsRelativeUrl("href")
        val titleEl = card.selectFirst(".comic-card-title a")
        val title = titleEl?.text() ?: return null
        val coverUrl = card.selectFirst("img")?.src()?.takeIf { it.isNotBlank() }

        val typeBadge = card.selectFirst(".badge-type")?.text() ?: "Unknown"
        val typeTag = when (typeBadge.lowercase()) {
            "manga" -> MangaTag("type:manga", "Manga", source)
            "manhwa" -> MangaTag("type:manhwa", "Manhwa", source)
            "manhua" -> MangaTag("type:manhua", "Manhua", source)
            else -> null
        }

        val isAdult = card.selectFirst(".badge-pornhwa") != null

        val statusText = card.selectFirst(".badge-status")?.text()
        val state = when (statusText?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "tamat" -> MangaState.FINISHED
            else -> null
        }

        val tags = mutableSetOf<MangaTag>()
        if (typeTag != null) tags.add(typeTag)
        if (isAdult) tags.add(MangaTag("adult", "Adult", source))

        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            altTitles = emptySet(),
            coverUrl = coverUrl,
            authors = emptySet(),
            tags = tags,
            rating = RATING_UNKNOWN,
            state = state,
            contentRating = if (isAdult) ContentRating.ADULT else null,
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val title = doc.selectFirst("h1")?.text() ?: manga.title
        val cover = doc.selectFirst(".comic-cover img")?.src() ?: manga.coverUrl

        val desc = doc.selectFirst(".comic-synopsis .synopsis-content")?.text() ?: ""

        val genreElements = doc.select(".comic-genres .genre-pill")
        val tags = genreElements.mapNotNull { a ->
            val genreName = a.text().trim()
            if (genreName.isNotEmpty()) MangaTag(key = genreName, title = genreName, source = source)
            else null
        }.toSet()

        val statusText = doc.selectFirst(".badge-status")?.text()
        val state = when (statusText?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "tamat" -> MangaState.FINISHED
            else -> null
        }

        val isAdult = doc.selectFirst(".badge-pornhwa") != null ||
                tags.any { it.title.equals("adult", ignoreCase = true) }
        val contentRating = if (isAdult) ContentRating.ADULT else null

        val authorRow = doc.select(".meta-row").firstOrNull { it.selectFirst(".meta-label")?.text() == "Author" }
        val author = authorRow?.selectFirst(".meta-value")?.text()?.trim()?.takeIf { it.isNotBlank() && it != "-" }
        val authors = if (author != null) setOf(author) else emptySet()

        val chapterLinks = doc.select(".chapter-grid a.ch-grid-item")
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
            contentRating = contentRating,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val containers = doc.select(".page-container")
        if (containers.isEmpty()) {
            throw ParseException("No image containers found", fullUrl)
        }

        return containers.mapNotNull { container ->
            val url = container.attr("data-url").takeIf { it.isNotBlank() }
                ?: container.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            if (url == null) return@mapNotNull null
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val fullUrl = seed.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val section = doc.selectFirst("section:has(.section-header h2:contains(Baca Juga))")
            ?: return emptyList()

        return section.select("article.comic-card").mapNotNull { card ->
            parseMangaCard(card)
        }
    }

    companion object {
        private val GENRES = listOf(
            "adaptation", "adult", "adultmature", "age gap", "ahegao", "aliens", "anal",
            "animals", "anthology", "bdsm", "beasts", "big ass", "big breast", "big breasts",
            "big penis", "bisexual", "blackmail", "bloody", "blowjob", "body swap", "bodyswap",
            "bondage", "business suit", "cheating", "cheating infidelity", "childhood friends",
            "collar", "college life", "comedy", "condom", "contest winning", "cooking", "crime",
            "cunnilingus", "curse", "dark skin", "defloration", "delinquents", "demon girl",
            "demons", "double penetration", "doujinshi", "drama", "dungeons", "ecchi", "elf",
            "emperor s daughter", "exhibitionism", "fantasy", "femdom", "fetish", "ffm threesome",
            "filming", "fingering", "footjob", "full color", "futanari", "game", "gender bender",
            "genderswap", "ghost", "ghosts", "glasses", "gore", "group", "gyaru", "handjob",
            "harem", "hentai", "historical", "horns", "huge breast", "humiliation",
            "inverted nipples", "isekai", "josei", "josei w", "kemomimi", "lingerie", "lolicon",
            "milf", "magic", "maid", "manhua", "manhwa", "martial arts", "masturbation",
            "mature", "medical", "military", "mind break", "mind control", "mmf threesome",
            "monster girls", "monsters", "music", "mystery", "ntr", "nakadashi", "netorare",
            "non human", "obsessive male lead", "office workers", "omegaverse", "oneshot",
            "paizuri", "police", "pregnant", "psychological", "rape", "regression",
            "reincarnation", "revenge", "reverse harem", "romance", "royal family", "royalty",
            "runaway", "school life", "sci fi", "seinen", "seinen m", "sex toys", "shoujo ai",
            "shoujo g", "shounen ai", "shounen b", "showbiz", "slice of life", "small breast",
            "smut", "space", "sports", "stocking", "story arc", "super power", "supernatural",
            "survival", "thriller", "time travel", "tomboy", "tower climbing", "traditional games",
            "tragedy", "transmigration", "twintails", "unusual pupils", "vampires", "video games",
            "villainess", "violence", "virginity", "virtual reality", "webtoon", "wuxia",
            "yakuzas", "yaoi bl", "yuri gl", "zombies", "action", "adventure", "boys", "girl",
            "hairy", "horror", "manga", "rofan"
        )
    }
}
