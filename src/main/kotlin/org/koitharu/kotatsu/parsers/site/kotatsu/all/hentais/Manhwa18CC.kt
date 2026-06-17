package org.koitharu.kotatsu.parsers.site.kotatsu.all.hentais

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.host
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseFailed
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.HashSet
import java.util.Locale
import org.koitharu.kotatsu.parsers.parsers.MadaraParser

@MangaSourceParser("MANHWA18CC", "Manhwa18.cc", "en", ContentType.HENTAI)
internal class Manhwa18CC(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANHWA18CC, "manhwa18.cc", 24) {
    override val datePattern = "dd MMM yyyy"
    override val sourceLocale: Locale = Locale.ENGLISH
    override val listUrl = "webtoons/"
    override val tagPrefix = "webtoon-genre/"
    override val withoutAjax = true
    override val selectTestAsync = "ul.row-content-chapter"
    override val selectDate = "span.chapter-time"
    override val selectChapter = "li.a-h"
    override val selectBodyPage = "div.read-content"

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    override suspend fun getFilterOptions() = super.getFilterOptions().copy(
        availableStates = emptySet(),
        availableContentRating = emptySet(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        val url = buildString {
            append("https://")
            append(domain)

            when {

                !query.isNullOrEmpty() -> {
                    append("/search?q=")
                    append(query.urlEncoded())
                    append("&page=")
                    append(page.toString())
                }

                else -> {

                    val tag = filter.tags.oneOrThrowIfMany()
                    if (filter.tags.isNotEmpty()) {
                        append("/$tagPrefix")
                        append(tag?.key.orEmpty())
                    } else {
                        append("/$listUrl")
                    }

                    if (page > 1) {
                        append(page.toString())
                    }

                    append("?orderby=")
                    when (order) {
                        SortOrder.POPULARITY -> append("trending")
                        SortOrder.UPDATED -> append("latest")
                        SortOrder.ALPHABETICAL -> append("alphabet")
                        SortOrder.RATING -> append("rating")
                        else -> append("latest")
                    }
                }
            }
        }
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("div.manga-lists div.manga-item").map { div ->
            val href = div.selectFirst("a")?.attrAsRelativeUrlOrNull("href") ?: div.parseFailed("Link not found")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(div.host ?: domain),
                coverUrl = div.selectFirst("img")?.src(),
                title = div.selectFirstOrThrow("h3").text(),
                altTitles = emptySet(),
                rating = div.selectFirst(".item-rate span")?.ownText()?.toFloatOrNull()?.div(5f) ?: -1f,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/$listUrl").parseHtml()
        val list = doc.body().selectFirstOrThrow("div.sub-menu").select("ul li")
        val keySet = HashSet<String>(list.size)
        return list.mapNotNullToSet { li ->
            val a = li.selectFirst("a") ?: return@mapNotNullToSet null
            val href = a.attr("href").removeSuffix("/").substringAfterLast(tagPrefix, "")
            if (href.isEmpty() || !keySet.add(href)) {
                return@mapNotNullToSet null
            }
            MangaTag(
                key = href,
                title = a.ownText().ifEmpty {
                    a.selectFirst(".menu-image-title")?.text() ?: return@mapNotNullToSet null
                }.toTitleCase(),
                source = source,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val root = doc.body().selectFirstOrThrow(selectBodyPage)
        return root.select("img").map { img ->
            val url = img.requireSrc()
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}