package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException
import tsuki.network.OkHttpWebClient

import tsuki.model.*
import tsuki.util.*

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
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
    @Volatile
    private var publisherList: List<Pair<String, Int>>? = null
    private var filterFetchFailed = false

    private val rawHttpClient: OkHttpClient by lazy {
        context.httpClient.newBuilder()
            .addInterceptor(::refererInterceptor)
            .addInterceptor(::dleGuardInterceptor)
            .build()
    }

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
        if (genreList != null && publisherList != null) return
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

            genreList = parseFilterValues(filterItems, "g")
            publisherList = parseFilterValues(filterItems, "p")
        } catch (_: Exception) {
            filterFetchFailed = true
        }
    }

    private fun parseFilterValues(filterItems: JSONObject, key: String): List<Pair<String, Int>> {
        val obj = filterItems.optJSONObject(key) ?: return emptyList()
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
        publisherList?.forEach { (name, id) ->
            tags += MangaTag(name, "p_$id", source)
        }
        return MangaListFilterOptions(
            availableTags = tags,
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
        if (query != null) return searchManga(query, page)

        val urlBuilder = StringBuilder().apply {
            append("https://$domain/ComicList/")
            val pIds = filter.tags.filter { it.key.startsWith("p_") }.map { it.key.removePrefix("p_") }
            val gIds = filter.tags.filter { it.key.startsWith("g_") }.map { it.key.removePrefix("g_") }
            if (pIds.isNotEmpty()) append("p=${pIds.joinToString(",")}/")
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

    private suspend fun searchManga(query: String, page: Int): List<Manga> {
        val encoded = query.urlEncoded()
        val url = if (page == 1) "https://$domain/search/$encoded/"
        else "https://$domain/search/$encoded/page/$page/"
        return parseMangaList(apiClient.httpGet(url).parseHtml())
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

    @get:Synchronized
    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    override suspend fun getDetails(manga: Manga): Manga {
        detailsCache[manga.url]?.let { return it }

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
        detailsCache[manga.url] = result
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
