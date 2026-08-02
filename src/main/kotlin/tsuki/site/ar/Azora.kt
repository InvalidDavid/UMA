package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.MangaParserSource
import tsuki.model.MangaChapter
import tsuki.model.Manga
import tsuki.model.MangaTag
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaState
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.model.ContentType

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import tsuki.util.json.toJSONObjectOrNull
import tsuki.util.mapNotNullToSet
import tsuki.util.parseSafe
import tsuki.util.src
import tsuki.util.urlEncoded

import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("AZORAMANGA", "Azora Manga", "ar", ContentType.MANGA)
internal class Azora(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.AZORAMANGA, 24) {

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.ALPHABETICAL)

    override val configKeyDomain = ConfigKey.Domain("azorafly.com")

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://api.")
            append(domain)
            append("/api/query")

            val params = mutableListOf<String>()

            params.add("page=$page")
            params.add("perPage=24")

            if (!filter.query.isNullOrEmpty()) {
                params.add("searchTerm=${filter.query!!.urlEncoded()}")
            }

            if (filter.tags.isNotEmpty()) {
                val genreIds = filter.tags.joinToString(",") { it.key }
                params.add("genreIds=$genreIds")
            }

            val (orderBy, orderDirection) = when (order) {
                SortOrder.UPDATED -> "lastChapterAddedAt" to "desc"
                SortOrder.POPULARITY -> "totalViews" to "desc"
                SortOrder.NEWEST -> "createdAt" to "desc"
                SortOrder.ALPHABETICAL -> "postTitle" to "asc"
                else -> "lastChapterAddedAt" to "desc"
            }
            params.add("orderBy=$orderBy")
            params.add("orderDirection=$orderDirection")

            if (params.isNotEmpty()) {
                append("?")
                append(params.joinToString("&"))
            }
        }

        val response = webClient.httpGet(url)
        val body = response.body.string()

        val jsonArray = try {
            JSONArray(body)
        } catch (_: Exception) {
            try {
                val jsonObject = JSONObject(body)
                when {
                    jsonObject.has("posts") -> jsonObject.getJSONArray("posts")
                    jsonObject.has("data") -> jsonObject.getJSONArray("data")
                    jsonObject.has("results") -> jsonObject.getJSONArray("results")
                    else -> JSONArray()
                }
            } catch (_: Exception) {
                JSONArray()
            }
        }
        return parseMangaList(jsonArray)
    }

    private fun parseMangaList(json: JSONArray): List<Manga> {
        val result = mutableListOf<Manga>()

        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val slug = obj.getString("slug")
            val url = "/series/$slug"
            val title = obj.getString("postTitle")
            val coverUrl = obj.optString("featuredImage")

            val seriesStatus = obj.optString("seriesStatus", "")
            val state = when (seriesStatus.uppercase()) {
                "ONGOING" -> MangaState.ONGOING
                "COMPLETED" -> MangaState.FINISHED
                "HIATUS" -> MangaState.PAUSED
                else -> null
            }

            val genresArray = obj.optJSONArray("genres")
            var isNovel = false
            val tags = if (genresArray != null) {
                buildSet {
                    for (idx in 0 until genresArray.length()) {
                        val genre = genresArray.getJSONObject(idx)
                        val genreName = genre.getString("name")
                        if (genreName == "رواية") isNovel = true
                        add(MangaTag(
                            key = genre.getInt("id").toString(),
                            title = genreName,
                            source = source,
                        ))
                    }
                }
            } else {
                emptySet()
            }

            if (obj.optString("postType") == "رواية" || obj.optString("type") == "رواية") isNovel = true

            if (obj.optString("seriesType").equals("NOVEL", ignoreCase = true)) isNovel = true

            if (slug.contains("novel", ignoreCase = true)) isNovel = true

            if (isNovel) continue

            result.add(
                Manga(
                    id = generateUid(url),
                    title = title,
                    altTitles = emptySet(),
                    url = url,
                    publicUrl = url.toAbsoluteUrl(domain),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = coverUrl,
                    tags = tags,
                    state = state,
                    authors = emptySet(),
                    source = source,
                )
            )
        }

        return result
    }

    private fun fetchAvailableTags(): Set<MangaTag> {
        return setOf(
            MangaTag("أكشن", "1", source),
            MangaTag("حريم", "2", source),
            MangaTag("زمكاني", "3", source),
            MangaTag("سحر", "4", source),
            MangaTag("شونين", "5", source),
            MangaTag("مغامرات", "6", source),
            MangaTag("خيال", "7", source),
            MangaTag("رومانسي", "8", source),
            MangaTag("كوميدي", "9", source),
            MangaTag("مانهوا", "10", source),
            MangaTag("إثارة", "11", source),
            MangaTag("دراما", "12", source),
            MangaTag("تاريخي", "13", source),
            MangaTag("راشد", "14", source),
            MangaTag("سينين", "15", source),
            MangaTag("خارق للطبيعة", "16", source),
            MangaTag("شياطين", "17", source),
            MangaTag("حياة مدرسية", "18", source),
            MangaTag("جوسي", "19", source),
            MangaTag("مانها", "20", source),
            MangaTag("ويبتون", "21", source),
            MangaTag("شينين", "22", source),
            MangaTag("قوة خارقة", "23", source),
            MangaTag("خيال علمي", "24", source),
            MangaTag("غموض", "25", source),
            MangaTag("مأساة", "26", source),
            MangaTag("شريحة من الحياة", "27", source),
            MangaTag("فنون قتالية", "28", source),
            MangaTag("شوجو", "29", source),
            MangaTag("ايسكاي", "30", source),
            MangaTag("مصاصي الدماء", "31", source),
            MangaTag("اسبوعي", "32", source),
            MangaTag("لعبة", "33", source),
            MangaTag("نفسي", "34", source),
            MangaTag("وحوش", "35", source),
            MangaTag("الحياة اليومية", "36", source),
            MangaTag("الحياة المدرسية", "37", source),
            MangaTag("رعب", "38", source),
            MangaTag("عسكري", "39", source),
            MangaTag("رياضي", "40", source),
            MangaTag("اتشي", "41", source),
            MangaTag("ايشي", "42", source),
            MangaTag("دموي", "43", source),
            MangaTag("زومبي", "44", source),
            MangaTag("مميز", "45", source),
            MangaTag("ايسيكاي", "46", source),
            MangaTag("فنتازيا", "47", source),
            MangaTag("اشباح", "48", source),
            MangaTag("إعادة إحياء", "49", source),
            MangaTag("بطل غير اعتيادي", "50", source),
            MangaTag("ثأر", "51", source),
            MangaTag("اثارة", "52", source),
            MangaTag("تراجيدي", "53", source),
            MangaTag("طبخ", "54", source),
            MangaTag("تناسخ", "55", source),
            MangaTag("عودة بالزمن", "56", source),
            MangaTag("انتقام", "57", source),
            MangaTag("تجسيد", "58", source),
            MangaTag("فانتازيا", "59", source),
            MangaTag("عائلي", "60", source),
            MangaTag("تجسد", "61", source),
            MangaTag("العاب", "62", source),
            MangaTag("عالم اخر", "63", source),
            MangaTag("السفر عبر الزمن", "64", source),
            MangaTag("خيالي", "65", source),
            MangaTag("زمنكاني", "66", source),
            MangaTag("مغامرة", "67", source),
            MangaTag("طبي", "68", source),
            MangaTag("عصور وسطى", "69", source),
            MangaTag("ساموراي", "70", source),
            MangaTag("مافيا", "71", source),
            MangaTag("نظام", "72", source),
            MangaTag("هوس", "73", source),
            MangaTag("عصري", "74", source),
            MangaTag("بطل مجنون", "75", source),
            MangaTag("رعاية اطفال", "76", source),
            MangaTag("زواج مدبر", "77", source),
            MangaTag("تشويق", "78", source),
            MangaTag("مكتبي", "79", source),
            MangaTag("قوى خارقه", "80", source),
            MangaTag("تحقيق", "81", source),
            MangaTag("أيتام", "82", source),
            MangaTag("جوسين", "83", source),
            MangaTag("موسيقي", "84", source),
            MangaTag("قصة حقيقة", "85", source),
            MangaTag("موريم", "86", source),
            MangaTag("موظفين", "87", source),
            MangaTag("فيكتوري", "88", source),
            MangaTag("مأساوي", "89", source),
            MangaTag("عصر حديث", "90", source),
            MangaTag("ندم", "91", source),
            MangaTag("حياة جامعية", "92", source),
            MangaTag("حاصد", "93", source),
            MangaTag("الأرواح", "94", source),
            MangaTag("جريمة", "95", source),
            MangaTag("عاطفي", "96", source),
            MangaTag("أكاديمي", "97", source),
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        if (doc.selectFirst("span.inline-block.border.text-foreground")?.text() == "رواية") {
            return manga.copy(
                state = MangaState.FINISHED,
                chapters = emptyList(),
            )
        }

        val chapters = loadChapters(manga, doc)

        val coverUrl = doc.selectFirst("section img")?.src() ?: manga.coverUrl

        val ratingText = doc.selectFirst("div:contains(التقييم) + div, span:contains(Rating)")?.text()
        val rating = ratingText?.substringBefore("/")?.trim()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN

        val statusText = doc.selectFirst("div:contains(الحالة) + div, span:contains(Status)")?.text()
        val state = when {
            statusText?.contains("مستمر", ignoreCase = true) == true ||
                    statusText?.contains("ongoing", ignoreCase = true) == true -> MangaState.ONGOING
            statusText?.contains("مكتمل", ignoreCase = true) == true ||
                    statusText?.contains("completed", ignoreCase = true) == true -> MangaState.FINISHED
            else -> null
        }

        val description = doc.selectFirst("meta[name=description]")?.attr("content")

        val tags = doc.select("a[href*='/series/?genres='], span.genre").mapNotNullToSet { element ->
            val genreName = element.text().trim()
            val genreId = element.attr("href").substringAfter("genres=").substringBefore("&")
                .ifEmpty { genreName }

            if (genreName.isNotEmpty()) {
                MangaTag(
                    key = genreId,
                    title = genreName,
                    source = source,
                )
            } else {
                null
            }
        }

        return manga.copy(
            coverUrl = coverUrl,
            rating = rating,
            state = state,
            tags = tags,
            description = description,
            chapters = chapters,
        )
    }

    private fun loadChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val seriesSlug = manga.url.substringAfter("/series/", "").substringBefore('/')
        if (seriesSlug.isEmpty()) return emptyList()

        val chaptersJson = extractChaptersFromAstroProps(doc) ?: return emptyList()

        val chapters = ArrayList<MangaChapter>(chaptersJson.length())
        for (i in 0 until chaptersJson.length()) {
            val chapter = unboxSerializedValue(chaptersJson.opt(i)) as? JSONObject ?: continue
            val slug = (unboxSerializedValue(chapter.opt("slug")) as? String)
                ?.takeUnless { it.isBlank() || it == "null" }
                ?: continue
            val url = "/series/$seriesSlug/$slug"
            val number = when (val rawNumber = unboxSerializedValue(chapter.opt("number"))) {
                is Number -> rawNumber.toFloat()
                is String -> rawNumber.toFloatOrNull() ?: 0f
                else -> 0f
            }
            val title = (unboxSerializedValue(chapter.opt("title")) as? String)
                ?.takeUnless { it.isBlank() || it == "null" }
            val createdAt = unboxSerializedValue(chapter.opt("createdAt")) as? String

            chapters += MangaChapter(
                id = generateUid(url),
                title = when {
                    !title.isNullOrBlank() && number > 0f -> "الفصل ${formatChapterNumber(number)} - $title"
                    !title.isNullOrBlank() -> title
                    number > 0f -> "الفصل ${formatChapterNumber(number)}"
                    else -> slug
                },
                number = number,
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = parseChapterDate(createdAt),
                branch = null,
                source = source,
            )
        }
        return chapters.sortedBy { it.number }
    }

    private fun extractChaptersFromAstroProps(doc: Document): JSONArray? {
        val propsEscaped = doc
            .selectFirst("section[data-series-tab-panel=chapters] astro-island[props]")
            ?.attr("props")
            ?.takeUnless { it.isBlank() }
            ?: return null

        val props = Parser.unescapeEntities(propsEscaped, false)
        val root = props.toJSONObjectOrNull() ?: return null
        (unboxSerializedValue(root.opt("initialChap")) as? JSONArray)?.let { return it }
        val post = unboxSerializedValue(root.opt("post")) as? JSONObject ?: return null
        return unboxSerializedValue(post.opt("chapters")) as? JSONArray
    }

    private fun unboxSerializedValue(value: Any?): Any? {
        if (value !is JSONArray || value.length() != 2) {
            return value
        }
        return when (value.opt(0)) {
            0, 1, "0", "1" -> unboxSerializedValue(value.opt(1))
            else -> value
        }
    }

    private fun formatChapterNumber(number: Float): String {
        return if (number % 1f == 0f) {
            number.toInt().toString()
        } else {
            number.toString()
        }
    }

    private fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return synchronized(chapterDateFormat) { chapterDateFormat.parseSafe(date) }
    }

    private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        return doc.select("section[itemprop=articleBody] figure img").mapNotNull { img ->
            val imageUrl = img.src()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }
}
