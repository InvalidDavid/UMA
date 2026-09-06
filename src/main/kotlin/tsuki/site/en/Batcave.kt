package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.network.OkHttpWebClient

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

import tsuki.util.attrAsAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("BATCAVE", "Batcave", "en", ContentType.COMICS)
internal class Batcave(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.BATCAVE, 20) {

    override val configKeyDomain = ConfigKey.Domain("batcave.biz")

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH)

    @Volatile
    private var genreList: List<Pair<String, Int>>? = null
    private var filterFetchFailed = false

    private val rawHttpClient: OkHttpClient by lazy {
        context.httpClient.newBuilder()
            .addInterceptor(::refererInterceptor)
            .addInterceptor(::dleGuardInterceptor)
            .build()
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
        .build()

    private val apiClient: OkHttpWebClient by lazy {
        OkHttpWebClient(rawHttpClient, source)
    }

    private fun refererInterceptor(chain: okhttp3.Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val referer = when {
            url.contains("readcomicsonline.ru") -> "https://readcomicsonline.ru/"
            else -> "https://$domain/"
        }
        return chain.proceed(request.newBuilder().header("Referer", referer).build())
    }

    private fun dleGuardInterceptor(chain: okhttp3.Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)
        if (response.request.url.pathSegments.firstOrNull() != "_c") {
            return response
        }
        response.close()
        val url = if (originalRequest.method == "GET") {
            originalRequest.url.toString()
        } else {
            "https://$domain/"
        }
        context.requestBrowserAction(this, url)
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
        isYearSupported = false,
    )

    private suspend fun fetchFilters() {
        if (genreList != null) return
        if (filterFetchFailed) return

        try {
            val doc = apiClient.httpGet("https://$domain/comix").parseHtml()
            val script = doc.selectFirst("script:containsData(window.__XFILTER__)")?.data()
                ?: throw ParseException("Filter data not found", "https://$domain/comix")

            val rawJson = script
                .substringAfter("window.__XFILTER__ = ")
                .substringBeforeLast(";")
                .trim()
            val root = JSONObject(rawJson)
            val filterItems = root.getJSONObject("filter_items")

            genreList = parseFilterValues(filterItems)
        } catch (_: Exception) {
            filterFetchFailed = true
        }
    }

    private fun parseFilterValues(filterItems: JSONObject): List<Pair<String, Int>> {
        val obj = filterItems.optJSONObject("g") ?: return emptyList()
        val values = obj.optJSONArray("values") ?: return emptyList()
        val result = mutableListOf<Pair<String, Int>>()
        for (i in 0 until values.length()) {
            val item = values.getJSONObject(i)
            result.add(item.getString("value") to item.getInt("id"))
        }
        return result
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        fetchFilters()
        val tags = mutableSetOf<MangaTag>()
        genreList?.forEach { (name, id) ->
            tags += MangaTag(name, "g_$id", source)
        }
        return MangaListFilterOptions(
            availableTags = tags,
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrBlank()) {
            return searchManga(page, filter.query!!)
        }

        val urlBuilder = StringBuilder().apply {
            append("https://$domain/ComicList/")
            val gIds = filter.tags.filter { it.key.startsWith("g_") }.map { it.key.removePrefix("g_") }
            if (gIds.isNotEmpty()) append("g=${gIds.joinToString(",")}/")
            append("sort")
            if (page > 1) append("/page/$page/")
        }
        val url = urlBuilder.toString()

        val sortPair = when (order) {
            SortOrder.POPULARITY -> "rating" to "desc"
            SortOrder.UPDATED -> "date" to "desc"
            else -> "" to ""
        }

        return if (sortPair.first.isEmpty()) {
            parseMangaList(apiClient.httpGet(url).parseHtml())
        } else {
            val formBody = FormBody.Builder()
                .add("dlenewssortby", sortPair.first)
                .add("dledirection", sortPair.second)
                .add("set_new_sort", "dle_sort_xfilter")
                .add("set_direction_sort", "dle_direction_xfilter")
                .build()
            val request = Request.Builder().url(url).post(formBody).build()
            val response = rawHttpClient.newCall(request).execute()
            parseMangaList(response.parseHtml())
        }
    }

    private suspend fun searchManga(page: Int, query: String): List<Manga> {
        val url = "https://$domain".toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment(query)
            .apply {
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                    addPathSegment("")
                }
            }
            .build()
        val html = apiClient.httpGet(url.toString()).body?.string() ?: return emptyList()
        val doc = Jsoup.parse(html, "https://$domain")
        return doc.select("#dle-content > .readed").map { element ->
            val a = element.selectFirst(".readed__title > a") ?: return@map null
            Manga(
                id = generateUid(a.attrAsRelativeUrl("href")),
                url = a.attrAsRelativeUrl("href"),
                publicUrl = a.absUrl("href"),
                title = a.ownText(),
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = element.selectFirst(".readed__img img")?.attrAsAbsoluteUrl("data-src"),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }.filterNotNull()
    }

    private fun parseMangaList(doc: org.jsoup.nodes.Document): List<Manga> {
        return doc.select("#dle-content > .readed").map { element ->
            val a = element.selectFirst(".readed__title > a") ?: return@map null
            val cover = element.selectFirst("img")?.attrAsAbsoluteUrl("data-src")
            Manga(
                id = generateUid(a.attrAsRelativeUrl("href")),
                url = a.attrAsRelativeUrl("href"),
                publicUrl = a.absUrl("href"),
                title = a.ownText(),
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }.filterNotNull()
    }

    private val detailsCacheLock = Any()
    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    override suspend fun getDetails(manga: Manga): Manga {
        synchronized(detailsCacheLock) {
            detailsCache[manga.url]?.let { return it }
        }

        val doc = apiClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.selectFirst("header.page__header h1")?.text() ?: manga.title
        val cover = doc.selectFirst("div.page__poster img")?.absUrl("src")
        val description = doc.selectFirst("div.page__text")?.text()

        val author = doc.selectFirst(".page__list > li:has(> div:contains(Writer)) a")?.text()
            ?: doc.selectFirst(".page__list > li:has(> div:contains(Writer))")?.ownText()
        val state = when (doc.selectFirst(".page__list > li:has(> div:contains(Release type))")?.ownText()?.trim()) {
            "Ongoing" -> MangaState.ONGOING
            else -> MangaState.FINISHED
        }
        val tags = doc.select("div.page__tags a").map { a ->
            MangaTag(a.text(), a.text().lowercase().replace(" ", "-"), source)
        }.toSet() + MangaTag("Comic", "comic", source)

        val script = doc.selectFirst("script:containsData(window.__DATA__)")?.data()
            ?: throw ParseException("Chapter data script not found", manga.url)
        val json = JSONObject(
            script.substringAfter("window.__DATA__ = ").substringBeforeLast(";").trim()
        )
        val newsId = json.getLong("news_id")
        val chaptersArray = json.getJSONArray("chapters")
        val chapters = (0 until chaptersArray.length()).map { i ->
            val ch = chaptersArray.getJSONObject(i)
            MangaChapter(
                id = generateUid("/reader/$newsId/${ch.getInt("id")}"),
                url = "/reader/$newsId/${ch.getInt("id")}",
                title = ch.optString("title"),
                number = ch.optDouble("posi", 0.0).toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = dateFormat.parseSafe(ch.optString("date")),
                branch = null,
                source = source,
            )
        }.reversed()

        val result = manga.copy(
            title = title,
            coverUrl = cover ?: manga.coverUrl,
            description = description,
            authors = setOfNotNull(author),
            state = state,
            tags = tags,
            chapters = chapters,
        )
        synchronized(detailsCacheLock) {
            detailsCache[manga.url] = result
        }
        return result
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (newsId, rawId) = chapter.url.substringAfter("reader/").split("/", limit = 2)
        val id = Regex("^\\d+").find(rawId)?.value ?: rawId
        val jsonBody = JSONObject().apply {
            put("news_id", newsId)
            put("chapter_id", id)
        }.toString()

        val request = Request.Builder()
            .url("https://$domain/engine/ajax/controller.php?mod=api&action=reader/getChapterData")
            .header("Referer", "https://$domain/")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()

        val response = rawHttpClient.newCall(request).execute()
        val json = JSONObject(response.body?.string() ?: throw ParseException("Empty response", chapter.url))
        val data = json.getJSONObject("data")
        val images = data.optJSONArray("images") ?: throw ParseException("No images found", chapter.url)

        return (0 until images.length()).map { i ->
            var img = images.getString(i).trim()
            if (!img.startsWith("http")) img = "https://$domain$img"
            MangaPage(id = generateUid(img), url = img, preview = null, source = source)
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    private fun SimpleDateFormat.parseSafe(date: String?): Long =
        if (date.isNullOrBlank()) 0L else runCatching { parse(date)?.time ?: 0L }.getOrDefault(0L)
}
