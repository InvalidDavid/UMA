package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.*

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANHWAREAD", "ManhwaRead", "en", ContentType.HENTAI)
internal class ManhwaRead(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANHWAREAD, pageSize = 12) {

    override val configKeyDomain = ConfigKey.Domain("manhwaread.com")
    private val baseUrl = "https://$domain"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
//        SortOrder.ALPHABETICAL,          // A-Z
//        SortOrder.RATING,                // rating
//        SortOrder.POPULARITY,            // all time
//        SortOrder.POPULARITY_TODAY,      // daily
//        SortOrder.POPULARITY_WEEK,       // weekly
//        SortOrder.POPULARITY_MONTH,      // monthly
//        SortOrder.POPULARITY_YEAR,       // yearly
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
        isSearchWithFiltersSupported = true,
    )

    companion object {
        val GENRE_ID_MAP = mapOf(
            "Action" to "650", "Adventure" to "645", "Comedy" to "536",
            "Drama" to "530", "Ecchi" to "537", "Fantasy" to "646",
            "Hentai" to "531", "Horror" to "590", "Isekai" to "2735",
            "Mahou Shoujo" to "2696", "Mystery" to "626", "Psychological" to "591",
            "Romance" to "538", "Sci-Fi" to "688", "Slice of Life" to "532",
            "Sports" to "677", "Supernatural" to "544", "Thriller" to "580"
        )
        val allTags: Set<MangaTag> = run {
            val genreTags = GENRE_ID_MAP.keys.map { name ->
                MangaTag(name, "genre:${GENRE_ID_MAP[name]}", MangaParserSource.MANHWAREAD)
            }
            genreTags.toSet()
        }
    }

    private val tags by lazy { allTags }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = tags,
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortBy = when (order) {
            SortOrder.UPDATED -> "release"
            SortOrder.NEWEST -> "new"
//            SortOrder.ALPHABETICAL -> "alphabet"
//            SortOrder.RATING -> "rating"
//            SortOrder.POPULARITY -> "all_top"
//            SortOrder.POPULARITY_TODAY -> "daily_top"
//            SortOrder.POPULARITY_WEEK -> "weekly_top"
//            SortOrder.POPULARITY_MONTH -> "monthly_top"
//            SortOrder.POPULARITY_YEAR -> "yearly_top"
            // doenst work idk why? search resulsts are different too
            else -> "release"
        }

        val url = buildString {
            append(baseUrl)
            if (page > 1) {
                append("/page/")
                append(page)
                append("/")
            }

            val params = linkedMapOf<String, String?>()
            params["s"] = filter.query ?: ""
            params["sortby"] = sortBy

            if (!filter.query.isNullOrBlank()) {
                params["keyword_mode"] = "contain"
                params["s_mode"] = "AND"
            }

            filter.states.oneOrThrowIfMany()?.let { state ->
                params["status"] = when (state) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    MangaState.PAUSED -> "on-hold"
                    MangaState.ABANDONED -> "canceled"
                    else -> null
                }
            }

            append(params.toQueryString())

            val includedGenres = mutableListOf<String>()
            val includedTags = mutableListOf<String>()
            val excludedTags = mutableListOf<String>()

            filter.tags.forEach { tag ->
                val key = tag.key
                when {
                    key.startsWith("genre:") -> includedGenres.add(key.removePrefix("genre:"))
                    key.startsWith("tag:") -> includedTags.add(key.removePrefix("tag:"))
                }
            }
            filter.tagsExclude.forEach { tag ->
                val key = tag.key
                when {
                    key.startsWith("tag:") -> excludedTags.add(key.removePrefix("tag:"))
                }
            }

            includedGenres.forEach { append("&genres[]=$it") }
            includedTags.forEach { append("&including[]=$it") }
            excludedTags.forEach { append("&excluding[]=$it") }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".main-container .manga-item").mapNotNull { card ->
            val link = card.selectFirst("a.manga-item__link") ?: return@mapNotNull null
            val href = link.absUrl("href")
            val title = link.text().trim()
            val cover = card.selectFirst(".manga-item__img img")?.absUrl("src")
            Manga(
                id = generateUid(href),
                url = href.removePrefix(baseUrl),
                publicUrl = href,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    @get:Synchronized
    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    override suspend fun getDetails(manga: Manga): Manga {
        detailsCache[manga.url]?.let { return it }

        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val title = doc.selectFirst("#mangaSummary .manga-titles h1")?.text() ?: manga.title
        val cover = doc.selectFirst("head meta[property=og:image]")?.absUrl("content") ?: manga.coverUrl

        val author = doc.select("#mangaSummary .text-primary:contains(Author:) + .flex a span:first-child").text().trim()
        val artist = doc.select("#mangaSummary .text-primary:contains(Artist:) + .flex a span:first-child").text().trim()
        val publisher = doc.select("#mangaSummary .text-primary:contains(Publisher:) + .flex a span:first-child").text().trim()

        val statusText = doc.selectFirst("#mangaSummary .manga-status")?.attr("data-status")
        val state = when (statusText) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "canceled" -> MangaState.ABANDONED
            "on-hold" -> MangaState.PAUSED
            else -> null
        }

        val genres = doc.select("#mangaSummary .manga-genres a").map { it.text() }
        val tags = doc.select("#mangaSummary .text-primary:contains(Tags:) + .flex a span:first-child").map { it.text() }
        val allTags = (genres + tags).map { name ->
            val genreId = GENRE_ID_MAP[name]
            if (genreId != null) MangaTag(name, "genre:$genreId", source)
            else MangaTag(name, "tag:$name", source)
        }.toSet()

        val descBuilder = StringBuilder()
        if (publisher.isNotEmpty()) descBuilder.append("Publisher: $publisher\n")
        doc.selectFirst("#mangaDesc > .manga-desc__content")?.text()?.let { descBuilder.append("\n$it") }
        doc.selectFirst("#mangaSummary .manga-titles h2")?.text()?.split("|")?.joinToString("\n") { "- " + it.trim() }?.let { altTitles ->
            if (altTitles.isNotEmpty()) descBuilder.append("\n\nAlternative titles:\n$altTitles")
        }

        val chapters = parseChapters(doc)

        val result = manga.copy(
            title = title,
            coverUrl = cover,
            authors = setOfNotNull(author.takeIf { it.isNotEmpty() }, artist.takeIf { it.isNotEmpty() }),
            state = state,
            tags = allTags,
            description = descBuilder.toString().trim(),
            chapters = chapters,
        )

        detailsCache[manga.url] = result
        return result
    }

    private fun parseChapters(doc: Document): List<MangaChapter> {
        return doc.select("#chaptersList > a.chapter-item").mapNotNull { el ->
            val href = el.absUrl("href")
            val name = el.selectFirst("span.chapter-item__name")?.text() ?: return@mapNotNull null
            val dateStr = el.selectFirst("span.chapter-item__date")?.text()
            val uploadDate = dateStr?.let { dateFormat.parseSafe(it) } ?: 0L
            MangaChapter(
                id = generateUid(href),
                title = name,
                number = 0f,
                volume = 0,
                url = href.removePrefix(baseUrl),
                uploadDate = uploadDate,
                scanlator = null,
                branch = null,
                source = source,
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val script = doc.selectFirst("script:containsData(chapterData)")
            ?: throw Exception("Chapter data not found")
        val dataMatch = CHAPTER_DATA_REGEX.find(script.data()) ?: throw Exception("Chapter data regex failed")
        val jsonStr = dataMatch.groupValues[1]
        val chapterData = JSONObject(jsonStr)
        val base = chapterData.getString("base")
        val encodedData = chapterData.getString("data")
        val decodedBytes = Base64.getDecoder().decode(encodedData)
        val decodedStr = String(decodedBytes, StandardCharsets.UTF_8)
        val imagesArray = JSONArray(decodedStr)

        return (0 until imagesArray.length()).map { i ->
            val imgObj = imagesArray.getJSONObject(i)
            val imgSrc = imgObj.getString("src")
            val imgUrl = "$base/$imgSrc"
            MangaPage(
                id = generateUid(imgUrl),
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    private fun SimpleDateFormat.parseSafe(date: String): Long {
        return try { parse(date)?.time ?: 0L } catch (_: Exception) { 0L }
    }

    private fun Map<String, String?>.toQueryString(): String {
        val sb = StringBuilder()
        for ((key, value) in this) {
            if (!value.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append("&")
                sb.append(key).append("=").append(value.urlEncoded())
            }
        }
        return if (sb.isNotEmpty()) "?$sb" else ""
    }
    private val CHAPTER_DATA_REGEX = Regex("""var\s+chapterData\s*=\s*(\{.*\})""")
}
