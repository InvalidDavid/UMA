package tsuki.site.ru

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Favicon
import tsuki.model.Favicons
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

import tsuki.util.generateUid
import tsuki.util.parseJson

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("ASTRAMANGA", "AstraManga", "ru")
internal class AstraManga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ASTRAMANGA, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("astramanga.org")

    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon("https://$domain/favicon.ico", 256, null),
            ),
            domain,
        )
    }

    private val apiUrl = "https://api.$domain/api/v1"
    private val mediaUrl = "https://$domain/media"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
        isYearRangeSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GENRES.map { (name, id) -> MangaTag(name, id, source) }.toSet(),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.COMICS
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildSearchUrl(page, order, filter)
        val json = webClient.httpGet(url).parseJson()
        val data = json.optJSONObject("data") ?: return emptyList()
        val titles = data.optJSONArray("titles") ?: return emptyList()
        return (0 until titles.length()).map { i ->
            parseSearchManga(titles.getJSONObject(i))
        }
    }

    private fun buildSearchUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        return "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("size", "30")
            .apply {
                filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    addQueryParameter("q", it)
                }

                val sortParam = when (order) {
                    SortOrder.POPULARITY -> "-popularity"
                    SortOrder.UPDATED -> "-updated_at"
                    SortOrder.NEWEST -> "-created_at"
                    SortOrder.RATING -> "-rating"
                    SortOrder.ALPHABETICAL -> "name"
                    else -> "-popularity"
                }
                addQueryParameter("sort", sortParam)

                filter.types.mapNotNull { type ->
                    when (type) {
                        ContentType.MANGA -> "manga"
                        ContentType.MANHWA -> "manhwa"
                        ContentType.MANHUA -> "manhua"
                        ContentType.COMICS -> "comics"
                        else -> null
                    }
                }.takeIf { it.isNotEmpty() }?.let {
                    addQueryParameter("types", it.joinToString(","))
                }

                filter.tags.map { it.key }
                    .takeIf { it.isNotEmpty() }
                    ?.let { addQueryParameter("genres", it.joinToString(",")) }

                filter.tagsExclude.map { it.key }
                    .takeIf { it.isNotEmpty() }
                    ?.let { addQueryParameter("exclude_genres", it.joinToString(",")) }

                if (filter.yearFrom > 0) addQueryParameter("year_from", filter.yearFrom.toString())
                if (filter.yearTo > 0) addQueryParameter("year_to", filter.yearTo.toString())
            }
            .build()
            .toString()
    }

    private fun parseSearchManga(obj: JSONObject): Manga {
        val slug = obj.getString("slug")
        val title = obj.getString("name")
        val coverUrl = resolveCoverUrl(obj)
        return Manga(
            id = generateUid(slug),
            url = slug,
            publicUrl = "https://$domain/manga/$slug",
            title = title,
            altTitles = emptySet(),
            coverUrl = coverUrl,
            largeCoverUrl = null,
            description = null,
            tags = emptySet(),
            authors = emptySet(),
            state = null,
            rating = RATING_UNKNOWN,
            contentRating = null,
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url
        val titleUrl = "$apiUrl/titles/$slug"
        val json = webClient.httpGet(titleUrl).parseJson()
        val data = json.getJSONObject("data")
        val updated = parseDetailManga(data)

        val titleId = data.getInt("id")
        val branchesUrl = "$apiUrl/titles/$titleId/branches"
        val branchesJson = webClient.httpGet(branchesUrl).parseJson()
        val branches = branchesJson.getJSONObject("data").getJSONArray("branches")
        var branchId = -1
        var maxChapters = 0
        for (i in 0 until branches.length()) {
            val branch = branches.getJSONObject(i)
            val isMain = branch.optBoolean("is_main", false)
            val count = branch.optInt("count_chapters", 0)
            if (isMain || count > maxChapters) {
                branchId = branch.getInt("id")
                maxChapters = count
            }
        }
        if (branchId == -1) return updated.copy(chapters = emptyList())

        val chaptersUrl = "$apiUrl/branches/$branchId/chapters"
        val chaptersJson = webClient.httpGet(
            chaptersUrl.toHttpUrl().newBuilder()
                .addQueryParameter("size", maxChapters.coerceAtLeast(100).toString())
                .build()
        ).parseJson()
        val items = chaptersJson.getJSONObject("data").getJSONArray("items")
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until items.length()) {
            val ch = items.getJSONObject(i)
            val chId = ch.getLong("id")
            val number = ch.getDouble("number").toFloat()
            val volume = ch.optInt("volume_number", 0)
            val name = ch.optString("name", null)
            val publishedAt = ch.optString("published_at", null)
            val uploadDate = dateFormat.parseSafe(publishedAt)
            val title = buildString {
                if (volume > 0) append("Том $volume ")
                append("Глава ${number.toString().removeSuffix(".0")}")
                if (!name.isNullOrBlank()) append(" — $name")
            }
            chapters.add(
                MangaChapter(
                    id = generateUid(chId.toString()),
                    title = title,
                    number = number,
                    volume = volume,
                    url = "$slug/${number.toString().removeSuffix(".0")}/$chId",
                    uploadDate = uploadDate,
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            )
        }
        return updated.copy(chapters = chapters.sortedBy { it.number })
    }

    private fun parseDetailManga(obj: JSONObject): Manga {
        val slug = obj.getString("slug")
        val title = obj.getString("name")
        val coverUrl = resolveCoverUrl(obj)
        val description = obj.optString("description", null)
        val status = obj.optString("status", null)
        val state = when (status) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "paused" -> MangaState.PAUSED
            "frozen", "discontinued" -> MangaState.ABANDONED
            else -> null
        }

        val rawRating = obj.optDouble("rating", -1.0)
        val rating = if (rawRating >= 0.0) (rawRating / 10.0).toFloat() else RATING_UNKNOWN

        val genresArray = obj.optJSONArray("genres")
        val tagsArray = obj.optJSONArray("tags")
        val allTags = mutableSetOf<MangaTag>()
        genresArray?.let { arr ->
            for (i in 0 until arr.length()) {
                val genre = arr.getJSONObject(i)
                val id = genre.getString("id")
                val name = genre.getString("name")
                allTags.add(MangaTag(id, name, source))
            }
        }
        tagsArray?.let { arr ->
            for (i in 0 until arr.length()) {
                val tag = arr.getJSONObject(i)
                val id = tag.getString("id")
                val name = tag.getString("name")
                if (allTags.none { it.title == name }) {
                    allTags.add(MangaTag(id, name, source))
                }
            }
        }

        val authors = mutableSetOf<String>()
        obj.optJSONObject("publishing_house")?.optString("name")?.let { authors.add(it) }
        obj.optJSONArray("publishers")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")?.let { authors.add(it) }
            }
        }

        val altTitles = mutableSetOf<String>()
        obj.optString("secondary_name", null)?.let { altTitles.add(it) }
        obj.optJSONArray("alternative_names")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i)?.let { altTitles.add(it) }
            }
        }

        val isNsfw = obj.optBoolean("is_erotic", false) ||
                obj.optBoolean("is_yaoi", false) ||
                obj.optBoolean("is_lgbt", false)
        val contentRating = if (isNsfw) ContentRating.ADULT else ContentRating.SAFE

        return Manga(
            id = generateUid(slug),
            url = slug,
            publicUrl = "https://$domain/manga/$slug",
            title = title,
            altTitles = altTitles,
            coverUrl = coverUrl,
            largeCoverUrl = null,
            description = description,
            tags = allTags,
            authors = authors,
            state = state,
            rating = rating,
            contentRating = contentRating,
            source = source,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/")
        val url = "$apiUrl/chapters/$chapterId/pages"
        val json = webClient.httpGet(url).parseJson()
        val pages = json.getJSONObject("data").getJSONArray("pages")
        return (0 until pages.length()).map { i ->
            val imageUrl = pages.getJSONObject(i).getString("image_url")
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private fun resolveCoverUrl(obj: JSONObject): String? {
        val coverVersions = obj.optJSONObject("cover_versions")
        coverVersions?.optString("mid")?.takeIf { it.isNotBlank() }?.let { return resolveUrl(it) }
        coverVersions?.optString("high")?.takeIf { it.isNotBlank() }?.let { return resolveUrl(it) }
        obj.optString("cover_image").takeIf { it.isNotBlank() }?.let { return resolveUrl(it) }
        return null
    }

    private fun resolveUrl(path: String): String {
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "$mediaUrl/$path"
        }
    }

    private fun SimpleDateFormat.parseSafe(date: String?): Long {
        return date?.let { runCatching { parse(it)?.time ?: 0L }.getOrDefault(0L) } ?: 0L
    }

    companion object {
        private val GENRES = mapOf(
            "Аниме" to "1", "Антиутопия" to "2", "Апокалиптический" to "3", "Арт" to "4",
            "Безумие" to "5", "Боевик" to "6", "Боевые искусства" to "7", "Вестерн" to "8",
            "Военное" to "9", "Выживание" to "10", "Гарем" to "11", "Героическое фэнтези" to "12",
            "Гуро" to "13", "Гэг-юмор" to "14", "Детектив" to "15", "Детское" to "16",
            "Дзёсей" to "17", "Драма" to "18", "Завоевание мира" to "19", "Исекай" to "20",
            "Искусство" to "21", "Исторический" to "22", "Киберпанк" to "23", "Кодомо" to "24",
            "Комедия" to "25", "Космос" to "26", "Криминал / Преступники" to "27",
            "Кулинария" to "28", "Культивация" to "29", "Литрес" to "30",
            "Махо-сёдзё" to "31", "Меха" to "32", "Мистика" to "33", "Мифология" to "34",
            "Мурим" to "35", "Научная фантастика" to "36", "Обратный Гарем" to "37",
            "Омегаверс" to "38", "Пародия" to "39", "Повседневность" to "40",
            "Постапокалипсис" to "41", "Приключения" to "42", "Психология" to "43",
            "Регрессия" to "44", "Рисование" to "45", "Романтика" to "46",
            "Самурайский боевик" to "47", "Сверхъестественное" to "48", "Сёдзе" to "49",
            "Сёнен" to "50", "Спорт" to "51", "Средневековье" to "52", "Стимпанк" to "53",
            "Сэйнэн" to "54", "Сянься" to "55", "Трагедия" to "56", "Триллер" to "57",
            "Ужасы" to "58", "Фантастика" to "59", "Философия" to "60", "Фэнтези" to "61",
            "Школьная жизнь" to "62", "Экшен" to "63", "Элементы юмора" to "64",
            "Юмор" to "65", "Образовательная литература" to "131"
        )
    }
}
