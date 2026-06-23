package org.koitharu.kotatsu.parsers.site.kotatsu.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("TEAMXNOVEL", "TeamXNovel", "ar")
internal class TeamXNovel(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.TEAMXNOVEL, 10) {

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

    override val configKeyDomain = ConfigKey.Domain("olympustaff.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !query.isNullOrEmpty() -> {
                    append("/?search=")
                    append(query.urlEncoded())
                    append("&page=")
                    append(page)
                }

                else -> {

                    if (order == SortOrder.UPDATED) {
                        if (filter.tags.isNotEmpty() || filter.demographics.isNotEmpty()) {
                            throw IllegalArgumentException("Updated sorting does not support other sorting filters")
                        }
                        append("/?page=")
                        append(page.toString())
                    } else {
                        append("/series?page=")
                        append(page.toString())

                        filter.tags.oneOrThrowIfMany()?.let {
                            append("&genre=")
                            append(it.key)
                        }

                        filter.types.forEach {
                            append("&type=")
                            append(
                                when (it) {
                                    ContentType.MANGA -> "مانجا ياباني"
                                    ContentType.MANHWA -> "مانهوا كورية"
                                    ContentType.MANHUA -> "مانها صيني"
                                    else -> ""
                                },
                            )
                        }

                        filter.states.oneOrThrowIfMany()?.let {
                            append("status=")
                            append(
                                when (it) {
                                    MangaState.ONGOING -> "مستمرة"
                                    MangaState.FINISHED -> "مكتمل"
                                    MangaState.ABANDONED -> "متوقف"
                                    else -> "مستمرة"
                                },
                            )
                        }
                    }
                }
            }
        }
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("div.listupd .bs .bsx").ifEmpty {
            doc.select("div.post-body .box")
        }.map { div ->
            val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                title = div.select(".tt, h3").text(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = div.selectFirstOrThrow("img").src()?.replace("thumbnail_", ""),
                tags = emptySet(),
                state = when (div.selectFirst(".status")?.text()) {
                    "مستمرة" -> MangaState.ONGOING
                    "مكتمل" -> MangaState.FINISHED
                    "متوقف" -> MangaState.ABANDONED
                    else -> null
                },
                authors = emptySet(),
                source = source,
            )
        }
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/series").parseHtml()
        return doc.requireElementById("select_genre").select("option").mapToSet {
            MangaTag(
                key = it.attr("value"),
                title = it.text().toTitleCase(sourceLocale),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val mangaUrl = manga.url.toAbsoluteUrl(domain)
        val maxPageChapterSelect = doc.select(".pagination .page-item a")
        var maxPageChapter = 1
        if (!maxPageChapterSelect.isNullOrEmpty()) {
            maxPageChapterSelect.map {
                val i = it.attr("href").substringAfterLast("=").toInt()
                if (i > maxPageChapter) {
                    maxPageChapter = i
                }
            }
        }
        return manga.copy(
            state = when (doc.selectFirstOrThrow(".full-list-info:contains(الحالة:) a").text()) {
                "مستمرة" -> MangaState.ONGOING
                "مكتمل" -> MangaState.FINISHED
                "متوقف" -> MangaState.ABANDONED
                else -> null
            },
            tags = doc.select(".review-author-info a").mapToSet { a ->
                MangaTag(
                    key = a.attr("href").substringAfterLast("="),
                    title = a.text(),
                    source = source,
                )
            },
            description = doc.selectFirstOrThrow(".review-content").html(),
            chapters = run {
                if (maxPageChapter == 1) {
                    parseChapters(doc)
                } else {
                    coroutineScope {
                        val result = ArrayList(parseChapters(doc))
                        result.ensureCapacity(result.size * maxPageChapter)
                        (2..maxPageChapter).map { i ->
                            async {
                                loadChapters(mangaUrl, i)
                            }
                        }.awaitAll()
                            .flattenTo(result)
                        result
                    }
                }
            }.reversed(),
        )
    }

    private suspend fun loadChapters(baseUrl: String, page: Int): List<MangaChapter> {
        return parseChapters(webClient.httpGet("$baseUrl?page=$page").parseHtml().body())
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd hh:mm:ss", Locale.getDefault())

    private fun parseChapters(root: Element): List<MangaChapter> {
        // Try current website structure first (enhanced-chapters-grid)
        val chapters = root.select("div.enhanced-chapters-grid a.chapter-link").ifEmpty {
            // Fallback to potential alternative structures
            root.select("div.chapter-card a").ifEmpty {
                root.select("div.eplister ul a").ifEmpty {
                    root.select("#chapter-contact .eplister ul li a")
                }
            }
        }

        return chapters.map { element ->
            val url = element.attrAsRelativeUrl("href")

            // Extract chapter number and title from current structure
            val chpNum = element.select(".chapter-number").text()
            val chpTitle = element.select(".chapter-title").text()

            // Fallback to old structure if current selectors are empty
            val finalChpNum = chpNum.ifEmpty {
                element.select("div.chapter-info div.chapter-number").text().ifEmpty {
                    element.select("div.epl-num").text()
                }
            }
            val finalChpTitle = chpTitle.ifEmpty {
                element.select("div.chapter-info div.chapter-title").text().ifEmpty {
                    element.select("div.epl-title").text()
                }
            }

            val title = when {
                finalChpNum.isNotBlank() -> "$finalChpNum - $finalChpTitle"
                else -> finalChpTitle
            }

            // Extract date from current structure, fallback to old structures
            val dateText = element.select(".chapter-date").text().ifEmpty {
                element.select("div.chapter-info div.chapter-date").text().ifEmpty {
                    element.select("div.epl-date").text()
                }
            }.replace(Regex("^\\s*\\S+\\s+"), "") // Remove icon text if present

            // Try to extract chapter number from data attribute or URL
            val chapterNumber = element.select("div.chapter-card").attr("data-number").toFloatOrNull()
                ?: finalChpNum.filter { it.isDigit() || it == '.' }.toFloatOrNull()
                ?: url.substringAfterLast('/').toFloatOrNull()
                ?: 0f

            MangaChapter(
                id = generateUid(url),
                title = title,
                number = chapterNumber,
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = dateFormat.parseSafe(dateText),
                branch = null,
                source = source,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select(".image_list img, .image_list canvas").map { img ->
            val url = when {
                img.hasAttr("src") -> img.requireSrc().toRelativeUrl(domain)
                else -> img.attrAsRelativeUrl("data-src")
            }

            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}