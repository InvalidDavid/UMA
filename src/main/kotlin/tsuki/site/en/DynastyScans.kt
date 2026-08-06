package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

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

import tsuki.util.attrAsAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.flattenTo
import tsuki.util.generateUid
import tsuki.util.mapToSet
import tsuki.util.nullIfEmpty
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.parseSafe
import tsuki.util.removeSuffix
import tsuki.util.selectFirstOrThrow
import tsuki.util.toAbsoluteUrl
import tsuki.util.toTitleCase
import tsuki.util.urlEncoded

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

private const val SERIES_TYPE = "Series"
private const val ANTHOLOGY_TYPE = "Anthology"
private const val DOUJIN_TYPE = "Doujin"
private const val ISSUE_TYPE = "Issue"

private const val SERIES_DIR = "series"
private const val CHAPTERS_DIR = "chapters"
private const val ANTHOLOGIES_DIR = "anthologies"
private const val DOUJINS_DIR = "doujins"
private const val ISSUES_DIR = "issues"

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

@MangaSourceParser("DYNASTYSCANS", "Dynasty Scans", "en")
internal class DynastyScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DYNASTYSCANS, 20) {

    override val configKeyDomain = ConfigKey.Domain("dynasty-scans.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    @Volatile
    private var tagsCache: Set<MangaTag>? = null

    private suspend fun fetchAvailableTags(): Set<MangaTag> = coroutineScope {
        if (tagsCache != null) return@coroutineScope tagsCache!!
        val tags = (1..3).map { page -> async { getTags(page) } }.awaitAll().flattenTo(LinkedHashSet())
        tagsCache = tags
        tags
    }

    private suspend fun getTags(page: Int): Set<MangaTag> {
        val url = "https://$domain/tags?page=$page"
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select(".tag-list a").mapToSet {
            MangaTag(
                key = it.attr("href").removeSuffix('/').substringAfterLast('/'),
                title = it.text().toTitleCase(sourceLocale),
                source = source,
            )
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrEmpty()) {
            return searchList(page, filter)
        }

        if (filter.tags.isNotEmpty()) {
            val tag = filter.tags.oneOrThrowIfMany()
            val url = "https://$domain/tags/${tag?.key}?view=groupings&page=$page"
            return parseMangaList(webClient.httpGet(url).parseHtml())
        }

        val url = "https://$domain/chapters/added.json?page=$page"
        val json = webClient.httpGet(url).parseJson()
        val chaptersArray = json.getJSONArray("chapters")
        val totalPages = json.getInt("total_pages")

        val entries = LinkedHashSet<MangaEntry>()
        for (i in 0 until chaptersArray.length()) {
            val chapter = chaptersArray.getJSONObject(i)
            val tags = chapter.getJSONArray("tags")
            var isSeries = false

            for (j in 0 until tags.length()) {
                val tag = tags.getJSONObject(j)
                val type = tag.getString("type")
                val name = tag.getString("name")
                val permalink = tag.getString("permalink")

                if (type in setOf(SERIES_TYPE, ANTHOLOGY_TYPE, DOUJIN_TYPE, ISSUE_TYPE)) {
                    entries.add(MangaEntry(url = "/${type.directory()}/$permalink", title = name))
                    isSeries = isSeries || type == SERIES_TYPE
                }
            }

            if (!isSeries) {
                entries.add(
                    MangaEntry(
                        url = "/$CHAPTERS_DIR/${chapter.getString("permalink")}",
                        title = chapter.getString("title")
                    )
                )
            }
        }

        return entries.map { entry ->
            Manga(
                id = generateUid(entry.url),
                url = entry.url,
                publicUrl = entry.url.toAbsoluteUrl(domain),
                title = entry.title,
                altTitles = emptySet(),
                coverUrl = null,
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }.take(if (page < totalPages) pageSize else entries.size)
    }

    private fun parseMangaList(doc: org.jsoup.nodes.Document): List<Manga> {
        return doc.select("li.span2").map { div ->
            val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                title = div.selectFirstOrThrow("div.caption").text(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = div.selectFirstOrThrow("img").attrAsAbsoluteUrl("src"),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    private suspend fun searchList(page: Int, filter: MangaListFilter): List<Manga> {
        val query = filter.query.orEmpty()
        val url = buildString {
            append("https://$domain/search?q=")
            append(query.urlEncoded())
            append("&classes[]=Series&page=$page")
        }
        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("dl.chapter-list dd").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = a.text(),
                altTitles = emptySet(),
                coverUrl = null,
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val (directory, permalink) = manga.url.trim('/').split('/').let { it[0] to it[1] }
        val jsonUrl = "https://$domain/$directory/$permalink.json"
        val json = webClient.httpGet(jsonUrl).parseJson()

        if (directory == CHAPTERS_DIR) {
            return chapterDetails(json, manga)
        }

        val data = parseMangaResponse(json)
        val authors = LinkedHashSet<String>()
        val tagNames = LinkedHashSet<String>()
        val statuses = LinkedHashSet<String>()

        data.tags.forEach { tag ->
            when (tag.type) {
                "Author" -> authors.add(tag.name)
                "General" -> tagNames.add(tag.name)
                "Status" -> statuses.add(tag.name)
            }
        }

        data.taggings.filterIsInstance<ChapterItem.Chapter>().forEach { chapter ->
            chapter.tags.forEach { tag ->
                when (tag.type) {
                    "Author" -> authors.add(tag.name)
                    "General" -> tagNames.add(tag.name)
                }
            }
        }

        val chapters = fetchAllChapters(data)

        return manga.copy(
            title = data.name,
            coverUrl = data.cover?.let { "https://$domain$it" },
            description = buildString {
                data.description?.let { desc ->
                    append(org.jsoup.Jsoup.parseBodyFragment(desc).wholeText().trim())
                    append("\n\n")
                }
                append("Type: ${data.type}\n")
                if (authors.isNotEmpty()) {
                    append("Authors: ${authors.joinToString(", ")}\n")
                }
            }.trim(),
            authors = authors.toSet(),
            tags = tagNames.map { MangaTag(it, it.lowercase().replace(" ", "-"), source) }.toSet(),
            state = when {
                statuses.any { it.equals("Ongoing", ignoreCase = true) } -> MangaState.ONGOING
                statuses.any { it.equals("Completed", ignoreCase = true) } -> MangaState.FINISHED
                statuses.any { it.equals("On Hiatus", ignoreCase = true) } -> MangaState.PAUSED
                statuses.any { it == "Dropped" || it == "Cancelled" || it == "Abandoned" } -> MangaState.ABANDONED
                else -> null
            },
            chapters = chapters,
        )
    }

    private fun chapterDetails(json: JSONObject, manga: Manga): Manga {
        val data = parseChapterResponse(json)
        return manga.copy(
            title = data.title,
            coverUrl = data.pages.firstOrNull()?.let { "https://$domain${it.url}" },
            description = "Type: Chapter\nReleased: ${data.releasedOn}",
            state = MangaState.FINISHED,
            chapters = listOf(
                MangaChapter(
                    id = generateUid("/$CHAPTERS_DIR/${data.permalink}"),
                    title = "Chapter",
                    number = 1f,
                    url = "/$CHAPTERS_DIR/${data.permalink}",
                    uploadDate = dateFormat.parseSafe(data.releasedOn),
                    source = source,
                    scanlator = data.tags.filter { it.type == "Scanlator" }.joinToString { it.name }.nullIfEmpty(),
                    volume = 0,
                    branch = null,
                )
            ),
        )
    }

    private suspend fun fetchAllChapters(data: MangaResponse): List<MangaChapter> = coroutineScope {
        val firstPage = data.taggings
        val totalPages = data.totalPages

        val deferred = (2..totalPages).map { page ->
            async {
                val url = "https://$domain/${data.directory}/${data.permalink}.json?page=$page"
                parseMangaResponse(webClient.httpGet(url).parseJson()).taggings
            }
        }
        val allTaggings = firstPage + deferred.awaitAll().flatten()

        var header: String? = null
        val result = mutableListOf<MangaChapter>()

        allTaggings.forEach { item ->
            when (item) {
                is ChapterItem.Header -> header = item.header
                is ChapterItem.Chapter -> {
                    var name = header?.let { "$it ${item.title}" } ?: item.title
                    if (data.type != SERIES_TYPE) {
                        name += item.tags.filter { it.type == "Author" }
                            .joinToString(prefix = " by ", separator = " and ") { it.name }
                    }
                    result.add(
                        MangaChapter(
                            id = generateUid("/$CHAPTERS_DIR/${item.permalink}"),
                            title = name,
                            number = (result.size + 1).toFloat(),
                            url = "/$CHAPTERS_DIR/${item.permalink}",
                            uploadDate = dateFormat.parseSafe(item.releasedOn),
                            source = source,
                            scanlator = item.tags.filter { it.type == "Scanlator" }.joinToString { it.name }.nullIfEmpty(),
                            volume = 0,
                            branch = null,
                        )
                    )
                }
            }
        }

        if (data.type != DOUJIN_TYPE) result.sortedBy { it.number } else result
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val permalink = chapter.url.substringAfterLast('/')
        val jsonUrl = "https://$domain/$CHAPTERS_DIR/$permalink.json"
        val json = webClient.httpGet(jsonUrl).parseJson()
        val data = parseChapterResponse(json)
        return data.pages.mapIndexed { _, page ->
            MangaPage(
                id = generateUid(page.url),
                url = "https://$domain${page.url}",
                preview = null,
                source = source,
            )
        }
    }

    private data class MangaResponse(
        val name: String,
        val type: String,
        val permalink: String,
        val directory: String,
        val cover: String?,
        val description: String?,
        val tags: List<BrowseTag>,
        val taggings: List<ChapterItem>,
        val totalPages: Int,
    )

    private sealed class ChapterItem {
        data class Header(val header: String) : ChapterItem()
        data class Chapter(
            val title: String,
            val permalink: String,
            val releasedOn: String,
            val tags: List<BrowseTag>,
        ) : ChapterItem()
    }

    private data class ChapterResponse(
        val title: String,
        val permalink: String,
        val releasedOn: String,
        val tags: List<BrowseTag>,
        val pages: List<Page>,
    )

    private data class Page(val url: String)
    private data class BrowseTag(val type: String, val name: String, val permalink: String)
    private data class MangaEntry(val url: String, val title: String)

    private fun parseMangaResponse(json: JSONObject): MangaResponse {
        val name = json.getString("name")
        val type = json.getString("type")
        val permalink = json.getString("permalink")
        val directory = when (type) {
            SERIES_TYPE -> SERIES_DIR
            ANTHOLOGY_TYPE -> ANTHOLOGIES_DIR
            DOUJIN_TYPE -> DOUJINS_DIR
            ISSUE_TYPE -> ISSUES_DIR
            else -> throw Exception("Unknown type: $type")
        }
        val cover = json.optString("cover", null)
        val description = json.optString("description", null)
        val tags = json.getJSONArray("tags").parseTags()
        val taggings = json.optJSONArray("taggings")?.parseTaggings() ?: emptyList()
        val totalPages = json.optInt("total_pages", 1)
        return MangaResponse(name, type, permalink, directory, cover, description, tags, taggings, totalPages)
    }

    private fun parseChapterResponse(json: JSONObject): ChapterResponse {
        val title = json.getString("title")
        val permalink = json.getString("permalink")
        val releasedOn = json.getString("released_on")
        val tags = json.getJSONArray("tags").parseTags()
        val pagesArray = json.getJSONArray("pages")
        val pages = (0 until pagesArray.length()).map { i ->
            val pageObj = pagesArray.getJSONObject(i)
            Page(pageObj.getString("url"))
        }
        return ChapterResponse(title, permalink, releasedOn, tags, pages)
    }

    private fun JSONArray.parseTags(): List<BrowseTag> = (0 until length()).map { i ->
        val obj = getJSONObject(i)
        BrowseTag(obj.getString("type"), obj.getString("name"), obj.getString("permalink"))
    }

    private fun JSONArray.parseTaggings(): List<ChapterItem> = (0 until length()).map { i ->
        val obj = getJSONObject(i)
        if (obj.has("header")) {
            ChapterItem.Header(obj.getString("header"))
        } else {
            ChapterItem.Chapter(
                title = obj.getString("title"),
                permalink = obj.getString("permalink"),
                releasedOn = obj.getString("released_on"),
                tags = obj.getJSONArray("tags").parseTags(),
            )
        }
    }

    private fun String.directory() = when (this) {
        SERIES_TYPE -> SERIES_DIR
        ANTHOLOGY_TYPE -> ANTHOLOGIES_DIR
        DOUJIN_TYPE -> DOUJINS_DIR
        ISSUE_TYPE -> ISSUES_DIR
        else -> throw IllegalArgumentException("Unknown type: $this")
    }
}
