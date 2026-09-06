package tsuki.site.ru

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

import tsuki.model.ContentRating
import tsuki.model.ContentType
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
import tsuki.model.YEAR_UNKNOWN

import tsuki.util.Set
import tsuki.util.attrAsAbsoluteUrl
import tsuki.util.attrAsAbsoluteUrlOrNull
import tsuki.util.attrAsRelativeUrl
import tsuki.util.generateUid
import tsuki.util.insertCookies
import tsuki.util.json.getFloatOrDefault
import tsuki.util.json.getIntOrDefault
import tsuki.util.json.getStringOrNull
import tsuki.util.mapNotNullToSet
import tsuki.util.nullIfEmpty
import tsuki.util.parseHtml
import tsuki.util.parseSafe
import tsuki.util.selectFirstOrThrow
import tsuki.util.splitByWhitespace
import tsuki.util.suspendlazy.getOrNull
import tsuki.util.suspendlazy.suspendLazy
import tsuki.util.textOrNull
import tsuki.util.toAbsoluteUrl
import tsuki.util.toTitleCase
import tsuki.util.urlEncoded

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("COMX", "Com-X", "ru", ContentType.COMICS)
internal class ComX(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMX, 20) {

    override val configKeyDomain = ConfigKey.Domain("com-x.life", "comx.life")

    private val availableTags = suspendLazy(initializer = ::fetchTags)
    private val cdnImageUrl = "img.com-x.life/comix/"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    init {
        context.cookieJar.insertCookies(domain, "adt-accepted", "1")
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isYearRangeSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags.get(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val urlBuilder = StringBuilder()
        when {
            !filter.query.isNullOrEmpty() -> {
                val encodedQuery = filter.query.splitByWhitespace().joinToString(separator = "%20") { part ->
                    part.urlEncoded()
                }
                urlBuilder.append("/search/")
                urlBuilder.append(encodedQuery)
                if (page > 1) {
                    urlBuilder.append("/page/$page/")
                }
            }

            else -> {
                urlBuilder.append("/ComicList")
                if (filter.yearFrom != YEAR_UNKNOWN) {
                    urlBuilder.append("/y[from]=${filter.yearFrom}")
                }
                if (filter.yearTo != YEAR_UNKNOWN) {
                    urlBuilder.append("/y[to]=${filter.yearTo}")
                }
                if (filter.tags.isNotEmpty()) {
                    urlBuilder.append("/g=")
                    urlBuilder.append(filter.tags.joinToString(",") { it.key })
                }
                urlBuilder.append("/sort")
                if (page > 1) {
                    urlBuilder.append("/page/$page/")
                }
            }
        }

        val fullUrl = urlBuilder.toString().toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select("div.readed.d-flex.short").map { item ->
            val a = item.selectFirstOrThrow("a.readed__img.img-fit-cover.anim")
            val img = item.selectFirst("img[data-src]")
            val href = a.attrAsRelativeUrl("href")
            val titleElement = item.selectFirstOrThrow("h3.readed__title a")
            val (mainTitle, altTitle) = titleElement.text()
                .split("\\s*/\\s*".toRegex())
                .map { it.trim() }
                .let { parts ->
                    when {
                        parts.size >= 2 -> parts[1] to parts[0]
                        parts.isNotEmpty() -> parts[0] to ""
                        else -> "" to ""
                    }
                }

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = a.attrAsAbsoluteUrl("href"),
                title = mainTitle,
                altTitles = if (altTitle.isNotEmpty()) setOf(altTitle) else emptySet(),
                authors = emptySet(),
                description = null,
                tags = emptySet(),
                rating = RATING_UNKNOWN,
                state = null,
                coverUrl = img?.attrAsAbsoluteUrlOrNull("data-src"),
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)

        val scriptData = doc.selectFirst("script:containsData(__DATA__)")?.data()
            ?.substringAfter("window.__DATA__ = ")
            ?.substringBefore(";</script>")
            ?.trim()
            ?: throw ParseException("Script data not found", manga.url)

        val jsonData = JSONObject(scriptData)
        val chaptersJson = jsonData.getJSONArray("chapters")
        val newsId = jsonData.getLong("news_id")

        val chapters = List(chaptersJson.length()) { i ->
            val chapter = chaptersJson.getJSONObject(i)
            val chapterId = chapter.getLong("id")

            MangaChapter(
                id = generateUid("$newsId/$chapterId"),
                url = "/reader/$newsId/$chapterId",
                number = chapter.getFloatOrDefault("posi", 0f),
                title = decodeText(chapter.getStringOrNull("title")),
                uploadDate = dateFormat.parseSafe(chapter.getStringOrNull("date")),
                source = source,
                scanlator = null,
                branch = null,
                volume = chapter.getIntOrDefault("volume", 0),
            )
        }.reversed()

        val author = doc.selectFirst("li:contains(Publisher:)")
            ?.textOrNull()
            ?.substringAfter("Publisher:")
            ?.trim()
            ?.nullIfEmpty()
        val state = when (
            doc.selectFirst("li:contains(Release type:)")?.text()?.substringAfter("Release type:")?.trim()
        ) {
            "Ongoing" -> MangaState.ONGOING
            else -> MangaState.FINISHED
        }

        val tagLinks = doc.getElementsByAttributeValueContaining("href", "/genres/")
        val tags = if (tagLinks.isNotEmpty()) {
            availableTags.getOrNull()?.let { allTags ->
                tagLinks.mapNotNullToSet { a ->
                    val tagName = a.text()
                    allTags.find { it.title.equals(tagName, ignoreCase = true) }
                }
            }
        } else {
            null
        }

        return manga.copy(
            authors = setOfNotNull(author),
            state = state,
            chapters = chapters,
            description = doc.select("div.page__text.full-text.clearfix").textOrNull(),
            tags = tags ?: manga.tags,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val newsId = chapter.url.substringAfter("/reader/").substringBefore("/")
        context.cookieJar.insertCookies(domain, "adult=$newsId")

        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val data = doc.selectFirst("script:containsData(__DATA__)")?.data()
            ?.substringAfter("=")
            ?.trim()
            ?.removeSuffix(";")
            ?.substringAfter("\"images\":[")
            ?.substringBefore("]")
            ?.split(",")
            ?.map { it.trim().removeSurrounding("\"").replace("\\", "") }
            ?: throw ParseException("Image data not found", chapter.url)

        return data.map { imageUrl ->
            val finalUrl = "https://$cdnImageUrl$imageUrl"
            MangaPage(
                id = generateUid(imageUrl),
                url = finalUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/comix/").parseHtml()
        val scriptData = doc.selectFirstOrThrow("script:containsData(__XFILTER__)").data()

        val genresJson = scriptData
            .substringAfter("\"g\":{")
            .substringBefore("}}}") + "}"

        val genresObj = JSONObject("{$genresJson}")
        val valuesArray = genresObj.getJSONArray("values")

        return Set(valuesArray.length()) { i ->
            val genre = valuesArray.getJSONObject(i)
            MangaTag(
                key = genre.getInt("id").toString(),
                title = genre.getString("value").toTitleCase(sourceLocale),
                source = source,
            )
        }
    }

    private fun decodeText(text: String?): String? {
        if (text == null) return null
        return text.replace("\\u([0-9a-fA-F]{4})".toRegex()) { matchResult ->
            val codePoint = matchResult.groupValues[1].toInt(16)
            codePoint.toChar().toString()
        }
    }
}
