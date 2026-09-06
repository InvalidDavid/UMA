package tsuki.site.ru.nsfw

import tsuki.parsers.ChanParser
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey

import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.mapToSet
import tsuki.util.parseFailed
import tsuki.util.parseHtml
import tsuki.util.requireElementById
import tsuki.util.toAbsoluteUrl
import tsuki.util.toTitleCase
import tsuki.util.urlBuilder
import tsuki.util.urlDecode

import java.util.EnumSet
import okhttp3.HttpUrl

@MangaSourceParser("HENCHAN", "Хентай-тян", "ru", ContentType.HENTAI)
internal class HenChan(context: MangaLoaderContext) : 
    ChanParser(context, MangaParserSource.HENCHAN) {

    override val configKeyDomain = ConfigKey.Domain(
        "x5.h-chan.me",
        "xxxx.henchan.pro",
        "xxl.hentaichan.live",
        "xxx.henchan.pro",
        "y.hentaichan.live",
        "xx.hentaichan.live",
        "x.henchan.pro",
        "hentaichan.live",
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.RATING,
    )

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val root = doc.body().requireElementById("dle-content")
        val readLink = manga.url.replace("manga", "online")
        return manga.copy(
            description = root.getElementById("description")?.html()?.substringBeforeLast("<div"),
            largeCoverUrl = root.getElementById("cover")?.absUrl("src"),
            tags = root.selectFirst("div.sidetags")?.select("li.sidetag")?.mapToSet {
                val a = it.children().last() ?: doc.parseFailed("Invalid tag")
                MangaTag(
                    title = a.text().toTitleCase(),
                    key = a.attr("href").substringAfterLast('/').urlDecode(),
                    source = source,
                )
            } ?: manga.tags,
            chapters = listOf(
                MangaChapter(
                    id = generateUid(readLink),
                    url = readLink,
                    source = source,
                    number = 0f,
                    volume = 0,
                    uploadDate = 0L,
                    title = null,
                    scanlator = null,
                    branch = null,
                ),
            ),
        )
    }

    override fun buildUrl(offset: Int, order: SortOrder, filter: MangaListFilter): HttpUrl = when {
        filter.query.isNullOrEmpty() && filter.tags.isEmpty() && filter.tagsExclude.isEmpty() -> {
            val builder = urlBuilder().addQueryParameter("offset", offset.toString())
            when (order) {
                SortOrder.POPULARITY -> {
                    builder.addPathSegment("mostviews")
                    builder.addQueryParameter("sort", "manga")
                }
                SortOrder.RATING -> {
                    builder.addPathSegment("mostfavorites")
                    builder.addQueryParameter("sort", "manga")
                }
                else -> {
                    builder.addPathSegment("manga")
                    builder.addPathSegment("newest")
                }
            }
            builder.build()
        }
        else -> {
            super.buildUrl(offset, order, filter)
        }
    }
}
