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
import tsuki.model.ContentRating
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaState

import tsuki.util.attrAsRelativeUrl
import tsuki.util.generateUid
import tsuki.util.mapNotNullToSet
import tsuki.util.parseHtml
import tsuki.util.selectFirstOrThrow
import tsuki.util.toTitleCase
import tsuki.util.urlEncoded
import tsuki.util.extractChapterNumber
import tsuki.util.oneOrThrowIfMany

import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.EnumSet

@MangaSourceParser("MANGADISTRICT", "MangaDistrict", "en", ContentType.HENTAI)
internal class MangaDistrict(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGADISTRICT, "mangadistrict.com", pageSize = 30) {

    override val tagPrefix = "publication-genre/"
    override val withoutAjax: Boolean = true
    override val datePattern: String = "MMMM d, yyyy"
    override val stylePage: String = "?style=list"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isMultipleTagsSupported = false,
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val pages = page + 1
        fun sortParam(): String = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.UPDATED -> "latest"
            SortOrder.NEWEST -> "new-manga"
            SortOrder.ALPHABETICAL -> "alphabet"
            SortOrder.RATING -> "rating"
            SortOrder.RELEVANCE -> ""
            else -> ""
        }
        val url = if (filter.tags.isNotEmpty()) {
            val genreSlug = filter.tags.first().key
            buildString {
                append("https://")
                append(domain)
                append("/publication-genre/$genreSlug/")
                if (pages > 1) {
                    append("page/")
                    append(pages)
                    append("/")
                }
                append("?m_orderby=")
                append(sortParam())
            }
        } else {
            buildString {
                append("https://")
                append(domain)
                if (pages > 1) {
                    append("/page/")
                    append(pages.toString())
                }
                append("/?s=")
                append(filter.query?.urlEncoded() ?: "")
                append("&post_type=wp-manga")
                filter.states.forEach {
                    append("&status[]=")
                    when (it) {
                        MangaState.ONGOING -> append("on-going")
                        MangaState.FINISHED -> append("end")
                        MangaState.ABANDONED -> append("canceled")
                        MangaState.PAUSED -> append("on-hold")
                        MangaState.UPCOMING -> append("upcoming")
                        else -> throw IllegalArgumentException("$it not supported")
                    }
                }
                filter.contentRating.oneOrThrowIfMany()?.let {
                    append("&adult=")
                    append(
                        when (it) {
                            ContentRating.SAFE -> "0"
                            ContentRating.ADULT -> "1"
                            else -> ""
                        }
                    )
                }
                if (filter.year != 0) {
                    append("&release=")
                    append(filter.year.toString())
                }
                filter.author?.takeIf { it.isNotEmpty() }?.let {
                    append("&author=")
                    append(it.lowercase().replace(" ", "-"))
                }
                append("&m_orderby=")
                append(sortParam())
            }
        }
        val html = try {
            webClient.httpGet(url).parseHtml()
        } catch (e: HttpStatusException) {
            if (e.statusCode == HttpURLConnection.HTTP_INTERNAL_ERROR) return emptyList()
            else throw ParseException("Can't fetch data from source!", url)
        }
        return parseMangaList(html)
    }

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
        val chapters = doc.body()
            .select("li.wp-manga-chapter")
            .mapNotNull { li ->
                val a = li.selectFirstOrThrow("a")
                val href = a.attrAsRelativeUrl("href")
                val link = href + stylePage
                val name = a.selectFirst("p")?.text()
                    ?: a.ownText()
                val dateText = li.selectFirst("a.c-new-tag")?.attr("title")
                        ?: li.selectFirst("span.chapter-release-date i")?.text()
                MangaChapter(
                    id = generateUid(href),
                    title = name,
                    number = name.extractChapterNumber(),
                    volume = 0,
                    url = link,
                    uploadDate = parseChapterDate(dateFormat, dateText),
                    scanlator = null,
                    branch = null,
                    source = source,
                )
            }
            .sortedWith(compareBy<MangaChapter> { it.number }.thenBy { it.title })
        return chapters
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
