package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.*

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANGAKEKO", "MangaGeko", "en")
internal class MangaGeko(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAKEKO, 30) {

    override val configKeyDomain = ConfigKey.Domain("www.mgeko.cc")
    private val baseUrl = "https://$domain"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,             // recently_added
        SortOrder.UPDATED,            // latest
        SortOrder.POPULARITY,         // popular_all_time
        SortOrder.RATING,             // rating
        SortOrder.ALPHABETICAL,       // az
        SortOrder.ALPHABETICAL_DESC,  // za
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
    )

    private val dateFormats = listOf(
        SimpleDateFormat("MMMM dd, yyyy, h:mm a", Locale.ENGLISH),
        SimpleDateFormat("MMMM dd, yyyy, h a", Locale.ENGLISH),
    )

    private val allGenres = setOf(
        "Action", "Adventure", "Comedy", "Cooking", "Manga", "Drama", "Fantasy",
        "Gender bender", "Harem", "Historical", "Horror", "Isekai", "Josei",
        "Manhua", "Manhwa", "Martial arts", "Mature", "Mecha", "Medical",
        "Mystery", "One shot", "Psychological", "Romance", "School life",
        "Sci fi", "Seinen", "Shoujo", "Shounen", "Slice of life", "Sports",
        "Supernatural", "Tragedy", "Webtoons", "Ladies",
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags = allGenres.map { MangaTag(it, it.lowercase().replace(" ", "_"), source) }.toSet()
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
            availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.COMICS),
            availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            return searchManga(page, filter.query!!)
        }
        return browseManga(page, order, filter)
    }

    private suspend fun searchManga(page: Int, query: String): List<Manga> {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("results", page.toString())
            .build()
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".novel-item").mapNotNull { it.toManga() }
    }

    private suspend fun browseManga(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sort = when (order) {
            SortOrder.NEWEST -> "recently_added"
            SortOrder.UPDATED -> "latest"
            SortOrder.POPULARITY -> "popular_all_time"
            SortOrder.RATING -> "rating"
            SortOrder.ALPHABETICAL -> "az"
            SortOrder.ALPHABETICAL_DESC -> "za"
            else -> "latest"
        }

        val url = "$baseUrl/browse-comics/data/".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("sort", sort)

            val safeMode = if (ContentRating.ADULT !in filter.contentRating) "1" else "0"
            addQueryParameter("safe_mode", safeMode)

            if (filter.tags.isNotEmpty()) {
                addQueryParameter("include_genres", filter.tags.joinToString(",") { it.key.replace("_", " ") })
            }
            if (filter.tagsExclude.isNotEmpty()) {
                addQueryParameter("exclude_genres", filter.tagsExclude.joinToString(",") { it.key.replace("_", " ") })
            }

            if (filter.states.isNotEmpty()) {
                val status = when (filter.states.first()) {
                    MangaState.ONGOING -> "Ongoing"
                    MangaState.FINISHED -> "Completed"
                    MangaState.PAUSED -> "Hiatus"
                    else -> null
                }
                status?.let { addQueryParameter("status", it) }
            }

            if (filter.types.isNotEmpty()) {
                val type = when (filter.types.first()) {
                    ContentType.MANGA -> "Manga"
                    ContentType.MANHWA -> "Manhwa"
                    ContentType.MANHUA -> "Manhua"
                    ContentType.COMICS -> "Webtoon"
                    else -> null
                }
                type?.let { addQueryParameter("type", it) }
            }
        }.build()

        val response = webClient.httpGet(url)
        val json = JSONObject(response.body.string())
        val htmlFragment = json.getString("results_html")
        val doc = Jsoup.parseBodyFragment(htmlFragment, baseUrl)
        return doc.select(".comic-card").map { it.toManga() }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val header = doc.selectFirstOrThrow(".novel-header")

        val title = header.selectFirst(".novel-title")?.text()?.trim() ?: manga.title
        val cover = header.selectFirst(".cover img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        } ?: manga.coverUrl

        val author = header.selectFirst(".author a")?.attr("title")?.trim()?.takeIf {
            it.lowercase() != "updating"
        }

        val state = when {
            header.selectFirst("div.header-stats strong.completed") != null -> MangaState.FINISHED
            header.selectFirst("div.header-stats strong.ongoing") != null -> MangaState.ONGOING
            else -> null
        }

        val tags = doc.select(".categories a[href*=genre]").mapToSet { a ->
            val name = a.ownText().trim().split(" ").joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
            MangaTag(name, name.lowercase().replace(" ", "_"), source)
        }

        val chapters = fetchChapters(manga.url)

        return manga.copy(
            title = title,
            altTitles = emptySet(),
            coverUrl = cover,
            largeCoverUrl = cover,
            state = state,
            authors = setOfNotNull(author),
            description = null,
            tags = tags,
            chapters = chapters,
        )
    }

    private suspend fun fetchChapters(mangaUrl: String): List<MangaChapter> {
        val doc = webClient.httpGet(mangaUrl.toAbsoluteUrl(domain) + "all-chapters/").parseHtml()
        return doc.select("ul.chapter-list > li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val url = a.attrAsRelativeUrl("href")
            val titleEl = li.selectFirst(".chapter-title, .chapter-number") ?: return@mapNotNull null
            val chapterName = titleEl.ownText()
            val dateStr = li.selectFirst(".chapter-update")?.attr("datetime")
                ?.replace(".", "")?.replace("Sept", "Sep") ?: ""
            val date = dateFormats.firstNotNullOfOrNull { fmt -> fmt.parseSafe(dateStr) } ?: 0L
            MangaChapter(
                id = generateUid(url),
                title = "Chapter $chapterName",
                number = 0f,
                volume = 0,
                url = url,
                uploadDate = date,
                source = source,
                scanlator = null,
                branch = null,
            )
        }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("#chapter-reader img").mapNotNull { img ->
            val src = img.absUrl("src")
            if (src.isEmpty()) return@mapNotNull null
            MangaPage(
                id = generateUid(src),
                url = src,
                preview = null,
                source = source,
            )
        }
    }

    private fun Element.toManga(): Manga {
        val titleEl = selectFirst(".comic-card__title a") ?: selectFirst(".novel-title")
        val title = titleEl?.text()?.trim() ?: ""
        val href = selectFirst("a")?.attrAsRelativeUrl("href") ?: ""
        val cover = selectFirst(".comic-card__cover img, .novel-cover img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        } ?: ""
        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = cover,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private fun SimpleDateFormat.parseSafe(date: String): Long? = runCatching { parse(date)?.time }.getOrNull()
}