package tsuki.site.all.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.network.OkHttpWebClient

import tsuki.model.ContentType
import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.parseHtml

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.EnumSet

@MangaSourceParser("SEXKOMIX2COM", "SexKomik2COM", type = ContentType.HENTAI)
class SexKomik2COM(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SEXKOMIX2COM, 24) {

    override val configKeyDomain = tsuki.config.ConfigKey.Domain("sexkomix2.com")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,   // date
        SortOrder.POPULARITY, // prosmotr (views)
        SortOrder.RATING,   // like
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
        isTagsExclusionSupported = false,
        isSearchWithFiltersSupported = true,
    )

    private val cookieInterceptor = Interceptor { chain ->
        val original = chain.request()
        val request = original.newBuilder()
            .header("Cookie", "confirm=true")
            .header("Referer", "https://$domain/")
            .build()
        chain.proceed(request)
    }

    override val webClient by lazy {
        val client = context.httpClient.newBuilder()
            .addInterceptor(cookieInterceptor)
            .build()
        OkHttpWebClient(client, source)
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = setOf(
                MangaTag("3D", "3D", source),
                MangaTag("Adventure", "Adventure", source),
                MangaTag("AI Generated", "AI Generated", source),
                MangaTag("BDSM", "BDSM", source),
                MangaTag("Big boobs", "Big boobs", source),
                MangaTag("Big dick", "Big dick", source),
                MangaTag("Black girls", "Black girls", source),
                MangaTag("Blondes", "Blondes", source),
                MangaTag("Brunettes", "Brunettes", source),
                MangaTag("Cheating", "Cheating", source),
                MangaTag("Double penetration", "Double penetration", source),
                MangaTag("Fantasy", "Fantasy", source),
                MangaTag("Fat girls", "Fat girls", source),
                MangaTag("Fitness", "Fitness", source),
                MangaTag("Furry", "Furry", source),
                MangaTag("Gays Yaoi", "Gays Yaoi", source),
                MangaTag("Giants", "Giants", source),
                MangaTag("Hentai Manga", "Hentai Manga", source),
                MangaTag("Inter Komix", "Inter Komix", source),
                MangaTag("Interracial sex", "Interracial sex", source),
                MangaTag("Lesbians", "Lesbians", source),
                MangaTag("Milf", "Milf", source),
                MangaTag("Monsters", "Monsters", source),
                MangaTag("Neighbor", "Neighbor", source),
                MangaTag("NFT", "NFT", source),
                MangaTag("Normal boobs", "Normal boobs", source),
                MangaTag("Nurses", "Nurses", source),
                MangaTag("Parodies", "Parodies", source),
                MangaTag("Redheads", "Redheads", source),
                MangaTag("Shemale", "Shemale", source),
                MangaTag("Simpsons", "Simpsons", source),
                MangaTag("Super heroes", "Super heroes", source),
                MangaTag("Teacher", "Teacher", source),
                MangaTag("Teen", "Teen", source),
                MangaTag("Threesome", "Threesome", source),
                MangaTag("Unique VIP", "Unique VIP", source),
                MangaTag("With sex toys", "With sex toys", source),
            ),
            availableStates = emptySet(),
            availableContentTypes = emptySet(),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.NEWEST -> "date"
            SortOrder.POPULARITY -> "prosmotr"
            SortOrder.RATING -> "like"
            else -> "date"
        }

        val tag = filter.tags.firstOrNull()
        val basePath = if (tag != null) "categories" else "search"

        val url = "https://$domain/$basePath/".toHttpUrl().newBuilder().apply {
            addQueryParameter("lang", "en")

            if (tag != null) {
                addQueryParameter("cat", tag.key)
            }

            filter.query?.takeIf { it.isNotBlank() }?.let {
                addQueryParameter("q", it)
            }

            addQueryParameter("page", page.toString())
            addQueryParameter("sort", sortParam)
        }.build().toString()

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        val listContainer = doc.selectFirst("#comix_directory")
            ?: return emptyList()

        return listContainer.select("li.comix").mapNotNull { element ->
            parseMangaItem(element)
        }
    }

    private fun parseMangaItem(element: Element): Manga? {
        val link = element.selectFirst("a[href]") ?: return null
        val href = link.attr("abs:href")
        if (href.isBlank()) return null

        val titleElement = element.selectFirst(".comix_title h2 p")
        val title = titleElement?.text()?.trim() ?: link.attr("title").ifBlank { link.text() }.trim()
        if (title.isBlank()) return null

        val img = element.selectFirst(".comix_img_box img")
        val cover = img?.imgAttr() ?: ""

        val tagElements = element.select(".tags_ul li a")
        val tags = tagElements.mapNotNull { a ->
            val text = a.text().trim()
            if (text.isNotBlank()) MangaTag(key = text.lowercase(), title = text, source = source) else null
        }.toSet()

        return Manga(
            id = generateUid(href),
            title = title,
            altTitles = emptySet(),
            url = href,
            publicUrl = href,
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = cover,
            tags = tags,
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-url") -> attr("abs:data-url")
        hasAttr("data-zoom-src") -> attr("abs:data-zoom-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-cfsrc") -> attr("abs:data-cfsrc")
        else -> attr("abs:src")
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()

        val title = doc.selectFirst(".info_box h1 a")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
        if (title.isNullOrBlank()) {
            throw ParseException(
                "Failed to extract title from manga page: ${manga.url}\n" +
                        "HTML snippet: ${doc.html().take(300)}",
                manga.url
            )
        }

        val description = doc.selectFirst(".info_box p")?.text()?.trim() ?: ""

        val coverElement = doc.selectFirst("#comix_cover_img")
        val cover = coverElement?.imgAttr()?.takeIf { it.isNotBlank() }
        if (cover == null) {
            throw ParseException(
                "Failed to extract cover image from manga page: ${manga.url}\n" +
                        "HTML snippet: ${doc.html().take(300)}",
                manga.url
            )
        }

        val categoryElements = doc.select(".info_box:has(.tags_ul) .tags_ul li a")
        val tags = categoryElements.mapNotNull { a ->
            val text = a.text().trim()
            if (text.isNotBlank()) MangaTag(key = text.lowercase(), title = text, source = source) else null
        }.toSet()

        val studioElement = doc.selectFirst(".studio_translator_box .link_button a")
        val authors = studioElement?.text()?.trim()?.let { setOf(it) } ?: emptySet()

        val chapters = listOf(
            MangaChapter(
                id = generateUid(manga.url),
                url = manga.url,
                title = title,
                number = 1f,
                volume = 0,
                uploadDate = 0L,
                scanlator = null,
                branch = null,
                source = source,
            )
        )

        return manga.copy(
            title = title,
            description = description,
            coverUrl = cover,
            tags = tags,
            authors = authors,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        val fancyboxLinks = doc.select("#comix_pages_ul li a.fancybox[href]")
        if (fancyboxLinks.isNotEmpty()) {
            return fancyboxLinks.mapIndexed { _, element ->
                val url = element.attr("abs:href")
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }

        val images = doc.select("#comix_pages_ul li img")
        if (images.isNotEmpty()) {
            return images.mapIndexed { _, img ->
                val url = img.imgAttr()
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }

        val scriptMatch = Regex("\"images\"\\s*:\\s*(\\[.*?])", RegexOption.DOT_MATCHES_ALL)
            .find(doc.html())
        if (scriptMatch != null) {
            val jsonArray = JSONArray(scriptMatch.groupValues[1])
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
        return emptyList()
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        return emptyList()
    }
}
