package tsuki.site.en.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser
import tsuki.exception.ParseException

import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.SortOrder
import tsuki.model.MangaListFilterCapabilities

import tsuki.util.mapNotNullToSet
import tsuki.util.parseHtml
import tsuki.util.toTitleCase

import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document

@MangaSourceParser("MANGADISTRICT", "MangaDistrict", "en", ContentType.HENTAI)
internal class MangaDistrict(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGADISTRICT, "mangadistrict.com", pageSize = 30) {

    override val tagPrefix = "publication-genre/"
    override val withoutAjax = true
    override val datePattern = "MMMM d, yyyy"
    override val stylePage = "?style=list"

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isMultipleTagsSupported = false,
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (filter.tags.isEmpty()) {
            return super.getListPage(page, order, filter)
        }

        val pages = page + 1
        val genreSlug = filter.tags.first().key
        val sortParam = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.UPDATED -> "latest"
            SortOrder.NEWEST -> "new-manga"
            SortOrder.ALPHABETICAL -> "alphabet"
            SortOrder.RATING -> "rating"
            SortOrder.RELEVANCE -> ""
            else -> ""
        }

        val url = buildString {
            append("https://")
            append(domain)
            append("/$tagPrefix$genreSlug/")
            if (pages > 1) {
                append("page/")
                append(pages)
                append("/")
            }
            append("?m_orderby=")
            append(sortParam)
        }

        val html = try {
            webClient.httpGet(url).parseHtml()
        } catch (e: HttpStatusException) {
            throw ParseException("Failed to load page: ${e.statusCode}", url)
        }
        return parseMangaList(html)
    }

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        return super.getChapters(manga, doc)
            .sortedWith(compareBy<MangaChapter> { it.number }.thenBy { it.title })
    }

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/").parseHtml()
        val elements = doc.select("div.genres_wrap ul li a")
        return elements.mapNotNullToSet { a ->
            val href = a.attr("href").removeSuffix("/").substringAfterLast(tagPrefix, "")
            if (href.isBlank()) return@mapNotNullToSet null
            MangaTag(
                key = href,
                title = a.text().trim().toTitleCase(),
                source = source,
            )
        }
    }
}
