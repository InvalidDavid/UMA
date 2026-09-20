package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.network.OkHttpWebClient

import tsuki.model.ContentRating
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

import tsuki.util.generateUid

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("KARH", "Karh", "ar")
internal class Karh(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KARH, 30) {

    override val configKeyDomain = ConfigKey.Domain("karh.org")
    private val baseUrl = "https://$domain"

    override val webClient = OkHttpWebClient(context.httpClient, source)

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
        isSearchWithFiltersSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (page > 1) return emptyList()

        val body = webClient.httpGet(baseUrl).body?.string() ?: return emptyList()
        val doc = Jsoup.parse(body, baseUrl)

        var items: List<Element> = doc.select("#allWorksGrid .manga-filter-item")
        if (items.isEmpty()) return emptyList()

        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            items = items.filter { item ->
                val title = item.selectFirst("h3.manga-card-title")?.text().orEmpty()
                title.contains(query, ignoreCase = true)
            }
        }
        return items.mapNotNull { item ->
            runCatching { parseMangaCard(item) }.getOrNull()
        }
    }

    private fun parseMangaCard(item: Element): Manga {
        val link = item.selectFirst("a.manga-card")
            ?: throw ParseException("No link in manga card", item.baseUri())
        val href = link.attr("href").trimStart('/')
        val title = item.selectFirst("h3.manga-card-title")?.text()?.trim()
            ?: href.replace("-", " ").replaceFirstChar { it.uppercase() }
        val cover = item.selectFirst("img")?.attr("src")?.let(::absolutize)

        val rating = item.selectFirst(".manga-card-rating-overlay")
            ?.text()?.trim()?.toFloatOrNull() ?: RATING_UNKNOWN

        val cats = link.attr("data-cats")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val tags = cats.mapTo(LinkedHashSet()) {
            MangaTag(title = it, key = it, source = source)
        }

        return Manga(
            id = generateUid(href),
            title = title,
            altTitles = emptySet(),
            url = href,
            publicUrl = "$baseUrl/$href",
            rating = rating,
            contentRating = ContentRating.SAFE,
            coverUrl = cover.orEmpty(),
            tags = tags,
            state = null,
            authors = emptySet(),
            largeCoverUrl = cover,
            description = null,
            source = source,
        )
    }
    
    override suspend fun getDetails(manga: Manga): Manga {
        val url = "$baseUrl/${manga.url}"
        val body = webClient.httpGet(url).body?.string()
            ?: throw ParseException("Empty response", url)
        val doc = Jsoup.parse(body, url)

        val title = doc.selectFirst(".manga-detail-title")?.text()?.trim() ?: manga.title
        val cover = doc.selectFirst(".manga-detail-cover img")?.attr("src")?.let(::absolutize)

        return manga.copy(
            title = title,
            coverUrl = cover ?: manga.coverUrl,
            largeCoverUrl = cover ?: manga.largeCoverUrl,
            chapters = parseChapters(doc, manga),
        )
    }

    private fun parseChapters(doc: Document, manga: Manga): List<MangaChapter> {
        val elements = doc.select(".chapter-item")
        val chapters = mutableListOf<MangaChapter>()

        for (element in elements) {
            val rawHref = element.attr("href")
            if (rawHref.isBlank()) continue
            val href = if (rawHref.startsWith("/")) rawHref else "/$rawHref"

            val numberText = element.selectFirst(".chapter-item-number")?.text()?.trim().orEmpty()
            val titleText = element.selectFirst(".chapter-item-title")?.text()?.trim().orEmpty()
            val dateText = element.selectFirst(".chapter-item-date")?.text()?.trim().orEmpty()

            chapters += MangaChapter(
                id = generateUid("${manga.url}#$href"),
                title = buildString {
                    append(numberText)
                    if (titleText.isNotEmpty()) append(" - ").append(titleText)
                },
                number = extractChapterNumber(numberText),
                volume = 0,
                url = href,
                uploadDate = parseDate(dateText),
                scanlator = null,
                branch = null,
                source = source,
            )
        }

        return chapters.sortedBy { it.number }
    }

    private fun extractChapterNumber(text: String): Float {
        val match = Regex("""(\d+(?:\.\d+)?)""").find(text)?.value
        return match?.toFloatOrNull() ?: 0f
    }

    private fun parseDate(text: String): Long {
        if (text.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("d/M/yyyy", Locale.US).parse(text)?.time ?: 0L
        }.getOrDefault(0L)
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = absolutize(chapter.url)
        val body = webClient.httpGet(url).body?.string()
            ?: throw ParseException("Empty response", url)
        val doc = Jsoup.parse(body, url)

        val pages = mutableListOf<MangaPage>()
        val images = doc.select(".reader-page img")

        for ((index, img) in images.withIndex()) {
            val src = img.attr("src")
            if (src.isBlank()) continue
            pages += MangaPage(
                id = generateUid("${chapter.id}-$index"),
                url = absolutize(src),
                preview = null,
                source = source,
            )
        }
        return pages
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
    
    private fun absolutize(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> "$baseUrl/$url"
    }
}
