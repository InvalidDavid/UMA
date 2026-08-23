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

import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("BACAMI", "Bacami", "id")
internal class Bacami(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.BACAMI, 20) {

    override val configKeyDomain = ConfigKey.Domain("v1.bacami.site")

    private val dateFormat = SimpleDateFormat("dd MMMM, yyyy", Locale.ENGLISH)

    override val availableSortOrders: EnumSet<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
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
        val url = filter.query
            ?.takeIf { it.isNotBlank() }
            ?.let { q ->
                val cleaned = q.replace("Bahasa Indonesia", "").trim()
                "https://$domain/search/${cleaned.urlEncoded()}/page/$page/"
            }
            ?: buildString {
                append("https://$domain/custom-search/")

                if (filter.tags.isNotEmpty()) {
                    append("genre/")
                    append(filter.tags.first().key)
                    append("/")
                }

                filter.states.firstOrNull()?.let { state ->
                    when (state) {
                        MangaState.ONGOING -> append("status/hot/")
                        MangaState.FINISHED -> append("status/tamat/")
                        else -> {}
                    }
                }

                filter.types.firstOrNull()?.let { type ->
                    when (type) {
                        ContentType.MANGA -> append("type/manga/")
                        ContentType.MANHWA -> append("type/manhwa/")
                        ContentType.MANHUA -> append("type/manhua/")
                        else -> {}
                    }
                }

                when (order) {
                    SortOrder.POPULARITY -> append("orderby/score/")
                    SortOrder.UPDATED -> append("orderby/latest/")
                    SortOrder.ALPHABETICAL -> append("orderby/name/")
                    else -> append("orderby/latest/")
                }

                append("page/")
                append(page)
                append("/")
            }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("article.genre-card").mapNotNull { element ->
            val link = element.selectFirst("div.genre-cover > a") ?: return@mapNotNull null
            val title = element.selectFirst("div.genre-info > a")?.text() ?: return@mapNotNull null
            val href = link.attr("href")
            val cover = element.selectFirst("div.genre-cover > a > img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.toAbsoluteUrl(domain)

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

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val content = doc.selectFirst("#komik > section.manga-content")

        val title = content?.selectFirst("header > h1")?.text() ?: manga.title
        val cover = content?.selectFirst("figure .image-wrap img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.toAbsoluteUrl(domain) ?: manga.coverUrl.orEmpty()

        val description = content?.selectFirst("p.manga-description")?.text().orEmpty()
        val altTitle = content?.selectFirst("p.manga-altname")?.text().orEmpty()
        val fullDescription = if (altTitle.isNotBlank()) {
            "$description\n\nAlternative Title: $altTitle".trim()
        } else {
            description
        }

        val author = content?.selectFirst(".info-item:contains(Author) .info-value")?.text()
            ?: content?.selectFirst("div > div > div:nth-child(3) > span.info-value")?.text()
            ?: ""

        val tags = content?.select("nav > span > a")?.map { it.text() }
            ?.map { MangaTag(key = it.lowercase(), title = it, source = source) }
            ?.toSet().orEmpty()

        val state = when {
            doc.selectFirst(".hot-tag, .project-tag") != null -> MangaState.ONGOING
            doc.selectFirst(".tamat-tag") != null -> MangaState.FINISHED
            else -> null
        }

        val chapters = doc.select("ol.chapter-list > li").mapNotNull { element ->
            val link = element.selectFirst("a.ch-link") ?: return@mapNotNull null
            val href = link.attr("href")
            val name = link.text().substringAfter("–").trim()
            val date = element.selectFirst("span.ch-date")?.text()

            MangaChapter(
                id = generateUid(href),
                url = href,
                title = name,
                number = name.extractChapterNumber(),
                volume = 0,
                uploadDate = parseDate(date),
                scanlator = null,
                branch = null,
                source = source,
            )
        }.reversed()

        manga.copy(
            title = title,
            coverUrl = cover,
            description = fullDescription,
            authors = setOfNotNull(author.takeIf { it.isNotBlank() }),
            tags = tags,
            state = state,
            chapters = chapters,
        )
    }

    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return runCatching { dateFormat.parse(date)?.time ?: 0L }.getOrDefault(0L)
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val scriptContent = doc.selectFirst("script:containsData(imageUrls)")?.data()
            ?: return emptyList()

        val jsonString = scriptContent.substringAfter("imageUrls:").substringBefore("],").plus("]")
        val jsonArray = JSONArray(jsonString)

        return (0 until jsonArray.length()).map { i ->
            val url = jsonArray.getString(i)
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
            "action-2" to "Action",
            "adult" to "Adult",
            "adventure" to "Adventure",
            "apocalypse" to "Apocalypse",
            "comedy" to "Comedy",
            "comedy-mystery-romance-slice-of-life-supernatural" to "Comedy Mystery Romance Slice Of Life Supernatural",
            "comedy-romance-slice-of-life" to "Comedy Romance Slice Of Life",
            "cooking" to "Cooking",
            "crime" to "Crime",
            "cultivation" to "Cultivation",
            "demons" to "Demons",
            "doujinshi" to "Doujinshi",
            "drama" to "Drama",
            "ecchi" to "Ecchi",
            "fantasy" to "Fantasy",
            "furry" to "Furry",
            "game" to "Game",
            "gender-bender" to "Gender Bender",
            "genius" to "Genius",
            "gore" to "Gore",
            "harem" to "Harem",
            "hentai" to "Hentai",
            "historical" to "Historical",
            "horror" to "Horror",
            "isekai" to "Isekai",
            "josei" to "Josei",
            "lolicon" to "Lolicon",
            "long-strip" to "Long Strip",
            "love-polygon" to "Love Polygon",
            "magic" to "Magic",
            "magical-girl" to "Magical Girl",
            "manhua" to "Manhua",
            "manhwa" to "Manhwa",
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
            "mystery-shounen" to "Mystery Shounen",
            "mythology" to "Mythology",
            "one-shot" to "One Shot",
            "oneshot" to "Oneshot",
            "parody" to "Parody",
            "philosophical" to "Philosophical",
            "police" to "Police",
            "post-apocalyptic" to "Post-Apocalyptic",
            "psychological" to "Psychological",
            "rebirth" to "Rebirth",
            "reincarnation" to "Reincarnation",
            "romance" to "Romance",
            "romantic-subtext" to "Romantic Subtext",
            "samurai" to "Samurai",
            "school" to "School",
            "school-life" to "School Life",
            "sci-fi" to "Sci-fi",
            "seinen" to "Seinen",
            "shotacon" to "Shotacon",
            "shoujo" to "Shoujo",
            "shoujo-ai" to "Shoujo Ai",
            "shounen" to "Shounen",
            "shounen-ai" to "Shounen Ai",
            "slice-of-life" to "Slice of Life",
            "smut" to "Smut",
            "space" to "Space",
            "sports" to "Sports",
            "superhero" to "Superhero",
            "supernatural" to "Supernatural",
            "super-power" to "Super Power",
            "survival" to "Survival",
            "suspense" to "Suspense",
            "system" to "System",
            "team-sports" to "Team Sports",
            "thriller" to "Thriller",
            "time-travel" to "Time Travel",
            "tragedy" to "Tragedy",
            "urban" to "Urban",
            "urban-fantasy" to "Urban Fantasy",
            "vampire" to "Vampire",
            "video-game" to "Video Game",
            "villainess" to "Villainess",
            "visual-arts" to "Visual Arts",
            "webtoon" to "Webtoon",
            "webtoons" to "Webtoons",
            "wuxia" to "Wuxia",
            "yaoi" to "Yaoi",
            "yuri" to "Yuri",
            "zombies" to "Zombies",
        )
    }
}
