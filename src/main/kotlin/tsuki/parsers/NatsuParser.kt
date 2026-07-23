package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentType
import tsuki.model.RATING_UNKNOWN
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.SortOrder
import tsuki.model.MangaState

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.attrAsAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.await
import tsuki.util.parseJsonArray
import tsuki.util.toRelativeUrl
import tsuki.util.urlBuilder

import okhttp3.Request
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.jsoup.HttpStatusException
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import org.jsoup.nodes.Document

internal abstract class NatsuParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    pageSize: Int = 24,
) : PagedMangaParser(context, source, pageSize, pageSize) {

    override val configKeyDomain = ConfigKey.Domain(domain)

    override val sourceLocale: Locale = Locale.ENGLISH

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    @Volatile
    private var genreCache: Set<MangaTag>? = null

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getOrFetchGenres(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA,
            ContentType.COMICS, ContentType.NOVEL,
        ),
    )

    private suspend fun getOrFetchGenres(): Set<MangaTag> {
        genreCache?.let { return it }
        val tags = fetchAvailableTags()
        genreCache = tags
        return tags
    }

    private var nonce: String? = null

    private suspend fun getNonce(): String {
        if (nonce == null) {
            val doc = webClient.httpGet(
                "https://${domain}/wp-admin/admin-ajax.php?type=search_form&action=get_nonce"
            ).parseHtml()
            nonce = doc.selectFirst("input[name=search_nonce]")?.attr("value")
                ?: throw Exception("Unable to get nonce")
        }
        return nonce!!
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        val author = filter.author

        val formParts = mutableMapOf<String, String>()
        formParts["nonce"] = getNonce()

        formParts["inclusion"] = "OR"
        formParts["genre"] = if (filter.tags.isNotEmpty())
            JSONArray(filter.tags.map { it.key }).toString()
        else "[]"

        formParts["exclusion"] = "OR"
        formParts["genre_exclude"] = if (filter.tagsExclude.isNotEmpty())
            JSONArray(filter.tagsExclude.map { it.key }).toString()
        else "[]"

        formParts["page"] = page.toString()

        formParts["author"] = if (!author.isNullOrEmpty())
            JSONArray(author).toString()
        else "[]"

        formParts["artist"] = "[]"
        formParts["project"] = "0"

        formParts["type"] = if (filter.types.isNotEmpty())
            JSONArray(filter.types.mapNotNull { type ->
                when (type) {
                    ContentType.MANGA -> "manga"
                    ContentType.MANHWA -> "manhwa"
                    ContentType.MANHUA -> "manhua"
                    ContentType.COMICS -> "comic"
                    ContentType.NOVEL -> "novel"
                    else -> null
                }
            }).toString()
        else "[]"

        formParts["status"] = if (filter.states.isNotEmpty())
            JSONArray(filter.states.mapNotNull { state ->
                when (state) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    MangaState.PAUSED -> "on-hiatus"
                    else -> null
                }
            }).toString()
        else "[]"

        formParts["order"] = "desc"
        formParts["orderby"] = when (order) {
            SortOrder.UPDATED -> "updated"
            SortOrder.POPULARITY -> "popular"
            SortOrder.ALPHABETICAL -> "title"
            SortOrder.RATING -> "rating"
            else -> "popular"
        }

        if (!query.isNullOrEmpty()) formParts["query"] = query

        val doc = httpPost("https://${domain}/wp-admin/admin-ajax.php?action=advanced_search", formParts)
        return parseMangaList(doc)
    }

    protected open suspend fun parseMangaList(doc: Document): List<Manga> {
        val slugs = doc.select("div > a[href*=/manga/]:has(> img)").mapNotNull { a ->
            a.absUrl("href").toHttpUrlOrNull()?.pathSegments?.getOrNull(1)
        }.filter { it.isNotEmpty() }

        if (slugs.isEmpty()) return emptyList()

        val url = "https://$domain/wp-json/wp/v2/manga".toHttpUrl().newBuilder().apply {
            slugs.forEach { addQueryParameter("slug[]", it) }
            addQueryParameter("per_page", "${slugs.size + 1}")
            addQueryParameter("_embed", null)
        }.build()

        val jsonArray = webClient.httpGet(url).parseJsonArray()
        val mangaMap = mutableMapOf<String, Manga>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val slug = obj.getString("slug")
            val title = obj.getJSONObject("title").getString("rendered")
            val cover = obj.optJSONObject("_embedded")
                ?.optJSONArray("wp:featuredmedia")
                ?.optJSONObject(0)
                ?.optString("source_url", null)
            mangaMap[slug] = Manga(
                id = generateUid("/manga/$slug"),
                url = "/manga/$slug",
                publicUrl = "https://$domain/manga/$slug/",
                title = title,
                coverUrl = cover,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }

        return slugs.mapNotNull { mangaMap[it] }
    }

    @get:Synchronized
    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    override suspend fun getDetails(manga: Manga): Manga {
        detailsCache[manga.url]?.let { return it }

        val slug = manga.url.removePrefix("/manga/").removeSuffix("/")
        val id = getMangaIdFromSlug(slug) ?: return manga

        val url = "https://$domain/wp-json/wp/v2/manga/$id?_embed"
        val obj = webClient.httpGet(url).parseJson()
        val title = obj.getJSONObject("title").getString("rendered")
        val description = obj.getJSONObject("content").getString("rendered")
            .let { org.jsoup.Jsoup.parseBodyFragment(it).wholeText().trim() }
        val cover = obj.optJSONObject("_embedded")
            ?.optJSONArray("wp:featuredmedia")
            ?.optJSONObject(0)
            ?.optString("source_url", null)

        val embedded = obj.optJSONObject("_embedded")
        val terms = embedded?.optJSONArray("wp:term")

        fun getTermNames(taxonomy: String): List<String> {
            if (terms == null) return emptyList()
            for (i in 0 until terms.length()) {
                val termArray = terms.optJSONArray(i) ?: continue
                if (termArray.length() > 0 && termArray.getJSONObject(0).optString("taxonomy") == taxonomy)
                    return (0 until termArray.length()).map { j -> termArray.getJSONObject(j).getString("name") }
            }
            return emptyList()
        }

        val genres = getTermNames("genre") + getTermNames("type")
        val statusList = getTermNames("status")
        val state = when {
            statusList.any { it.equals("Ongoing", ignoreCase = true) } -> MangaState.ONGOING
            statusList.any { it.equals("Completed", ignoreCase = true) } -> MangaState.FINISHED
            statusList.any { it.equals("Cancelled", ignoreCase = true) } -> MangaState.ABANDONED
            statusList.any { it.equals("On Hiatus", ignoreCase = true) } -> MangaState.PAUSED
            else -> null
        }
        val authors = getTermNames("series-author")
        val tags = genres.map { name -> MangaTag(name, name.lowercase().replace(" ", "-"), source) }.toSet()
        val chapters = loadChapters(id.toString(), "https://$domain/manga/$slug/")
        val altTitles = fetchAltTitlesFromPage("https://$domain/manga/$slug/")

        val result = manga.copy(
            title = title,
            description = description,
            coverUrl = cover ?: manga.coverUrl,
            state = state,
            authors = authors.toSet(),
            tags = tags,
            chapters = chapters,
            altTitles = altTitles,
        )

        detailsCache[manga.url] = result
        return result
    }

    private suspend fun getMangaIdFromSlug(slug: String): Int? {
        val url = "https://$domain/wp-json/wp/v2/manga?slug=$slug"
        val jsonArray = webClient.httpGet(url).parseJsonArray()
        return if (jsonArray.length() > 0) jsonArray.getJSONObject(0).getInt("id") else null
    }

    private suspend fun fetchAltTitlesFromPage(pageUrl: String): Set<String> {
        val doc = webClient.httpGet(pageUrl).parseHtml()
        val titleElement = doc.selectFirst("h1[itemprop=name]")
        val altText = titleElement?.nextElementSibling()?.text()
        return altText?.split(',')?.mapNotNull { it.trim().takeIf(String::isNotBlank) }?.toSet() ?: emptySet()
    }

    protected open suspend fun loadChapters(mangaId: String, mangaAbsoluteUrl: String): List<MangaChapter> {
        val headers = Headers.headersOf(
            "HX-Request", "true",
            "HX-Target", "chapter-list",
            "HX-Trigger", "chapter-list",
            "HX-Current-URL", mangaAbsoluteUrl,
            "Referer", mangaAbsoluteUrl,
        )
        return buildList {
            for (page in 1..50) {
                val url = urlBuilder()
                    .addPathSegment("wp-admin")
                    .addPathSegment("admin-ajax.php")
                    .addQueryParameter("manga_id", mangaId)
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("action", "chapter_list")
                val doc = try {
                    webClient.httpGet(url.build(), headers).parseHtml()
                } catch (e: HttpStatusException) {
                    if (e.statusCode == 520) break else throw e
                }
                val elements = doc.select("div#chapter-list > div[data-chapter-number]")
                if (elements.isEmpty()) break
                elements.mapNotNullTo(this) { el ->
                    val a = el.selectFirst("a") ?: return@mapNotNullTo null
                    val href = a.attrAsRelativeUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNullTo null
                    val name = el.selectFirst("div.font-medium span")?.text() ?: ""
                    val number = el.attr("data-chapter-number").toFloatOrNull() ?: -1f
                    val dateText = el.selectFirst("time")?.attr("datetime")
                    val uploadDate = dateFormat.parseSafe(dateText)
                    MangaChapter(
                        id = generateUid(href),
                        title = name,
                        url = href,
                        number = number,
                        volume = 0,
                        scanlator = null,
                        uploadDate = uploadDate,
                        branch = null,
                        source = source,
                    )
                }
            }
        }.reversed()
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private fun SimpleDateFormat.parseSafe(date: String?): Long =
        date?.let { runCatching { parse(it)?.time }.getOrDefault(0L) } ?: 0L

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("main .relative section > img").map { img ->
            val url = img.attrAsAbsoluteUrl("src").toRelativeUrl(domain)
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
        val jsonArray = webClient.httpGet(
            "https://${domain}/wp-json/wp/v2/genre?per_page=100&page=1&orderby=count&order=desc"
        ).parseJsonArray()
        val tags = mutableSetOf<MangaTag>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            val slug = obj.optString("slug").takeIf { it.isNotBlank() } ?: continue
            tags += MangaTag(name, slug, source)
        }
        return tags
    }

    private val multipartHttpClient by lazy { OkHttpClient.Builder().build() }
    protected open suspend fun httpPost(url: String, form: Map<String, String>): Document {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        form.forEach { (k, v) -> body.addFormDataPart(k, v) }
        val request = Request.Builder().url(url).post(body.build())
            .header("User-Agent", config[userAgentKey])
            .header("Referer", "https://${domain}/advanced-search/")
            .header("Origin", "https://${domain}")
            .build()
        val response = multipartHttpClient.newCall(request).await()
        return response.parseHtml()
    }
}
