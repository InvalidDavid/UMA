package tsuki.site.en.nsfw


import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser
import tsuki.exception.ParseException

import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.model.MangaPage
import tsuki.model.ContentType

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.parseHtml
import tsuki.util.extractChapterNumber

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat

/**
 * Not a real Madara website, it just imitates it good.
 */

@MangaSourceParser("MANGA18FX", "Manga18fx", "en", ContentType.HENTAI)
internal class Manga18fx(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGA18FX, "manga18fx.com") {

    override val datePattern = "dd MMM yy"
    override val selectDesc = ".dsct"

    override val selectDate = "span.chapter-time"

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = false,
        isMultipleTagsSupported = false,
    )

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/").parseHtml()
        val scraped = doc.select(".header-bottom li a").mapNotNull { a ->
            val href = a.attr("href")
            val url = if (href.startsWith("http")) href else "https://$domain/$href"
            val name = a.text().trim().ifBlank { return@mapNotNull null }
            MangaTag(name, url, source)
        }.toSet()

        val hardcoded = listOf(
            MangaTag("Manhwa", "https://$domain/manga-genre/manhwa", source),
            MangaTag("Manhua", "https://$domain/manga-genre/manhua", source),
            MangaTag("Raw", "https://$domain/manga-genre/raw", source),
        )

        return scraped + hardcoded
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        if (query.isEmpty() && filter.tags.isNotEmpty()) {
            val genreUrl = filter.tags.first().key
            val doc = webClient.httpGet(genreUrl).parseHtml()
            return parseLatestOrSearch(doc)
        }
        if (query.isNotEmpty()) {
            val url = "https://$domain/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            val doc = webClient.httpGet(url).parseHtml()
            return parseLatestOrSearch(doc)
        }

        return when (order) {
            SortOrder.POPULARITY -> {
                val doc = webClient.httpGet("https://$domain/").parseHtml()
                parsePopular(doc)
            }
            else -> {
                val url = "https://$domain/page/$page"
                val doc = webClient.httpGet(url).parseHtml()
                parseLatestOrSearch(doc)
            }
        }
    }

    private fun parsePopular(doc: Document): List<Manga> {
        val block = doc.selectFirst(".trending-block") ?: return emptyList()
        return block.select("a").map { a -> mangaFromElement(a) }
    }

    private fun parseLatestOrSearch(doc: Document): List<Manga> {
        return doc.select(".bsx-item").mapNotNull { item ->
            val a = item.selectFirst("a") ?: return@mapNotNull null
            mangaFromElement(a)
        }
    }

    private fun mangaFromElement(a: Element): Manga {
        val href = a.attrAsRelativeUrl("href").removeSuffix("/")
        val title = a.attr("title").ifBlank { a.text() }
        val img = a.selectFirst("img")
        val cover = img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }

        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = cover,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> = parseChapters(doc)

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> = parseChapters(document)

    private fun parseChapters(doc: Document): List<MangaChapter> {
        val container = doc.selectFirst(".row-content-chapter") ?: return emptyList()
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

        return container.children().mapNotNull { child ->
            val a = child.selectFirst("a") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            val name = a.text().trim()
            val dateText = child.selectFirst("span.chapter-time")?.text()
            val number = name.extractChapterNumber()

            MangaChapter(
                id = generateUid(href),
                title = name,
                number = number,
                volume = 0,
                url = href,
                uploadDate = parseChapterDate(dateFormat, dateText),
                source = source,
                scanlator = null,
                branch = null,
            )
        }.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        var images = doc.select("div.page-break img")
        if (images.isEmpty()) {
            images = doc.select("div.read-content img, div.reading-content img")
        }
        return images.mapIndexed { index, img ->
            val url = img.attr("data-src")
                .ifBlank { img.attr("src") }
                .ifBlank { throw ParseException("Image URL missing for page $index", fullUrl) }

            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val fullUrl = seed.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        return doc.select(".related-items .item").mapNotNull { item ->
            val a = item.selectFirst("a[href*=/manga/]") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href").removeSuffix("/")
            val title = item.selectFirst("h5 a")?.text()?.trim()
                ?: a.attr("title").ifBlank { a.text() }
            val img = item.selectFirst("img")
            val cover = img?.attr("data-src").orEmpty().ifBlank { img?.attr("src").orEmpty() }

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }
}
