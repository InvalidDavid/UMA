package tsuki.site.all.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.OkHttpWebClient
import tsuki.network.WebClient

import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.model.ContentType

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseJson

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("DANBOORU", "Danbooru", type = ContentType.HENTAI)
internal class Danbooru(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.DANBOORU, pageSize = 20), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("danbooru.donmai.us")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val webClient: WebClient by lazy {
        OkHttpWebClient(
            context.httpClient.newBuilder()
                .addInterceptor(this)
                .build(),
            source
        )
    }

    override fun getRequestHeaders(): Headers {
        return super.getRequestHeaders().newBuilder()
            .set("Referer", "https://$domain/")
            .build()
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,      // "updated_at"
        SortOrder.ALPHABETICAL, // "name"
        SortOrder.NEWEST,       // "created_at"
        SortOrder.POPULARITY,   // "post_count"
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        if (query.startsWith("http://") || query.startsWith("https://")) {
            val url = query.toHttpUrlOrNull()
            if (url != null && url.host == domain.toHttpUrl().host && url.pathSegments.size >= 2 && url.pathSegments[0] == "pools") {
                val poolId = url.pathSegments[1]
                return listOf(
                    Manga(
                        id = generateUid(poolId),
                        url = "/pools/$poolId",
                        publicUrl = "https://$domain/pools/$poolId",
                        title = poolId,
                        altTitles = emptySet(),
                        coverUrl = null,
                        rating = RATING_UNKNOWN,
                        contentRating = ContentRating.ADULT,
                        tags = emptySet(),
                        state = null,
                        authors = emptySet(),
                        source = source,
                    )
                )
            }
        }

        val sortParam = when (order) {
            SortOrder.UPDATED -> "updated_at"
            SortOrder.ALPHABETICAL -> "name"
            SortOrder.NEWEST -> "created_at"
            SortOrder.POPULARITY -> "post_count"
            else -> "updated_at"
        }

        val url = "https://$domain/pools/gallery".toHttpUrl().newBuilder()
            .addQueryParameter("search[category]", "series")
            .addQueryParameter("search[order]", sortParam)
            .addQueryParameter("page", page.toString())
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("search[name_contains]", query)
                }
            }
            .build()

        val doc = webClient.httpGet(url).parseHtml()
        val entries = doc.select("article.post-preview")
        return entries.mapNotNull { element ->
            val link = element.selectFirst(".post-preview-link") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = element.selectFirst("div.text-center")?.text() ?: return@mapNotNull null
            val cover = element.selectFirst("source")?.attr("srcset")
                ?.substringAfterLast(',')?.trim()
                ?.substringBeforeLast(' ')?.trimStart()
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = "https://$domain$href",
                title = title,
                altTitles = emptySet(),
                coverUrl = cover,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.publicUrl
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val title = doc.selectFirst(".pool-category-series, .pool-category-collection")?.text()
            ?: doc.selectFirst("h1")?.text() ?: manga.title
        val description = doc.getElementById("description")?.wholeText()
        val author = doc.selectFirst("#description a[href*=artists]")?.ownText()
        val artists = setOfNotNull(author)

        val poolJson = webClient.httpGet("$fullUrl.json").parseJson()
        val postIds = poolJson.getJSONArray("post_ids")
        val updatedAt = poolJson.optString("updated_at", null)

        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until postIds.length()) {
            val postId = postIds.getInt(i)
            chapters.add(
                MangaChapter(
                    id = generateUid("/posts/$postId"),
                    title = "Post ${i + 1}",
                    number = (i + 1).toFloat(),
                    volume = 0,
                    url = "/posts/$postId",
                    uploadDate = 0L,
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            )
        }
        if (chapters.isNotEmpty() && updatedAt != null) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
            val date = dateFormat.parseSafe(updatedAt)
            chapters[0] = chapters[0].copy(uploadDate = date)
        }

        return manga.copy(
            title = title,
            description = description,
            authors = artists,
            chapters = chapters.sortedBy { it.number },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val postId = chapter.url.substringAfterLast("/")
        val json = webClient.httpGet("https://$domain/posts/$postId.json").parseJson()
        val imageUrl = json.optString("file_url", null)
            ?: json.optString("large_file_url", null)
            ?: json.optString("preview_file_url", null)
            ?: throw Exception("Image URL not found for post $postId")
        val fullImageUrl = if (imageUrl.startsWith("http")) imageUrl else "https://$domain$imageUrl"
        return listOf(
            MangaPage(
                id = generateUid(fullImageUrl),
                url = fullImageUrl,
                preview = null,
                source = source,
            )
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host == "cdn.donmai.us") {
            val newRequest = request.newBuilder()
                .removeHeader("Cookie")
                .header("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
                .header("Sec-Fetch-Dest", "image")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "same-site")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }

    private fun SimpleDateFormat.parseSafe(date: String?): Long {
        return date?.let { runCatching { parse(it)?.time }.getOrDefault(0L) } ?: 0L
    }
}
