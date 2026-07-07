package org.koitharu.kotatsu.parsers.site.kotatsu.all

import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.OkHttpWebClient
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseJson
import java.util.EnumSet

internal abstract class MangaFireParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    private val siteLang: String,
) : PagedMangaParser(context, source, 30), Interceptor, MangaParserAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("mangafire.to")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, // chapter update
        SortOrder.POPULARITY, // most views
        SortOrder.RATING, // rating score
        SortOrder.NEWEST, // created manga
        SortOrder.ALPHABETICAL, // title asc
        SortOrder.RELEVANCE, // relevance sc
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
    )

    private val apiClient by lazy {
        val newHttpClient = context.httpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Referer", "https://$domain/")
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
        OkHttpWebClient(newHttpClient, source)
    }

    override fun intercept(chain: Interceptor.Chain) = chain.proceed(
        chain.request().newBuilder()
            .header("Referer", "https://$domain/")
            .build()
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    companion object {
        val GENRE_MAP = mapOf(
            "Action" to "1",
            "Adult" to "268929",
            "Adventure" to "78",
            "Avant Garde" to "3",
            "Boys Love" to "4",
            "Comedy" to "5",
            "Crime" to "268921",
            "Demons" to "77",
            "Drama" to "6",
            "Ecchi" to "7",
            "Fantasy" to "79",
            "Girls Love" to "9",
            "Gourmet" to "10",
            "Harem" to "11",
            "Hentai" to "268930",
            "Historical" to "268922",
            "Horror" to "530",
            "Isekai" to "13",
            "Iyashikei" to "531",
            "Josei" to "15",
            "Kids" to "532",
            "Magic" to "539",
            "Magical Girls" to "268923",
            "Mahou Shoujo" to "533",
            "Martial Arts" to "534",
            "Mature" to "268931",
            "Mecha" to "19",
            "Medical" to "268924",
            "Military" to "535",
            "Music" to "21",
            "Mystery" to "22",
            "Parody" to "23",
            "Philosophical" to "268925",
            "Psychological" to "536",
            "Reverse Harem" to "25",
            "Romance" to "26",
            "School" to "73",
            "Sci-Fi" to "28",
            "Seinen" to "537",
            "Shoujo" to "30",
            "Shounen" to "31",
            "Slice of Life" to "538",
            "Smut" to "268932",
            "Space" to "33",
            "Sports" to "34",
            "Super Power" to "75",
            "Superhero" to "268926",
            "Supernatural" to "76",
            "Suspense" to "37",
            "Thriller" to "38",
            "Tragedy" to "268927",
            "Vampire" to "39",
            "Wuxia" to "268928"
        )
    }

    private val tags by lazy {
        GENRE_MAP.entries.map { (title, id) ->
            MangaTag(title, id, source)
        }.toSet()
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = tags,
        availableStates = EnumSet.of(
            MangaState.ONGOING, MangaState.FINISHED,
            MangaState.ABANDONED, MangaState.PAUSED, MangaState.UPCOMING,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.OTHER,
        ),
        availableDemographics = EnumSet.of(Demographic.SHOUNEN, Demographic.SHOUJO, Demographic.SEINEN, Demographic.JOSEI),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = StringBuilder("https://$domain/api/titles?page=$page&limit=50")

        if (!filter.query.isNullOrBlank()) {
            url.append("&keyword=").append(filter.query)
        }

        filter.types.forEach { type ->
            val value = when (type) {
                ContentType.MANGA -> "manga"
                ContentType.MANHWA -> "manhwa"
                ContentType.MANHUA -> "manhua"
                ContentType.OTHER -> "other"
                else -> null
            }
            value?.let { url.append("&types[]=$it") }
        }

        filter.demographics.forEach { demo ->
            val id = when (demo) {
                Demographic.JOSEI -> "268919"
                Demographic.SEINEN -> "268920"
                Demographic.SHOUJO -> "268917"
                Demographic.SHOUNEN -> "268918"
                else -> null
            }
            id?.let { url.append("&demographics[]=$it") }
        }

        filter.tags.forEach { url.append("&genres_in[]=").append(it.key) }
        filter.tagsExclude.forEach { url.append("&genres_ex[]=").append(it.key) }

        filter.states.forEach {
            url.append("&statuses[]=").append(
                when (it) {
                    MangaState.ONGOING -> "releasing"
                    MangaState.FINISHED -> "finished"
                    MangaState.ABANDONED -> "discontinued"
                    MangaState.PAUSED -> "on_hiatus"
                    MangaState.UPCOMING -> "not_yet_released"
                    else -> ""
                }
            )
        }

        val sortParam = when (order) {
            SortOrder.UPDATED -> "order[chapter_updated_at]=desc"
            SortOrder.POPULARITY -> "order[views_total]=desc"
            SortOrder.RATING -> "order[score]=desc"
            SortOrder.NEWEST -> "order[created_at]=desc"
            SortOrder.ALPHABETICAL -> "order[title]=asc"
            SortOrder.RELEVANCE -> "order[relevance]=desc"
            SortOrder.POPULARITY_WEEK -> "order[views_7d]=desc"
            SortOrder.POPULARITY_MONTH -> "order[views_30d]=desc"
            else -> ""
        }
        if (sortParam.isNotEmpty()) url.append("&").append(sortParam)

        val response = apiClient.httpGet(url.toString()).parseJson()
        val items = response.getJSONArray("items")
        val mangas = mutableListOf<Manga>()
        for (i in 0 until items.length()) {
            val obj = items.getJSONObject(i)
            val hid = obj.getString("hid")
            val slug = obj.optString("slug", null)
            val title = obj.getString("title")
            val poster = obj.optJSONObject("poster")
            val cover = poster?.optString("large")
                ?: poster?.optString("medium")
                ?: poster?.optString("small") ?: ""
            val urlPath = "/title/$hid${slug?.let { "-$it" } ?: ""}"
            mangas.add(
                Manga(
                    id = generateUid(urlPath),
                    url = urlPath,
                    publicUrl = "https://$domain$urlPath",
                    title = title,
                    coverUrl = cover,
                    source = source,
                    altTitles = emptySet(),
                    largeCoverUrl = null,
                    authors = emptySet(),
                    contentRating = null,
                    rating = RATING_UNKNOWN,
                    state = null,
                    tags = emptySet(),
                )
            )
        }
        return mangas
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val hid = extractHid(manga.url)
        val detailsJson = apiClient.httpGet("https://$domain/api/titles/$hid").parseJson()
        val data = detailsJson.getJSONObject("data")

        val title = data.getString("title")
        val poster = data.optJSONObject("poster")
        val cover = poster?.optString("large")
            ?: poster?.optString("medium")
            ?: poster?.optString("small")
        val synopsisHtml = data.optString("synopsisHtml", null)
        val status = data.optString("status", null)
        val type = data.optString("type", null)
        val authors = data.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
        }?.joinToString()
        val genres = data.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
        }
        val themes = data.optJSONArray("themes")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
        }
        val altTitlesArray = data.optJSONArray("altTitles")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        // putting rating into description, adding it via "rating" does not work
        val raw = data.optDouble("rating", -1.0)
        val ratingValue = if (raw >= 0.0) raw.toFloat() else null

        val synopsisText = synopsisHtml?.let { Jsoup.parseBodyFragment(it).text() } ?: ""

        val richDescription = buildString {
            ratingValue?.let { rating ->
                val stars = (rating / 2.0).toInt().coerceIn(0, 5)
                append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} $rating\n\n")
            }
            if (synopsisText.isNotBlank()) {
                append(synopsisText.trim(), "\n\n")
            }
            if (altTitlesArray.isNotEmpty()) {
                append("Alternative Names:\n")
                altTitlesArray.forEach { append("- ", it, "\n") }
            }
        }.trim()

        val genreList = buildList {
            type?.let { add(it.replaceFirstChar { c -> c.uppercase() }) }
            genres?.let { addAll(it) }
            themes?.let { addAll(it) }
        }
        val genreTags = genreList.mapNotNull { name ->
            tags.find { it.title == name }
        }.toSet()

        val chapters = fetchChapters(hid)

        return manga.copy(
            title = title,
            coverUrl = cover ?: manga.coverUrl,
            authors = setOfNotNull(authors),
            description = richDescription,
            state = when (status?.lowercase()) {
                "releasing" -> MangaState.ONGOING
                "finished" -> MangaState.FINISHED
                "discontinued" -> MangaState.ABANDONED
                "on_hiatus" -> MangaState.PAUSED
                "not_yet_released" -> MangaState.UPCOMING
                else -> null
            },
            tags = genreTags,
            altTitles = altTitlesArray.toSet(),
            chapters = chapters,
        )
    }

    private suspend fun fetchChapters(hid: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        var lastPage: Int
        do {
            val url = "https://$domain/api/titles/$hid/chapters?language=$siteLang&sort=number&order=desc&page=$page&limit=200"
            val response = apiClient.httpGet(url).parseJson()
            val items = response.getJSONArray("items")
            val meta = response.optJSONObject("meta")
            lastPage = meta?.optInt("lastPage", 1) ?: 1

            for (i in 0 until items.length()) {
                val chObj = items.getJSONObject(i)

                if (chObj.getString("language") != siteLang) continue

                val id = chObj.getInt("id")
                val number = chObj.getDouble("number").toFloat()
                val name = chObj.optString("name", null)
                val createdAt = chObj.optLong("createdAt", 0L) * 1000L
                val type = chObj.getString("type") // "official" or "unofficial" or others
                val chapterUrl = "/title/$hid/$id"
                val displayName = buildString {
                    append("Ch. ")
                    append(number.toString().removeSuffix(".0"))
                    if (!name.isNullOrBlank()) {
                        append(" - $name")
                    }
                }
                chapters.add(
                    MangaChapter(
                        id = generateUid(chapterUrl),
                        title = displayName,
                        number = number,
                        volume = 0,
                        url = chapterUrl,
                        scanlator = null,
                        uploadDate = createdAt,
                        branch = type, // store raw type temporarily
                        source = source,
                    )
                )
            }
            page++
        } while (page <= lastPage)

        val distinctTypes = chapters.map { it.branch }.distinct()
        val useGroups = distinctTypes.size > 1

        return chapters
            .map { chapter ->
                chapter.copy(
                    branch = if (useGroups) {
                        (chapter.branch ?: "").replaceFirstChar { it.uppercase() }
                    } else {
                        null
                    }
                )
            }
            .sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/") // numeric ID
        val response = apiClient.httpGet("https://$domain/api/chapters/$chapterId").parseJson()
        val pagesArray = response.getJSONObject("data").getJSONArray("pages")
        val pages = ArrayList<MangaPage>(pagesArray.length())
        for (i in 0 until pagesArray.length()) {
            val pageObj = pagesArray.getJSONObject(i)
            val url = pageObj.getString("url")
            pages.add(
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            )
        }
        return pages
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override val authUrl: String get() = "https://$domain"
    override suspend fun isAuthorized(): Boolean = true
    override suspend fun getUsername(): String = ""


    private fun extractHid(url: String): String {
        val lastPart = url.removeSuffix("/").substringAfterLast("/")
        return when {
            lastPart.contains(".") -> lastPart.substringAfterLast(".")
            lastPart.contains("-") -> lastPart.substringBefore("-")
            else -> lastPart
        }
    }

    @MangaSourceParser("MANGAFIRE_EN", "MangaFire (English)", "en")
    class English(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_EN, "en")

    @MangaSourceParser("MANGAFIRE_ES", "MangaFire (Spanish)", "es")
    class Spanish(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_ES, "es")

    @MangaSourceParser("MANGAFIRE_ESLA", "MangaFire Spanish (Latin)", "es")
    class SpanishLatim(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_ESLA, "es-la")

    @MangaSourceParser("MANGAFIRE_FR", "MangaFire (French)", "fr")
    class French(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_FR, "fr")

    @MangaSourceParser("MANGAFIRE_JA", "MangaFire (Japanese)", "ja")
    class Japanese(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_JA, "ja")

    @MangaSourceParser("MANGAFIRE_PT", "MangaFire (Portuguese)", "pt")
    class Portuguese(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_PT, "pt")

    @MangaSourceParser("MANGAFIRE_PTBR", "MangaFire Portuguese (Brazil)", "pt")
    class PortugueseBR(context: MangaLoaderContext) :
        MangaFireParser(context, MangaParserSource.MANGAFIRE_PTBR, "pt-br")
}
