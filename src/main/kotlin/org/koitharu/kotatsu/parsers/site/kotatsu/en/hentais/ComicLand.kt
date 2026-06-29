package org.koitharu.kotatsu.parsers.site.kotatsu.en.hentais

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.urlEncoded

@MangaSourceParser("COMICLAND", "ComicLand", "en", ContentType.HENTAI)
internal class ComicLand(context: MangaLoaderContext) : PagedMangaParser(
    context, MangaParserSource.COMICLAND, 20) {

    override val configKeyDomain =
        ConfigKey.Domain("comicland.org")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val newRequest = request.newBuilder()
            .header(
                "Origin",
                "https://comicland.org"
            )
            .header(
                "Referer",
                "https://comicland.org/"
            )
            .build()

        return chain.proceed(newRequest)
    }

    override val availableSortOrders =
        setOf(
            SortOrder.UPDATED,      // Recommended
            SortOrder.POPULARITY,   // Popular
            SortOrder.NEWEST        // Official/Ongoing equivalent
        )

    override val filterCapabilities =
        MangaListFilterCapabilities(
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isYearSupported = false,
            isAuthorSearchSupported = false
        )

    private val api =
        "https://api.comicland.org/api"


    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {


        val offset = page * 20
        val endpoint =
            if (!filter.query.isNullOrBlank()) {
                "$api/comic/search?q=${filter.query!!.urlEncoded()}"
            } else {
                val category =
                    filter.tags
                        .firstOrNull()
                        ?.key
                when(category) {
                    "official" ->
                        "$api/comics/official"
                    "ongoing" ->
                        "$api/comics"
                    "popular" ->
                        "$api/comics/popular"
                    else ->
                        "$api/comics"
                }
            }

        val separator =
            if (endpoint.contains("?")) "&" else "?"

        val response =
            webClient.httpGet(
                "$endpoint${separator}offset=$offset&limit=20"
            )

        val root =
            JSONObject(response.body.string())

        val data =
            root.optJSONObject("data")
                ?: return emptyList()

        val array =
            data.optJSONArray("list")
                ?: data.optJSONArray("items")
                ?: return emptyList()

        val result = ArrayList<Manga>()
        for (i in 0 until array.length()) {
            val comic =
                array.getJSONObject(i)
            val slug =
                comic.optString("slug")
            result.add(
                Manga(
                    id = generateUid(slug),
                    title = comic.optString("title"),
                    url = slug,
                    publicUrl = "https://comicland.org/comic/$slug",
                    coverUrl = comic.optString("cover_url"),
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    tags = emptySet(),
                    authors = emptySet(),
                    state = null,
                    contentRating = ContentRating.ADULT,
                    source = source
                )
            )
        }
        return result
    }

    override suspend fun getDetails(
        manga: Manga
    ): Manga {
        val response =
            webClient.httpGet(
                "$api/comic/detail?slug=${manga.url}"
            )

        val root =
            JSONObject(response.body.string())

        val comic =
            root.optJSONObject("data")
                ?: return manga

        val chapters =
            ArrayList<MangaChapter>()

        val chapterArray =
            comic.optJSONArray("chapters")

        if (chapterArray != null) {
            for (i in 0 until chapterArray.length()) {
                val chapter =
                    chapterArray.getJSONObject(i)

                val index =
                    chapter.optDouble(
                        "chapter_index",
                        0.0
                    )
                chapters.add(
                    MangaChapter(
                        id = generateUid("${comic.optString("slug")}/$index"),
                        title = chapter.optString("title"),
                        number = index.toFloat(),
                        volume = 0,
                        url ="/comic/${comic.optString("slug")}/chapter/${index.toString().removeSuffix(".0")}",
                        scanlator = null,
                        uploadDate = 0,
                        branch = null,
                        source = source
                    )
                )
            }
        }
        return manga.copy(
            title =
                comic.optString(
                    "title",
                    manga.title
                ),
            description =
                comic.optString(
                    "description",
                    ""
                ),
            coverUrl =
                comic.optString(
                    "cover_url",
                    manga.coverUrl
                ),
            authors =
                parseNames(
                    comic.optJSONArray("authors")
                ),
            tags = emptySet(),
            chapters = chapters
        )
    }


    private fun parseNames(
        array: org.json.JSONArray?
    ): Set<String> {
        if (array == null)
            return emptySet()
        return buildSet {
            for (i in 0 until array.length()) {
                add(
                    array.getJSONObject(i)
                        .optString("name")
                )
            }
        }
    }


    override suspend fun getPages(
        chapter: MangaChapter
    ): List<MangaPage> {

        val slug =
            chapter.url
                .substringAfter("/comic/")
                .substringBefore("/chapter/")

        val index =
            chapter.url
                .substringAfter("/chapter/")

        val response =
            webClient.httpGet(
                "$api/chapter/pages_by_index?slug=$slug&index=$index"
            )

        val root =
            JSONObject(response.body.string())

        val data =
            root.optJSONObject("data")
                ?: return emptyList()

        val pages =
            data.optJSONArray("pages")
                ?: return emptyList()

        return buildList {
            for (i in 0 until pages.length()) {
                val image =
                    pages.getString(i)
                add(
                    MangaPage(
                        id = generateUid(image),
                        url = image,
                        preview = null,
                        source = source
                    )
                )
            }
        }
    }

    private val categories = mapOf(
        "recommended" to "/comics",
        "official" to "/comics/official",
        "ongoing" to "/comics?status=ongoing",
        "popular" to "/comics/popular"
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = categories.map { (key, _) ->
                MangaTag(
                    title = key.replaceFirstChar { it.uppercase() },
                    key = key,
                    source = source
                )
            }.toSet(),

            availableContentRating = emptySet()
        )
    }
}
