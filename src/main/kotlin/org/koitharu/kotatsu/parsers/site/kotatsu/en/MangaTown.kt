package org.koitharu.kotatsu.parsers.site.kotatsu.en

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGATOWN", "MangaTown", "en")
internal class MangaTown(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGATOWN, 30) {

    override val configKeyDomain = ConfigKey.Domain("www.mangatown.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isYearSupported = false,
        )


    @Volatile
    private var allTagsCache: List<MangaTag>? = null

    private suspend fun getAllTags(): List<MangaTag> {
        if (allTagsCache == null) {
            allTagsCache = fetchAvailableTags().toList()
        }
        return allTagsCache!!
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getAllTags().toSet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
        ),
        availableDemographics = EnumSet.of(
            Demographic.SHOUJO,
            Demographic.SEINEN,
            Demographic.SHOUNEN,
            Demographic.JOSEI,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHUA,
            ContentType.MANHWA,
        ),
    )


    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()
        val hasFilters = filter.types.isNotEmpty() || filter.demographics.isNotEmpty() ||
                filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() ||
                filter.states.isNotEmpty()

        val url = when {
            hasFilters -> {
                val builder = "https://$domain/search".toHttpUrl().newBuilder()
                    .addQueryParameter("name_method", "cw")
                    .addQueryParameter("name", query ?: "")
                    .addQueryParameter("author_method", "cw")
                    .addQueryParameter("author", "")
                    .addQueryParameter("artist_method", "cw")
                    .addQueryParameter("artist", "")

                builder.addQueryParameter("type",
                    if (filter.types.size == 1) when (filter.types.first()) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        else -> ""
                    } else ""
                )

                builder.addQueryParameter("demographic",
                    if (filter.demographics.size == 1) when (filter.demographics.first()) {
                        Demographic.SEINEN -> "SEINEN"
                        Demographic.SHOUJO -> "SHOUJO"
                        Demographic.SHOUNEN -> "SHOUNEN"
                        Demographic.JOSEI -> "JOSEI"
                        else -> ""
                    } else ""
                )

                if (filter.states.size == 1) {
                    when (filter.states.first()) {
                        MangaState.FINISHED -> builder.addQueryParameter("is_completed", "1")
                        MangaState.ONGOING -> builder.addQueryParameter("is_completed", "0")
                        else -> builder.addQueryParameter("is_completed", "")
                    }
                } else {
                    builder.addQueryParameter("is_completed", "")
                }

                val allTags = getAllTags()
                val includedKeys = filter.tags.map { it.key }.toSet()
                val excludedKeys = filter.tagsExclude.map { it.key }.toSet()
                for (tag in allTags) {
                    val value = when (tag.key) {
                        in includedKeys -> "1"
                        in excludedKeys -> "2"
                        else -> "0"
                    }
                    builder.addQueryParameter("genres[${tag.title}]", value)
                }

                builder.addQueryParameter("released_method", "eq")
                builder.addQueryParameter("released", "")
                builder.addQueryParameter("rating_method", "eq")
                builder.addQueryParameter("rating", "")
                builder.addQueryParameter("advopts", "1")
                builder.addQueryParameter("page", page.toString())

                builder.build().toString()
            }
            !query.isNullOrEmpty() -> {
                // Simple search
                "https://$domain/search?page=$page&name=${query.urlEncoded()}"
            }
            order == SortOrder.UPDATED -> {
                "https://$domain/latest/$page.htm"
            }
            else -> {
                // Directory browse
                buildString {
                    append("https://$domain/directory/")
                    append("0-")
                    append(
                        if (filter.tags.isNotEmpty()) filter.tags.oneOrThrowIfMany()?.key ?: "0"
                        else "0"
                    )
                    append("-0-")
                    append(
                        if (filter.states.isNotEmpty()) {
                            filter.states.oneOrThrowIfMany()?.let {
                                when (it) {
                                    MangaState.ONGOING -> "ongoing"
                                    MangaState.FINISHED -> "completed"
                                    else -> "0"
                                }
                            } ?: "0"
                        } else "0"
                    )
                    append("-0-0/")
                    append(page).append(".htm")
                    append(
                        when (order) {
                            SortOrder.POPULARITY -> ""
                            SortOrder.ALPHABETICAL -> "?name.az"
                            SortOrder.RATING -> "?rating.za"
                            else -> "?last_chapter_time.za"
                        }
                    )
                }
            }
        }

        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        val root = doc.body().selectFirst("ul.manga_pic_list") ?: return emptyList()
        val manga = root.select("li")
        if (manga.isEmpty()) return emptyList()

        return manga.mapNotNull { li ->
            val a = li.selectFirst("a.manga_cover") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            val views = li.select("p.view")
            val status = views.firstNotNullOfOrNull { it.ownText().takeIf { x -> x.startsWith("Status:") } }
                ?.substringAfter(':')?.trim()?.lowercase(Locale.ROOT)
            val author = views.firstNotNullOfOrNull { it.text().takeIf { x -> x.startsWith("Author:") } }
                ?.substringAfter(':')
                ?.trim()
            Manga(
                id = generateUid(href),
                title = a.attr("title"),
                coverUrl = a.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
                source = source,
                altTitles = emptySet(),
                rating = li.selectFirst("p.score")?.selectFirst("b")
                    ?.ownText()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
                authors = setOfNotNull(author),
                state = when (status) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                },
                tags = li.selectFirst("p.keyWord")?.select("a")?.mapToSet tags@{ x ->
                    MangaTag(
                        title = x.attr("title").toTitleCase(),
                        key = x.attr("href").substringAfter("/directory/0-").substringBefore("-0-"),
                        source = source,
                    )
                }.orEmpty(),
                url = href,
                contentRating = null,
                publicUrl = href.toAbsoluteUrl(a.host ?: domain),
            )
        }
    }


    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
        val root = doc.body().selectFirst("section.main")
            ?.selectFirst("div.article_content") ?: return manga
        val info = root.selectFirst("div.detail_info")?.selectFirst("ul")
        val chaptersList = root.selectFirst("div.chapter_content")
            ?.selectFirst("ul.chapter_list")?.select("li")?.asReversed()
        val dateFormat = SimpleDateFormat("MMM dd,yyyy", Locale.US)

        if (root.select("div.chapter_content:contains(has been licensed)").isNotEmpty()) {
            return manga.copy(state = MangaState.ABANDONED, chapters = bypassLicensedChapters(manga))
        }

        return manga.copy(
            tags = manga.tags + (info?.select("li")?.find { x ->
                x.selectFirst("b")?.ownText() == "Genre(s):"
            }?.select("a")?.mapNotNull { a ->
                MangaTag(
                    title = a.attr("title").toTitleCase(),
                    key = a.attr("href").substringAfter("/directory/0-").substringBefore("-0-"),
                    source = source,
                )
            }.orEmpty()),
            description = info?.getElementById("show")?.ownText(),
            chapters = chaptersList?.mapChapters { i, li ->
                val linkEl = li.selectFirst("a") ?: return@mapChapters null
                val href = linkEl.attrAsRelativeUrl("href")
                val name = buildString {
                    append(linkEl.text())
                    li.select("span").filter { span ->
                        !span.hasClass("time") && !span.hasClass("new")
                    }.forEach { span ->
                        if (isNotEmpty()) append(" ")
                        append(span.text())
                    }
                }
                MangaChapter(
                    id = generateUid(href),
                    url = href,
                    source = source,
                    number = i + 1f,
                    volume = 0,
                    uploadDate = parseChapterDate(
                        dateFormat,
                        li.selectFirst("span.time")?.text(),
                    ),
                    title = name.nullIfEmpty(),
                    scanlator = null,
                    branch = null,
                )
            } ?: emptyList(),
        )
    }

    
    private suspend fun bypassLicensedChapters(manga: Manga): List<MangaChapter> {
        val subdomain = "m." + domain.removePrefix("www.")
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(subdomain), getRequestHeaders()).parseHtml()
        val list = doc.body().selectFirst("ul.detail-ch-list") ?: return emptyList()
        val dateFormat = SimpleDateFormat("MMM dd,yyyy", Locale.US)
        return list.select("li").asReversed().mapIndexedNotNull { i, li ->
            val a = li.selectFirst("a") ?: return@mapIndexedNotNull null
            val href = a.attrAsRelativeUrl("href")
            val name = a.selectFirst("span.vol")?.text().orEmpty().ifEmpty { a.ownText() }
            MangaChapter(
                id = generateUid(href),
                url = href,
                source = source,
                number = i + 1f,
                volume = 0,
                uploadDate = parseChapterDate(
                    dateFormat,
                    li.selectFirst("span.time")?.text(),
                ),
                title = name.nullIfEmpty(),
                scanlator = null,
                branch = null,
            )
        }
    }

    
    private fun parseChapterDate(dateFormat: SimpleDateFormat, date: String?): Long {
        return when {
            date.isNullOrEmpty() -> 0L
            date.contains("Today") -> Calendar.getInstance().timeInMillis
            date.contains("Yesterday") -> Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
            else -> dateFormat.parseSafe(date)
        }
    }

    
    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("/directory/".toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
        val root = doc.body().selectFirst("aside.right")
            ?.getElementsContainingOwnText("Genres")
            ?.first()
            ?.nextElementSibling() ?: doc.parseFailed("Root not found")
        return root.select("li").mapNotNullToSet { li ->
            val a = li.selectFirst("a") ?: return@mapNotNullToSet null
            val key = a.attr("href").substringAfter("/directory/0-").substringBefore("-0-")
            MangaTag(
                source = source,
                key = key,
                title = a.text().toTitleCase(),
            )
        }
    }

    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl, getRequestHeaders()).parseHtml()
        val pageSelect = doc.body().selectFirst("div.page_select select") ?: return emptyList()
        val options = pageSelect.select("option").filterNot { it.attr("value").endsWith("featured.html") }
        if (options.isNotEmpty()) {
            return options.mapIndexed { _, option ->
                val href = option.attrAsRelativeUrl("value")
                MangaPage(
                    id = generateUid(href),
                    url = href,
                    preview = null,
                    source = source,
                )
            }
        }
        val imgElements = doc.select("div#viewer img")
        return imgElements.map { img ->
            val src = img.attrAsAbsoluteUrl("src")
            MangaPage(
                id = generateUid(src),
                url = src,
                preview = null,
                source = source,
            )
        }
    }

    
    override suspend fun getPageUrl(page: MangaPage): String {
        if (page.url.startsWith("http")) return page.url
        val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
        return doc.selectFirst("div#viewer img")?.attrAsAbsoluteUrl("src")
            ?: throw Exception("Could not find image")
    }

    
    private fun String.nullIfEmpty(): String? = ifEmpty { null }
}
