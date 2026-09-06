package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.RATING_UNKNOWN
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.MangaState
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded
import tsuki.util.parseHtml
import tsuki.util.extractChapterNumber

import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("THEMANGA", "TheManga", "id")
internal class TheManga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THEMANGA, 24) {

    override val configKeyDomain = ConfigKey.Domain("themanga.site")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

    override val availableSortOrders: EnumSet<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
        isYearSupported = true,
        isAuthorSearchSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GENRES.map { (key, title) ->
            MangaTag(key = key, title = title, source = source)
        }.toSet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, order, filter)
        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val noFilters = filter.query.isNullOrBlank() &&
                filter.tags.isEmpty() &&
                filter.states.isEmpty() &&
                filter.types.isEmpty() &&
                filter.year == 0 &&
                filter.author.isNullOrBlank()

        if (noFilters) {
            return when (order) {
                SortOrder.POPULARITY -> "https://$domain/?q=&sort=popular&page=$page"
                SortOrder.UPDATED -> "https://$domain/?q=&sort=latest_update&page=$page"
                else -> "https://$domain/explore?q=&page=$page"
            }
        }

        return buildString {
            append("https://$domain/explore?")
            val params = mutableListOf<String>()

            filter.query?.takeIf { it.isNotBlank() }?.let {
                params.add("q=${it.urlEncoded()}")
            }

            params.add("page=$page")

            filter.states.firstOrNull()?.let { state ->
                when (state) {
                    MangaState.ONGOING -> params.add("status=ongoing")
                    MangaState.FINISHED -> params.add("status=completed")
                    else -> {}
                }
            }

            filter.tags.firstOrNull()?.let { tag ->
                params.add("genre=${tag.key.urlEncoded()}")
            }

            filter.types.firstOrNull()?.let { type ->
                when (type) {
                    ContentType.MANGA -> params.add("type=manga")
                    ContentType.MANHWA -> params.add("type=manhwa")
                    ContentType.MANHUA -> params.add("type=manhua")
                    else -> {}
                }
            }

            if (filter.year != 0) {
                params.add("year=${filter.year}")
            }

            filter.author?.takeIf { it.isNotBlank() }?.let {
                params.add("author=${it.urlEncoded()}")
            }

            append(params.joinToString("&"))
        }
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("a.card, a.manga-card").mapNotNull { element ->
            val href = element.attr("href")
            val title = element.selectFirst(".card-title")?.text()
                ?: return@mapNotNull null
            val cover = element.selectFirst(".card-cover img, .cover img")?.absUrl("src")

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                authors = emptySet(),
                coverUrl = cover,
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                state = null,
                contentRating = null,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val title = doc.selectFirst(".hero-title")?.text() ?: manga.title
        val cover = doc.selectFirst(".hero-cover img")?.absUrl("src") ?: manga.coverUrl.orEmpty()
        val description = doc.selectFirst(".synopsis-text")?.text().orEmpty()

        val author = doc.selectFirst(".meta-item-label:matchesOwn(^Author$) + .meta-item-value")?.text()
        val artist = doc.selectFirst(".meta-item-label:matchesOwn(^Artist$) + .meta-item-value")?.text()
        val authors = setOfNotNull(author, artist).filter { it.isNotBlank() }.toSet()

        val tags = doc.select(".meta-pill-row .meta-pill").map { it.text() }
            .map { MangaTag(key = it.lowercase(), title = it, source = source) }
            .toSet()

        val typeMeta = doc.selectFirst(".meta-item-label:matchesOwn(^Type$) + .meta-item-value")?.text()
        val allTags = tags.toMutableSet().apply {
            typeMeta?.takeIf { it.isNotBlank() }?.let {
                add(MangaTag(key = it.lowercase(), title = it, source = source))
            }
        }

        val state = when (doc.selectFirst(".hero-status-badge")?.text()?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            else -> null
        }

        val chapterUrl = "$fullUrl?all=1"
        val chapterDoc = webClient.httpGet(chapterUrl).parseHtml()
        val chapters = chapterDoc.select(".chapter-row").mapNotNull { element ->
            val href = element.attr("data-href")
            val name = element.selectFirst(".chapter-title")?.text()
                ?: return@mapNotNull null
            val dateText = element.selectFirst("[data-local-time]")?.attr("data-local-time")
            val date = dateText?.let {
                runCatching { dateFormat.parse(it)?.time ?: 0L }.getOrDefault(0L)
            } ?: 0L

            MangaChapter(
                id = generateUid(href),
                url = href,
                title = name,
                number = name.extractChapterNumber(),
                volume = 0,
                uploadDate = date,
                scanlator = null,
                branch = null,
                source = source,
            )
        }.reversed()

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            authors = authors,
            tags = allTags,
            state = state,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = if (!chapter.url.contains("/chapter/")) {
            // Support old Madara URLs
            val segments = chapter.url.split("/").filter { it.isNotBlank() }
            if (segments.size >= 2) {
                val mangaSlug = segments[0]
                val number = segments[1].removePrefix("chapter-").replace("-", ".")
                val dotIndex = number.indexOf('.')
                val formatted = if (dotIndex >= 0) {
                    number.padEnd(dotIndex + 3, '0')
                } else {
                    "$number.00"
                }
                "https://$domain/manga/$mangaSlug/chapter/$formatted"
            } else {
                chapter.url.toAbsoluteUrl(domain)
            }
        } else {
            chapter.url.toAbsoluteUrl(domain)
        }

        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select("img.page-img").mapIndexed { _, img ->
            val url = img.absUrl("src")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    companion object {
        private val GENRES = listOf(
            "4-koma" to "4-Koma",
            "action" to "Action",
            "adult" to "Adult",
            "adventure" to "Adventure",
            "aliens" to "Aliens",
            "animals" to "Animals",
            "anthology" to "Anthology",
            "comedy" to "Comedy",
            "cooking" to "Cooking",
            "crime" to "Crime",
            "crossdressing" to "Crossdressing",
            "delinquents" to "Delinquents",
            "demon" to "Demon",
            "demons" to "Demons",
            "drama" to "Drama",
            "ecchi" to "Ecchi",
            "fantasy" to "Fantasy",
            "game" to "Game",
            "gender-bender" to "Gender Bender",
            "genderswap" to "Genderswap",
            "ghosts" to "Ghosts",
            "gore" to "Gore",
            "gyaru" to "Gyaru",
            "harem" to "Harem",
            "historical" to "Historical",
            "horror" to "Horror",
            "incest" to "Incest",
            "isekai" to "Isekai",
            "josei" to "Josei",
            "loli" to "Loli",
            "mafia" to "Mafia",
            "magic" to "Magic",
            "magical-girls" to "Magical Girls",
            "martial-art" to "Martial Art",
            "martial-arts" to "Martial Arts",
            "mature" to "Mature",
            "mecha" to "Mecha",
            "medical" to "Medical",
            "military" to "Military",
            "monster" to "Monster",
            "monster-girls" to "Monster Girls",
            "monsters" to "Monsters",
            "music" to "Music",
            "mystery" to "Mystery",
            "ninja" to "Ninja",
            "office-workers" to "Office Workers",
            "oneshot" to "Oneshot",
            "philosophical" to "Philosophical",
            "police" to "Police",
            "psychological" to "Psychological",
            "regression" to "Regression",
            "reincarnation" to "Reincarnation",
            "reverse-harem" to "Reverse Harem",
            "romance" to "Romance",
            "samurai" to "Samurai",
            "school" to "School",
            "school-life" to "School Life",
            "sci-fi" to "Sci-Fi",
            "seinen" to "Seinen",
            "sexual-violence" to "Sexual Violence",
            "shotacon" to "Shotacon",
            "shoujo" to "Shoujo",
            "shoujo-ai" to "Shoujo Ai",
            "shounen" to "Shounen",
            "slice-of-life" to "Slice of Life",
            "smut" to "Smut",
            "sports" to "Sports",
            "super-power" to "Super Power",
            "supernatural" to "Supernatural",
            "survival" to "Survival",
            "suspense" to "Suspense",
            "system" to "System",
            "thriller" to "Thriller",
            "time-travel" to "Time Travel",
            "tragedy" to "Tragedy",
            "urban" to "Urban",
            "vampire" to "Vampire",
            "video-games" to "Video Games",
            "villainess" to "Villainess",
            "virtual-reality" to "Virtual Reality",
            "web-comic" to "Web Comic",
            "webtoons" to "Webtoons",
            "yuri" to "Yuri",
            "zombies" to "Zombies",
        )
    }
}
