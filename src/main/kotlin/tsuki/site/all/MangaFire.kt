package tsuki.site.all

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.network.OkHttpWebClient
import tsuki.MangaParserAuthProvider

import tsuki.util.*
import tsuki.model.*

import okhttp3.Interceptor
import org.jsoup.Jsoup
import java.util.EnumSet
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Base64

internal class VrfSigner {

    fun interceptor() = Interceptor { chain ->
        val request = chain.request()
        val url = request.url

        if (url.encodedPath.startsWith("/api/")) {
            val params = url.queryParameterNames
                .flatMap { key -> url.queryParameterValues(key).map { key to it } }
                .sortedBy { it.first }

            val sortedQueryUrl = buildString {
                append(url.encodedPath.removePrefix("/api"))
                if (params.isNotEmpty()) {
                    append('?')
                    var lastKey = ""
                    var index = 0
                    append(
                        params.joinToString("&") { (key, value) ->
                            val newKey = if (key.endsWith("[]")) {
                                if (lastKey != key) index = 0
                                lastKey = key
                                key.replace("[]", "[${index++}]")
                            } else {
                                key
                            }
                            "$newKey=$value"
                        },
                    )
                }
            }

            val builder = url.newBuilder().query(null)
            params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            builder.addQueryParameter("vrf", sign(sortedQueryUrl))

            val newRequest = request.newBuilder().url(builder.build()).build()
            chain.proceed(newRequest)
        } else {
            chain.proceed(request)
        }
    }

    private fun sign(path: String): String {
        var data = path.toByteArray(Charsets.UTF_8)
        for ((table, key, iv) in STAGES) {
            data = encryptStage(data, table, key, iv)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    private fun encryptStage(data: ByteArray, table: ByteArray, key: ByteArray, iv: Int): ByteArray {
        val out = ByteArray(data.size)
        var prev = iv
        val keySize = key.size
        for (i in data.indices) {
            prev = table[(data[i].toInt() xor key[i % keySize].toInt() xor prev) and 0xFF].toInt() and 0xFF
            out[i] = prev.toByte()
        }
        return out
    }

    companion object {
        private val decoder = Base64.getDecoder()

        private const val TABLE_1 = "yINlmUNho8VYJT+ibTIP+9ESiULpVEtMOoD6U6lRE0R/xwXo/Xp9NrUgC4cw/Lmo33vUyjUE40kUoEWIr/fxfNNcq2s79ShQ5NhNrFnJ4hXPwOu/SuXzIbuTQKGFvfm08E9jvCfqAtoDqvQq3dVWPQFmJjgvkISBeXY3BgANR+yVnjGbcxZ47d6kLNfZPIayTq3/YGySb1KuVZodWp/WGNAO5pfMcpaK53Hhs0allBszaMaxuouOwdxbwgxIw6YunSsXjI05Yi0j9j4eHKfSXR8Ifo/Od+8iamRfCXTyvm7NGRGYdcQ0ywcK/u6RXhrbcCm4t2eCtrDgQVecJGkQ+A=="
        private const val KEY_1 = "0Ec58JOY3uBzJK9m3zqIOpdlF7UFiax9DmA="
        private const val TABLE_2 = "IUFltCxD3Oc2cwCgkJffthaOg9cgPUb0LgW6H/VtfcF0kc5F25t+aWj6JH9VOhOaY0rAFdUxlDnl5BLNvwEJvQtP5qcw7vdb/K+chnbwnspSHT8mz5lqwz41TezG0hkO06FTjJZhsyNuFLDpD2ZZxQj/QIRcF90zpmQ7Byu483WsQqUE0C342HL+JXngRB6fRzxRyVTaKu83h7UYTJ0QMt6ixFh6S3F8gqkKwrGTL3jHNBsD45UnifK8+RGtishQV2K3rujLKEkiZxpr2dYcudFW4oFsDKhad3CLBvuyTqsCo4B7mL5IKQ1vXo/MOOvq1I1d8ar9X6Ttu5KF4fZgiA=="
        private const val KEY_2 = "AAdjb1iPY8CiDmq9H34tKTBF8a3oDQ=="
        private const val TABLE_3 = "NQHlu1/wVO5EmkwQymF810qqY2xG1k2obcas4Z9mCsPEIFl9pRIjFxbJ7ybMHbBckT5Ton85E0FOeHezbh/mjlEYpmpnlXOS8dgrqeq2KfxImTh1YK9y0PeMNhzA1OQzSY9brYOJq/l2QnE/hwOeZIhPixVSKIUlDb5vLcH6RWKxkIEMuP0bDwIqQ71AJJaEaMJL7A6YtyIwoRT+L5v4aZzodN/0+3nOGsfblFjgxSfPzVDjNFeNl5P26+kEC/8AHgdrpAbt3hHz3HrRN1Y6e+JHgF7ncFWnoF0y3THL1S71WgWGCa6KtSzTCCG58n68nTyj2T3Sshk7utqCtMi/ZQ=="
        private const val KEY_3 = "DELOJgPsVaCcblDtTGMdHzM="

        private val STAGES: List<Triple<ByteArray, ByteArray, Int>> = listOf(
            Triple(decoder.decode(TABLE_1), decoder.decode(KEY_1), 0x5A),
            Triple(decoder.decode(TABLE_2), decoder.decode(KEY_2), 0x35),
            Triple(decoder.decode(TABLE_3), decoder.decode(KEY_3), 0xBA),
        )
    }
}

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
            .addInterceptor(VrfSigner().interceptor())
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
        isYearRangeSupported = true,
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
            MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED, MangaState.PAUSED, MangaState.UPCOMING,
        ),
        availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.OTHER),
        availableDemographics = EnumSet.of(Demographic.SHOUNEN, Demographic.SHOUJO, Demographic.SEINEN, Demographic.JOSEI),
        availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.SUGGESTIVE, ContentRating.ADULT)
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val urlBuilder = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host(domain)
            .addPathSegments("api/titles")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "50")

        if (!filter.query.isNullOrBlank()) {
            urlBuilder.addQueryParameter("keyword", filter.query)
        }

        if (filter.yearFrom > 0) {
            urlBuilder.addQueryParameter("year_from", filter.yearFrom.toString())
        }
        if (filter.yearTo > 0) {
            urlBuilder.addQueryParameter("year_to", filter.yearTo.toString())
        }

        filter.contentRating.forEach { rating ->
            val value = when (rating) {
                ContentRating.SAFE -> "safe"
                ContentRating.SUGGESTIVE -> "suggestive"
                ContentRating.ADULT -> "pornographic"
            }
            value.let { urlBuilder.addQueryParameter("content_rating[]", it) }
        }

        filter.types.forEach { type ->
            val value = when (type) {
                ContentType.MANGA -> "manga"
                ContentType.MANHWA -> "manhwa"
                ContentType.MANHUA -> "manhua"
                ContentType.OTHER -> "other"
                else -> null
            }
            value?.let { urlBuilder.addQueryParameter("types[]", it) }
        }

        filter.demographics.forEach { demo ->
            val id = when (demo) {
                Demographic.JOSEI -> "268919"
                Demographic.SEINEN -> "268920"
                Demographic.SHOUJO -> "268917"
                Demographic.SHOUNEN -> "268918"
                else -> null
            }
            id?.let { urlBuilder.addQueryParameter("demographics[]", it) }
        }

        filter.tags.forEach { urlBuilder.addQueryParameter("genres_in[]", it.key) }
        filter.tagsExclude.forEach { urlBuilder.addQueryParameter("genres_ex[]", it.key) }

        filter.states.forEach { state ->
            val apiState = when (state) {
                MangaState.ONGOING -> "releasing"
                MangaState.FINISHED -> "finished"
                MangaState.ABANDONED -> "discontinued"
                MangaState.PAUSED -> "on_hiatus"
                MangaState.UPCOMING -> "not_yet_released"
                else -> null
            }
            apiState?.let { urlBuilder.addQueryParameter("statuses[]", it) }
        }

        val sortParam = when (order) {
            SortOrder.UPDATED -> "chapter_updated_at" to "desc"
            SortOrder.POPULARITY -> "views_total" to "desc"
            SortOrder.RATING -> "score" to "desc"
            SortOrder.NEWEST -> "created_at" to "desc"
            SortOrder.ALPHABETICAL -> "title" to "asc"
            SortOrder.RELEVANCE -> "relevance" to "desc"
            SortOrder.POPULARITY_WEEK -> "views_7d" to "desc"
            SortOrder.POPULARITY_MONTH -> "views_30d" to "desc"
            else -> null
        }

        sortParam?.let { (field, dir) ->
            urlBuilder.addQueryParameter("order[$field]", dir)
        }

        val url = urlBuilder.build().toString()

        val response = apiClient.httpGet(url).parseJson()
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

    private val detailsCacheLock = Any()

    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }
    override suspend fun getDetails(manga: Manga): Manga {
        synchronized(detailsCacheLock) {
            detailsCache[manga.url]?.let { return it }
        }

        val result = coroutineScope {
            val hid = extractHid(manga.url)

            val detailsJson = apiClient.httpGet("https://$domain/api/titles/$hid").parseJson()
            val data = detailsJson.getJSONObject("data")

            val hasVolumes = data.optBoolean("hasVolumes", false)

            val chaptersDeferred = async { fetchChapters(hid, hasVolumes) }

            val title = data.getString("title")
            val poster = data.optJSONObject("poster")
            val cover = poster?.optString("large")
                ?: poster?.optString("medium")
                ?: poster?.optString("small")
            val synopsisHtml = data.optString("synopsisHtml", null)
            val status = data.optString("status", null)
            val type = data.optString("type", null)
            val authorsList = data.optJSONArray("authors")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
            }.orEmpty()
            val artistsList = data.optJSONArray("artists")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
            }.orEmpty()
            val genres = data.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
            }
            val themes = data.optJSONArray("themes")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
            }
            val altTitlesArray = data.optJSONArray("altTitles")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            val rawRating = data.optDouble("rating", -1.0)
            val rating = if (rawRating >= 0.0) (rawRating / 10.0).toFloat() else RATING_UNKNOWN

            val synopsisText = synopsisHtml?.let { Jsoup.parseBodyFragment(it).text() } ?: ""

            val genreList = buildList {
                type?.let { add(it.replaceFirstChar { c -> c.uppercase() }) }
                genres?.let { addAll(it) }
                themes?.let { addAll(it) }
            }
            val genreTags = genreList.mapNotNull { name ->
                tags.find { it.title == name }
            }.toSet()

            val chapters = chaptersDeferred.await()

            manga.copy(
                title = title,
                coverUrl = cover ?: manga.coverUrl,
                authors = (authorsList + artistsList).toSet(),
                description = synopsisText.trim(),
                rating = rating,
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
        synchronized(detailsCacheLock) {
            detailsCache[manga.url] = result
        }
        return result
    }


    private suspend fun fetchChapters(hid: String, hasVolumes: Boolean): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        val base = "https://$domain/api/titles/$hid"

        var page = 1
        var lastPage: Int
        do {
            val json = apiClient.httpGet(
                "$base/chapters?language=$siteLang&sort=number&order=desc&page=$page&limit=200"
            ).parseJson()
            val items = json.getJSONArray("items")
            val meta = json.optJSONObject("meta")
            lastPage = meta?.optInt("lastPage", 1) ?: 1

            for (i in 0 until items.length()) {
                val ch = items.getJSONObject(i)
                if (ch.getString("language") != siteLang) continue

                val id = ch.getInt("id")
                val number = ch.getDouble("number").toFloat()
                val name = ch.optString("name", null)
                val createdAt = ch.optLong("createdAt", 0L) * 1000L
                val type = ch.getString("type")
                val chapterUrl = "/title/$hid/$id"
                val displayName = buildString {
                    append("Ch. ")
                    append(number.toString().removeSuffix(".0"))
                    if (!name.isNullOrBlank()) append(" - $name")
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
                        branch = type,
                        source = source,
                    )
                )
            }
            page++
        } while (page <= lastPage)

        if (hasVolumes) {
            try {
                val volJson = apiClient.httpGet("$base/volumes?language=$siteLang").parseJson()
                val volItems = volJson.getJSONArray("items")
                for (i in 0 until volItems.length()) {
                    val vol = volItems.getJSONObject(i)
                    if (vol.getString("language") != siteLang) continue

                    val volId = vol.getInt("id")
                    val volNumber = vol.getDouble("number").toFloat()
                    val volName = vol.optString("name", "").takeIf { it.isNotBlank() }
                    val chapterCount = vol.optInt("chapterCount", 0)

                    val title = buildString {
                        append("Vol. ")
                        append(volNumber.toString().removeSuffix(".0"))
                        if (volName != null) append(" - $volName")
                    }
                    val name = if (chapterCount > 0) "$chapterCount chapters" else ""

                    chapters.add(
                        MangaChapter(
                            id = generateUid("/title/$hid/vol/$volId"),
                            title = title,
                            number = volNumber,
                            volume = 0,
                            url = "/title/$hid/vol/$volId",
                            scanlator = name,
                            uploadDate = 0L,
                            branch = "Volume",
                            source = source,
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }

        val distinctBranches = chapters.map { it.branch }.distinct()
        val useGroups = distinctBranches.size > 1

        return chapters
            .map { chapter ->
                chapter.copy(
                    branch = if (useGroups) (chapter.branch ?: "").replaceFirstChar { it.uppercase() } else null
                )
            }
            .sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/") // numeric ID
        val isVolume = chapter.url.contains("/vol/")
        val endpoint = if (isVolume) "volumes" else "chapters"

        val response = apiClient.httpGet("https://$domain/api/$endpoint/$chapterId").parseJson()
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
