package org.koitharu.kotatsu.parsers.site.tachiyomi.en.mangak

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.TachiyomiSource
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@TachiyomiSource("MANGAK", "MangaK", "en")
class MangaK : HttpSource() {

    override val name = "MangaK"
    override val baseUrl = "https://mangak.io"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://api.mangak.io"

    // migration from MangaBuddy
    override val id: Long = 5020395055978987501L

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    // Catches 5xx errors from the primary CDN and falls back to the working domain
    private val imageFallbackInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful && request.url.host.matches(IMAGE_FALLBACK_REGEX)) {
            response.close()
            val newUrl = request.url.newBuilder().host("rx.rzyn.net").build()
            return@Interceptor chain.proceed(request.newBuilder().url(newUrl).build())
        }
        response
    }

    override val client = network.client.newBuilder()
        .addInterceptor(imageFallbackInterceptor)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/titles/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "popular")
            addQueryParameter("window", "week")
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "24")
        }.build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchResponseDto>()
        return MangasPage(dto.items.map { it.toSManga() }, dto.hasNext)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/titles/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("sort", "latest")
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "24")
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/titles/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "24")

            if (query.isNotBlank()) {
                val filteredQuery = query
                    .filter { it.isLetterOrDigit() || it == ' ' }
                    .take(50)
                addQueryParameter("q", filteredQuery)
            }

            val includedGenres = mutableListOf<String>()
            val excludedGenres = mutableListOf<String>()

            filters.forEach { filter ->
                when (filter) {
                    is GenreList -> {
                        filter.state.forEach { genre ->
                            when (genre.state) {
                                Filter.TriState.STATE_INCLUDE -> includedGenres.add(genre.value)
                                Filter.TriState.STATE_EXCLUDE -> excludedGenres.add(genre.value)
                            }
                        }
                    }
                    is SortFilter -> {
                        if (filter.selected.isNotBlank()) {
                            addQueryParameter("sort", filter.selected)
                        }
                    }
                    is ContentRatingFilter -> {
                        if (filter.selected.isNotBlank()) {
                            addQueryParameter("content_rating", filter.selected)
                        }
                    }
                    is StatusFilter -> {
                        if (filter.selected.isNotBlank()) {
                            addQueryParameter("status", filter.selected)
                        }
                    }
                    is TypeFilter -> {
                        if (filter.selected.isNotBlank()) {
                            addQueryParameter("type", filter.selected)
                        }
                    }
                    is DemographicFilter -> {
                        if (filter.selected.isNotBlank()) {
                            addQueryParameter("demographic", filter.selected)
                        }
                    }
                    else -> {}
                }
            }

            filters.firstInstanceOrNull<AuthorFilter>()?.state?.takeIf { it.isNotBlank() }?.let {
                addQueryParameter("author", it)
            }
            filters.firstInstanceOrNull<MinChapterFilter>()?.state?.takeIf { it.isNotBlank() }?.let {
                addQueryParameter("min_ch", it)
            }

            if (includedGenres.isNotEmpty()) {
                addQueryParameter("genres", includedGenres.joinToString(","))
            }
            if (excludedGenres.isNotEmpty()) {
                addQueryParameter("exclude", excludedGenres.joinToString(","))
            }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String {
        val path = manga.url.substringBefore("#")
        return baseUrl.toHttpUrl().resolve(path)?.toString() ?: (baseUrl + path)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseNextJsData()
        return dto?.pageProps?.initialManga?.toSManga()
            ?: throw Exception("Could not find manga details")
    }

    // ============================= Chapters ==============================

    override fun getChapterUrl(chapter: SChapter): String =
        baseUrl.toHttpUrl().resolve(chapter.url)?.toString() ?: (baseUrl + chapter.url)

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        if (!manga.url.contains("#")) {
            val idFromDesc = manga.description
                ?.substringAfterLast("Manga ID: ", "")
                ?.substringBefore("\n")
                ?.trim()

            if (!idFromDesc.isNullOrEmpty()) {
                val request = GET("$apiUrl/titles/$idFromDesc/chapters?cv=${System.currentTimeMillis()}", headers)
                return client.newCall(request).asObservable().map { chapterListParse(it) }
            }

            return client.newCall(mangaDetailsRequest(manga)).asObservable().map { response ->
                val dto = response.parseNextJsData()
                val id = dto?.pageProps?.initialManga?.id
                    ?: throw Exception("Could not find manga ID for migration")
                val request = GET("$apiUrl/titles/$id/chapters?cv=${System.currentTimeMillis()}", headers)
                client.newCall(request).execute().let { chapterListParse(it) }
            }
        }
        return super.fetchChapterList(manga)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("#")
        return GET("$apiUrl/titles/$id/chapters?cv=${System.currentTimeMillis()}", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = response.parseAs<ChapterListResponseDto>().chapters
        // The website uses the API's `chapter_number` field as an internal sorting index,
        // not the actual chapter number. This dictates the site's canonical reading order,
        // allowing them to correctly interleave volume extras and side stories between main chapters.
        return chapters.sortedByDescending { it.chapterNumber ?: 0f }
            .map { it.toSChapter(dateFormat) }
    }

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.parseNextJsData()
        val images = dto?.pageProps?.initialChapter?.images
            ?: throw Exception("Could not find chapter images")
        return images.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Next.js parsing ======================

    /**
     * Parses the Next.js __NEXT_DATA__ JSON embedded in the page HTML.
     * Replaces keiyoushi's extractNextJs utility which is not available in UMA/Usagi.
     */
    private fun Response.parseNextJsData(): NextJsDto? {
        val html = body.string()
        val doc = Jsoup.parse(html)
        val raw = doc.selectFirst("script#__NEXT_DATA__")?.data()
            ?: return null
        return try {
            // __NEXT_DATA__ root is { props: { pageProps: { ... } } }
            val root = json.decodeFromString<NextJsRootDto>(raw)
            root.props
        } catch (_: Exception) {
            null
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList {
        return FilterList(
            SortFilter(),
            ContentRatingFilter(),
            StatusFilter(),
            TypeFilter(),
            DemographicFilter(),
            Filter.Separator(),
            AuthorFilter(),
            MinChapterFilter(),
            Filter.Separator(),
            Filter.Header("Genres"),
        )
    }

    // ============================= Preferences ===========================

    companion object {
        private val IMAGE_FALLBACK_REGEX = "rx\\.qvzr[a-z]\\.org".toRegex()
    }
}