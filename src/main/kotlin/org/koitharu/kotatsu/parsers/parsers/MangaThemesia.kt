package org.koitharu.kotatsu.parsers.parsers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

abstract class MangaThemesia(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String):
    PagedMangaParser(context, source, 20) {

    private val domainName = domain

    override val configKeyDomain = ConfigKey.Domain(domainName)

    protected open val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)

    override val availableSortOrders = EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.NEWEST,
        )!!

    override val filterCapabilities = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isAuthorSearchSupported = true,
        )

    protected open val mangaDirectory = "manga"

    protected open val relatedSelector = ".related-posts .bsx, .bixbox .bsx, .related-manga .related-reading-wrap"

    protected open val searchSelector = ".utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx"

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        val url = buildString {
            append("https://")
            append(domainName)
            if(query?.isNotBlank() == true) {
                append("/?s=")
                append(query.urlEncoded())
            } else {
                append("/")
                append(mangaDirectory)
                append("?page=")
                append(page)
            }
            filter.author
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    append("&author=")
                    append(it.urlEncoded())
                }

            if (filter.year != -1) {
                append("&yearx=")
                append(filter.year)
            }

            filter.states.firstOrNull()?.let {
                append("&status=")
                append(
                    when(it) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        MangaState.PAUSED -> "hiatus"
                        MangaState.ABANDONED -> "dropped"
                        else -> ""
                    }
                )
            }

            filter.types.firstOrNull()?.let {
                append("&type=")
                append(
                    when(it) {
                        ContentType.MANGA -> "Manga"
                        ContentType.MANHWA -> "Manhwa"
                        ContentType.MANHUA -> "Manhua"
                        else -> ""
                    }
                )
            }
            filter.tags.forEach {
                append("&genre[]=")
                append(it.key.urlEncoded())
            }
            filter.tagsExclude.forEach {
                append("&genre[]=-")
                append(it.key.urlEncoded())
            }

            when(order) {
                SortOrder.UPDATED ->
                    append("&order=update")
                SortOrder.POPULARITY ->
                    append("&order=popular")
                SortOrder.NEWEST ->
                    append("&order=latest")
                else -> {}
            }
        }

        val document = webClient
                .httpGet(
                    url,
                )
                .parseHtml()


        return document
            .select(searchSelector)
            .mapNotNull {
                runCatching {
                    parseManga(it)
                }.getOrNull()

            }
    }


    protected open fun parseManga(element: Element): Manga {
        val link = element.selectFirst("a")
                ?: return Manga(
                    id = generateUid("empty"),
                    url = "",
                    publicUrl = "",
                    title = "",
                    altTitles = emptySet(),
                    authors = emptySet(),
                    tags = emptySet(),
                    coverUrl = null,
                    rating = RATING_UNKNOWN,
                    state = null,
                    contentRating =
                        if(isNsfwSource)
                            ContentRating.ADULT
                        else
                            null,
                    source = source,
                )

        val url = link.attr("href")
                .toRelativeUrl(domainName)

        return Manga(
            id = generateUid(url),
            url = url,
            publicUrl = url.toAbsoluteUrl(domainName),
            title = link.attr("title")
                    .ifBlank {
                        link.text()
                    },
            altTitles = emptySet(),
            authors = emptySet(),
            coverUrl = element
                    .selectFirst("img, source")
                    ?.imgAttr(),
            tags = emptySet(),
            rating = RATING_UNKNOWN,
            state = null,
            contentRating =
                if(isNsfwSource)
                    ContentRating.ADULT
                else
                    null,
            source = source,
        )
    }


    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val document = webClient
                .httpGet(
                    seed.url.toAbsoluteUrl(domainName),
                )
                .parseHtml()
        return document
            .select(relatedSelector)
            .mapNotNull {
                runCatching {
                    val link =
                        it.selectFirst("a")
                            ?: return@runCatching null
                    val url =
                        link
                            .attr("href")
                            .toRelativeUrl(domainName)
                    Manga(
                        id = generateUid(url),
                        url = url,
                        publicUrl = url.toAbsoluteUrl(domainName),
                        title = link.attr("title")
                                .ifBlank {
                                    link.text()
                                },
                        altTitles = emptySet(),
                        authors = emptySet(),
                        coverUrl = it
                                .selectFirst("img")
                                ?.imgAttr(),
                        tags = emptySet(),
                        rating = RATING_UNKNOWN,
                        state = null,
                        contentRating =
                            if(isNsfwSource)
                                ContentRating.ADULT
                            else
                                null,
                        source = source,
                    )
                }
                    .getOrNull()
            }
    }


    override suspend fun getDetails(manga: Manga): Manga {
        val document = webClient
                .httpGet(
                    manga.url.toAbsoluteUrl(domainName),
                )
                .parseHtml()

        val chapters = loadChapters(
                document,
                manga,
            )
        return manga.copy(
            title = document
                    .selectFirst("h1.entry-title, .ts-breadcrumb li:last-child span")
                    ?.text()
                    ?: manga.title,
            description = document
                    .selectFirst(".desc, .entry-content[itemprop=description]")
                    ?.text()
                    ?.trim()
                    .orEmpty(),
            coverUrl = document
                    .selectFirst(".thumb img, .infomanga img, .summary_image img, .cover img, img.wp-post-image")
                    ?.imgAttr()
                    ?: manga.coverUrl,
            authors = document
                    .select(".author, .artist, .fmed span")
                    .map {
                        it.text()
                    }
                    .filter {
                        it.isNotBlank()
                    }
                    .toSet(),
            tags = document
                    .select(".mgen a, .gnr a, .seriestugenre a")
                    .map {
                        MangaTag(
                            key = it.text().lowercase(),
                            title = it.text(),
                            source = source,
                        )

                    }
                    .toSet(),
            state = parseState(document
                        .select(
                            ".imptdt, .status"
                        )
                        .text()
                ),
            chapters = chapters,
        )
    }


    protected open suspend fun loadChapters(document: Document, manga: Manga): List<MangaChapter> {
        return document
            .select(
                "div.bxcl li, div.cl li, #chapterlist li, ul li:has(div.chbox)"
            )
            .mapNotNull { element ->
                val url = element
                        .selectFirst("a")
                        ?.attr("href")
                        ?: return@mapNotNull null
                val title = cleanChapterTitle(
                        element
                            .selectFirst("a")
                            ?.text()
                            .orEmpty()
                    )
                val chapterNumber = extractChapterNumber(title)
                        ?: return@mapNotNull null
                MangaChapter(
                    id = generateUid(url),
                    url = url.toRelativeUrl(domainName),
                    title = title,
                    number = chapterNumber,
                    volume = 0,
                    branch = null,
                    uploadDate =
                        element
                            .selectFirst(".chapterdate")
                            ?.text()
                            ?.let {
                                parseDate(it)
                            }
                            ?: 0,
                    scanlator = null,
                    source = source,
                )
            }
            .sortedBy { it.number }
    }

    private fun parseDate(
        value:String,
    ):Long {
        return try {
            dateFormat
                .parse(value)
                ?.time
                ?: 0
        } catch(_:Exception){
            0
        }
    }


    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val document =
            webClient
                .httpGet(
                    chapter.url.toAbsoluteUrl(domainName),
                )
                .parseHtml()

        val images =
            Regex(
                "\"images\"\\s*:\\s*(\\[.*?])",
                setOf(
                    RegexOption.DOT_MATCHES_ALL
                )
            )
                .find(document.html())
                ?.groupValues
                ?.getOrNull(1)

        if(!images.isNullOrBlank()) {
            val pages =
                runCatching {
                    Json
                        .parseToJsonElement(images)
                        .jsonArray
                        .map {
                            val url =
                                it.jsonPrimitive.content
                            MangaPage(
                                id = generateUid(url),
                                url = url,
                                preview = null,
                                source = source,
                            )
                        }
                }
                    .getOrNull()
            if(!pages.isNullOrEmpty())
                return pages
        }

        return document
            .select(pageSelector)
            .mapNotNull {
                val url =
                    it.imgAttr()
                if(url.isBlank())
                    null
                else
                    MangaPage(
                        id = generateUid(url),
                        url = url,
                        preview = null,
                        source = source,
                    )
            }
    }


    override suspend fun getPageUrl(
        page:MangaPage,
    ):String {
        return page.url
    }

    protected open fun parseState(value:String?):MangaState? {
        val text =
            value
                ?.lowercase()
                ?: return null
        return when{
            listOf(
                "ongoing",
                "on going",
                "publishing",
                "updating",
            )
                .any {
                    text.contains(it)
                } ->
                MangaState.ONGOING

            listOf(
                "completed",
                "complete",
                "finished",
            )
                .any {
                    text.contains(it)
                } ->
                MangaState.FINISHED

            listOf(
                "hiatus",
                "paused",
                "on hold",
            )
                .any {
                    text.contains(it)
                } ->
                MangaState.PAUSED

            listOf(
                "dropped",
                "cancelled",
                "canceled",
            )
                .any {
                    text.contains(it)
                } ->
                MangaState.ABANDONED
            else ->
                null
        }
    }


    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val tags =
            listOf(
                "Action",
                "Adventure",
                "Comedy",
                "Drama",
                "Fantasy",
                "Historical",
                "Horror",
                "Isekai",
                "Romance",
                "School Life",
                "Sci-Fi",
                "Shoujo",
                "Slice of Life",
                "Supernatural",
                "Thriller",
            )
                .map {
                    MangaTag(
                        key = it.lowercase(),
                        title = it,
                        source = source,
                    )
                }
                .toSet()

        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                    MangaState.ONGOING,
                    MangaState.FINISHED,
                    MangaState.PAUSED,
                    MangaState.ABANDONED,
                ),
            availableContentTypes = EnumSet.of(
                    ContentType.MANGA,
                    ContentType.MANHWA,
                    ContentType.MANHUA,
                ),
        )
    }

    protected fun Element.imgAttr(): String {
        val attributes = listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-cfsrc",
            "data-image",
            "data-bg",
            "src"
        )

        for(attribute in attributes) {
            val value = attr(attribute)
            if(value.isNotBlank()) {
                return fixImageExtension(
                    value.toAbsoluteUrl(domainName)
                )
            }
        }


        val srcset = attr("srcset")
        if(srcset.isNotBlank()) {
            return fixImageExtension(
                srcset
                    .split(",")
                    .last()
                    .trim()
                    .split(" ")
                    .first()
                    .toAbsoluteUrl(domainName)
            )
        }
        return ""
    }

    private fun fixImageExtension(url: String): String {
        var result = url
        // Remove WordPress image proxy
        result = result.replace(
            Regex(
                "https://i[0-3]\\.wp\\.com/"
            ),
            "https://"
        )
        // Remove WordPress resize params if they exist
        result = result.substringBefore("?")
        return result
    }



    private fun cleanChapterTitle(
        value: String,
    ): String {
        return value
            .replace(Regex("\\s+(January|February|March|April|May|June|July|August|September|October|November|December).*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}.*"), "")
            .trim()
    }

    private fun extractChapterNumber(title: String): Float? {
        return Regex(
            """(?:chapter|ch\.?)\s*([0-9]+(?:\.[0-9]+)?)""",
            RegexOption.IGNORE_CASE,
        )
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
    }

    open val pageSelector = "div#readerarea img"
}
