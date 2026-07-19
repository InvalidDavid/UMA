package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

import tsuki.model.*
import tsuki.util.*

import okhttp3.Headers
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.EnumSet
import java.util.Locale
import java.util.Calendar

// year filter broken
// filter does not work in other sortorder mode except updated

@MangaSourceParser("ROLIASCAN", "Rolia Scan", "en")
internal class RoliaScan(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ROLIASCAN, API_PAGES_PER_PAGE * API_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("roliascan.com")

    private val numberRegex = Regex("""(\d+(?:\.\d+)?)""")
    private val relativeDateRegex = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""")
    private val tokenFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH", Locale.US)

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/")
        .build()

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true,
        isYearSupported = true,
    )

    private val genreTags by lazy {
        ROLIA_TAGS.mapTo(LinkedHashSet(ROLIA_TAGS.size)) { (title, id) ->
            MangaTag(title = title, key = id.toString(), source = source)
        }
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = genreTags,
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.NOVEL
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val start = (page - 1) * API_PAGES_PER_PAGE + 1
        val end = start + API_PAGES_PER_PAGE - 1
        val result = ArrayList<Manga>(pageSize)
        val seen = HashSet<String>(pageSize * 2)

        for (apiPage in start..end) {
            val yearsParam = if (filter.year > 0) "[${filter.year}]" else "[]"
            val payload = JSONObject().apply {
                put("page", apiPage)
                put("search", filter.query.orEmpty())
                put("years", yearsParam)
                put("genres", filter.tags.toGenrePayload())
                put("types", filter.types.toTypePayload())
                put("statuses", filter.states.toStatusPayload())
                put("sort", order.toApiSort())
                put("genreMatchMode", "any")
            }
            val json = postJsonArray("https://$domain/wp-json/manga/v1/load", payload)
            if (json.length() == 0) break
            repeat(json.length()) { i ->
                val item = json.optJSONObject(i) ?: return@repeat
                if (item.optString("type").equals("Novel", true)) return@repeat
                val rawUrl = item.optString("url").trim()
                if (rawUrl.isEmpty()) return@repeat
                val uid = item.optString("id").nullIfEmpty() ?: rawUrl
                if (seen.add(uid)) result.add(item.toManga())
            }
            if (json.length() < API_PAGE_SIZE) break
        }
        return result
    }

    private fun JSONObject.toManga(): Manga {
        val url = optString("url")
        val id = optString("id")
        val fullUrl = withMangaId(url, id)
        return Manga(
            id = generateUid(fullUrl),
            title = optString("title").unescapeHtml(),
            altTitles = emptySet(),
            url = fullUrl,
            publicUrl = url.nullIfEmpty()?.toAbsoluteUrl(domain)
                ?: url.toRelativeUrl(domain).toAbsoluteUrl(domain),
            rating = optString("score").toFloatOrNull() ?: RATING_UNKNOWN,
            contentRating = null,
            coverUrl = optString("cover").nullIfEmpty(),
            tags = emptySet(),
            state = optString("status").toMangaState(),
            authors = emptySet(),
            description = optString("description").nullIfEmpty()?.unescapeHtml(),
            source = source,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = fetchDocument(manga.url.urlWithoutFragment().toAbsoluteUrl(domain))
        val ld = doc.findSeriesJsonLd()
        val mangaId = extractMangaId(manga.url)
            ?: doc.selectFirst("[data-manga-id]")?.attr("data-manga-id")
            ?: ld?.optString("identifier")?.nullIfEmpty()
            ?: throw ParseException("Cannot find manga id", manga.url)

        val cleanTitle = doc.selectFirst("h1")?.ownText()?.trim()?.nullIfEmpty()
            ?: ld?.optString("name")?.nullIfEmpty()?.unescapeHtml()
            ?: manga.title

        return manga.copy(
            title = cleanTitle,
            description = doc.extractDescription(ld)?.unescapeHtml(),
            altTitles = doc.extractAltTitles(ld).mapTo(mutableSetOf()) { it.unescapeHtml() },
            tags = doc.extractTags(ld).ifEmpty { manga.tags },
            authors = doc.extractAuthors(ld).ifEmpty { manga.authors }.mapTo(mutableSetOf()) { it.unescapeHtml() },
            state = doc.extractStatus(),
            rating = doc.extractRating(ld),
            chapters = loadChapters(mangaId, manga.url.toAbsoluteUrl(domain)),
        )
    }

    private suspend fun loadChapters(mangaId: String, referer: String): List<MangaChapter> {
        val headers = Headers.headersOf("Referer", referer)
        val result = ArrayList<MangaChapter>()
        val seen = HashSet<String>()
        var offset = 0
        val limit = CHAPTER_LIMIT
        var total = Int.MAX_VALUE
        while (offset < total) {
            val (token, ts) = chapterToken()
            val raw = webClient.httpGet(
                buildChapterUrl(mangaId, offset, limit, token, ts), headers
            ).parseRaw().trim()
            if (raw.isEmpty()) break
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: break
            if (!json.optBoolean("success", true)) break
            total = json.optInt("total", total)
            val arr = json.optJSONArray("chapters") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val ch = arr.getJSONObject(i)
                val url = ch.optString("url").nullIfEmpty() ?: continue
                if (ch.optString("language").nullIfEmpty()?.equals("en", true) == false) continue
                val key = ch.optString("id").ifEmpty { url }
                if (!seen.add(key)) continue
                result.add(ch.toChapter(url, offset + i + 1f))
            }
            offset += arr.length()
            if (!json.optBoolean("has_more", true)) break
        }
        return result.reversed()
    }

    private fun JSONObject.toChapter(url: String, fallback: Float): MangaChapter {
        val chapterNum = optString("chapter").nullIfEmpty() ?: fallback.toLabel()
        val rawTitle = optString("title").trim()
        val number = numberRegex.find(chapterNum)?.value?.toFloatOrNull() ?: fallback

        val title = buildString {
            append("Ch. ")
            append(chapterNum)
            val cleanTitle = rawTitle.takeUnless {
                it.equals("N/A", true) || it == "—" || it.isEmpty()
            }?.unescapeHtml()
            if (!cleanTitle.isNullOrBlank()) {
                append(" - ")
                append(cleanTitle)
            }
        }
        return MangaChapter(
            id = generateUid(url),
            title = title,
            number = number,
            volume = 0,
            url = url.toRelativeUrl(domain),
            scanlator = null,
            uploadDate = parseRelativeDate(optString("date")),
            branch = null,
            source = source,
        )
    }

    private fun chapterToken() = Pair(
        MessageDigest.getInstance("MD5")
            .digest("${System.currentTimeMillis() / 1000L}mng_ch_${ZonedDateTime.now(ZoneOffset.UTC).format(tokenFormatter)}".toByteArray())
            .toHex().substring(0, 16),
        (System.currentTimeMillis() / 1000L).toString()
    )

    @Suppress("SameParameterValue")
    private fun buildChapterUrl(mangaId: String, offset: Int, limit: Int, token: String, ts: String) =
        HttpUrl.Builder().apply {
            scheme("https").host(domain)
            addPathSegment("auth").addPathSegment("manga-chapters")
            addQueryParameter("manga_id", mangaId)
            addQueryParameter("offset", offset.toString())
            addQueryParameter("limit", limit.toString())
            addQueryParameter("order", "DESC")
            addQueryParameter("_t", token)
            addQueryParameter("_ts", ts)
        }.build().toString()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val id = chapter.url.toAbsoluteUrl(domain)
            .removeSuffix("/").substringBefore('?').substringAfterLast('/').substringAfterLast('-')
            .nullIfEmpty() ?: return emptyList()
        val raw = webClient.httpGet(
            "https://$domain/auth/chapter-content?chapter_id=$id", getRequestHeaders()
        ).parseRaw().trim()
        if (raw.isEmpty()) return emptyList()
        val images = runCatching { JSONObject(raw).optJSONArray("images") ?: JSONArray(raw) }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>(images.length())
        return (0 until images.length()).mapNotNull { i ->
            val img = images.optString(i).nullIfEmpty() ?: return@mapNotNull null
            val abs = if (img.startsWith("http", true)) img else img.toAbsoluteUrl(domain)
            if (seen.add(abs)) MangaPage(id = generateUid(abs), url = abs, preview = null, source = source) else null
        }
    }

    private fun String?.toMangaState() = when {
        this == null -> null
        contains("ongoing", true) -> MangaState.ONGOING
        contains("complete", true) -> MangaState.FINISHED
        contains("hiatus", true) -> MangaState.PAUSED
        contains("cancel", true) -> MangaState.ABANDONED
        contains("upcoming", true) || contains("tba", true) -> MangaState.UPCOMING
        else -> null
    }

    private fun parseRelativeDate(raw: String?): Long {
        val v = raw?.trim()?.lowercase(Locale.US)?.nullIfEmpty() ?: return 0L
        val m = relativeDateRegex.matchEntire(v) ?: return 0L
        val (num, unit) = m.destructured
        val cal = Calendar.getInstance()
        val n = num.toIntOrNull() ?: return 0L
        when (unit) {
            "second" -> cal.add(Calendar.SECOND, -n)
            "minute" -> cal.add(Calendar.MINUTE, -n)
            "hour"   -> cal.add(Calendar.HOUR_OF_DAY, -n)
            "day"    -> cal.add(Calendar.DAY_OF_YEAR, -n)
            "week"   -> cal.add(Calendar.WEEK_OF_YEAR, -n)
            "month"  -> cal.add(Calendar.MONTH, -n)
            "year"   -> cal.add(Calendar.YEAR, -n)
        }
        return cal.timeInMillis
    }

    private fun String.unescapeHtml(): String {
        var d = this
        var p: String
        do {
            p = d
            d = Parser.unescapeEntities(d, false)
        } while (d != p)
        return d
    }

    private fun Float.toLabel() = if (this % 1f == 0f) toInt().toString() else toString().trimEnd('0').removeSuffix(".")

    private fun Collection<MangaState>.toStatusPayload() = if (isEmpty()) "[]" else
        mapNotNull { s -> when(s) { MangaState.FINISHED -> "Completed"; MangaState.ONGOING -> "Ongoing"; else -> null } }.toJsonArray()

    private fun Collection<ContentType>.toTypePayload() = if (isEmpty()) "[]" else
        mapNotNull { t -> when(t) { ContentType.MANGA -> "Manga"; ContentType.MANHWA -> "Manhwa"; ContentType.MANHUA -> "Manhua"; ContentType.COMICS -> "Comics"; else -> null } }.toJsonArray()

    private fun Collection<String>.toJsonArray() = if (isEmpty()) "[]" else
        joinToString(prefix = "[", postfix = "]", separator = ",") { "\"${it.escapeJson()}\"" }

    private fun SortOrder.toApiSort() = when(this) {
        SortOrder.POPULARITY -> "popular_desc"
        SortOrder.UPDATED -> "post_desc"
        SortOrder.NEWEST -> "release_desc"
        SortOrder.ALPHABETICAL -> "title_asc"
        SortOrder.ALPHABETICAL_DESC -> "title_desc"
        else -> "post_desc"
    }

    private fun String.escapeJson() = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun Collection<MangaTag>.toGenrePayload(): String {
        if (isEmpty()) return "[]"
        val ids = mapNotNull { tag ->
            tag.key.toIntOrNull()?.toString()
                ?: genreTags.find { it.title.equals(tag.title, true) }?.key?.toIntOrNull()?.toString()
        }
        return ids.toJsonArray()
    }

    private fun Document.extractDescription(ld: JSONObject?) =
        ld?.optString("description")?.nullIfEmpty()
            ?: selectFirst("div[data-description], div#description, div#synopsis")?.nextElementSibling()?.text()?.nullIfEmpty()
            ?: selectFirst("meta[name='description']")?.attr("content")?.nullIfEmpty()
            ?: selectFirst("meta[property='og:description']")?.attr("content")?.nullIfEmpty()

    private fun Document.extractAltTitles(ld: JSONObject?) = buildSet {
        ld?.opt("alternateName")?.let { v ->
            when(v) { is JSONArray -> repeat(v.length()) { it -> v.optString(it).nullIfEmpty()?.let { add(it) } }; is String -> v.nullIfEmpty()?.let { add(it) } }
        }
        if (isEmpty()) selectFirst("h1 + p")?.text()?.split(" / ")?.mapNotNull { it.nullIfEmpty() }?.let { addAll(it) }
    }

    private fun Document.extractTags(ld: JSONObject?) = buildSet {
        ld?.opt("genre")?.let { v ->
            when(v) { is JSONArray -> repeat(v.length()) { it -> v.optString(it).nullIfEmpty()?.let { add(MangaTag(key = it.lowercase().replace(' ', '-'), title = it, source = source)) } }; is String -> v.nullIfEmpty()?.let { add(MangaTag(key = it.lowercase().replace(' ', '-'), title = it, source = source)) } }
        }
        if (isEmpty()) {
            select("a[href*='/genre/']").mapNotNull { a ->
                a.text().nullIfEmpty()?.let { title ->
                    val slug = a.attr("href").trimEnd('/').substringAfterLast('/').substringBefore('?').nullIfEmpty() ?: return@mapNotNull null
                    add(MangaTag(key = slug, title = title, source = source))
                }
            }
        }
    }

    private fun Document.extractAuthors(ld: JSONObject?) = buildSet {
        fun addName(v: Any?) {
            when(v) { is JSONArray -> repeat(v.length()) { addName(v.opt(it)) }; is JSONObject -> addName(v.opt("name")); is String -> v.nullIfEmpty()?.let { add(it) } }
        }
        addName(ld?.opt("author")); addName(ld?.opt("creator"))
        if (isEmpty()) select("a[href*='/author/'], a[href*='/artist/']").forEach { it -> it.text().nullIfEmpty()?.let { add(it) } }
    }

    private fun Document.extractStatus(): MangaState? {
        val label = select("*").firstOrNull { it.ownText().trim().equals("Status", true) }
        val text = label?.let { it.nextElementSibling()?.text()?.nullIfEmpty() }
            ?: selectFirst("[data-status]")?.attr("data-status")?.nullIfEmpty()
            ?: selectFirst("span.status, div.status")?.text()?.nullIfEmpty()
        return text?.toMangaState()
    }

    private fun Document.extractRating(ld: JSONObject?): Float {
        val rawRating = ld?.optJSONObject("aggregateRating")?.optDouble("ratingValue")
            ?.takeIf { !it.isNaN() }
            ?: selectFirst("svg ~ span.font-semibold")?.text()?.toDoubleOrNull()
        return if (rawRating != null && rawRating >= 0.0) (rawRating / 10.0).toFloat() else RATING_UNKNOWN
    }

    private fun Document.findSeriesJsonLd(): JSONObject? {
        for (s in select("script[type=application/ld+json]")) {
            val raw = s.data().trim()
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null && obj.matchesSeries()) return obj
            runCatching { JSONArray(raw) }.getOrNull()?.let { arr ->
                repeat(arr.length()) { it -> arr.optJSONObject(it)?.takeIf { it.matchesSeries() }?.let { return it } }
            }
        }
        return null
    }

    private fun JSONObject.matchesSeries() = optString("@type") in setOf("ComicSeries", "ComicStory", "Book", "CreativeWorkSeries")

    private fun extractMangaId(url: String): String? {
        url.substringAfter('#', "").split('&').firstOrNull { it.startsWith("mid=") }?.substringAfter("mid=")?.nullIfEmpty()?.let { return it }
        url.substringAfter('?', "").split('&').firstOrNull { it.startsWith("mid=") }?.substringAfter("mid=")?.nullIfEmpty()?.let { return it }
        return null
    }

    private fun String.urlWithoutFragment() = substringBefore('#')

    private fun withMangaId(rawUrl: String?, id: String?): String {
        val url = rawUrl.nullIfEmpty() ?: return "/"
        val cleanId = id?.nullIfEmpty() ?: return url
        return if (url.contains('#')) {
            if (url.contains("mid=")) url else "$url&mid=$cleanId"
        } else {
            "$url#mid=$cleanId"
        }
    }

    private suspend fun postJsonArray(url: String, payload: JSONObject): JSONArray {
        val raw = webClient.httpPost(url, payload).parseRaw().trim()
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private suspend fun fetchDocument(url: String) = webClient.httpGet(url).parseHtml()

    private fun ByteArray.toHex(): String {
        if (isEmpty()) return ""
        val chars = CharArray(size * 2)
        for ((i, b) in withIndex()) {
            val v = b.toInt() and 0xFF
            chars[i * 2] = Character.forDigit(v ushr 4, 16)
            chars[i * 2 + 1] = Character.forDigit(v and 0xF, 16)
        }
        return String(chars)
    }

    private companion object {
        const val API_PAGES_PER_PAGE = 5
        const val API_PAGE_SIZE = 24
        const val CHAPTER_LIMIT = 9999

        val ROLIA_TAGS = listOf(
            "Action" to 5,
            "Adaptation" to 49,
            "Adapted to Manhua" to 717,
            "Adult Cast" to 119,
            "Adventure" to 19,
            "Aliens" to 803,
            "Animals" to 240,
            "Award Winning" to 8,
            "Childcare" to 1146,
            "Combat Sports" to 358,
            "Comedy" to 61,
            "Cooking" to 266,
            "Crime" to 248,
            "Crossdressing" to 724,
            "Delinquents" to 228,
            "Demons" to 162,
            "Detective" to 150,
            "Drama" to 26,
            "Ecchi" to 117,
            "Erotica" to 202,
            "Fantasy" to 17,
            "Full Color" to 40,
            "Gag Humor" to 1068,
            "Game" to 1130,
            "Gender Bender" to 1190,
            "Ghosts" to 215,
            "Gore" to 187,
            "Gourmet" to 89,
            "Harem" to 47,
            "Historical" to 66,
            "Horror" to 67,
            "Isekai" to 55,
            "Josei" to 1062,
            "Light Novel" to 98,
            "Long Strip" to 41,
            "Love Status Quo" to 541,
            "Mafia" to 356,
            "Magic" to 45,
            "Magical Sex Shift" to 551,
            "Manga" to 97,
            "Manhua" to 35,
            "Manhwa" to 18,
            "Martial Arts" to 56,
            "Mature" to 404,
            "Mecha" to 396,
            "Medical" to 244,
            "Military" to 131,
            "Monster Girls" to 231,
            "Monsters" to 46,
            "Music" to 694,
            "Mystery" to 34,
            "Mythology" to 110,
            "Ninja" to 163,
            "Office Workers" to 505,
            "Official Colored" to 866,
            "Organized Crime" to 134,
            "Otaku Culture" to 570,
            "Parody" to 605,
            "Philosophical" to 912,
            "Post-Apocalyptic" to 241,
            "Psychological" to 149,
            "Regression" to 1131,
            "Reincarnation" to 29,
            "Revenge" to 964,
            "Reverse Harem" to 1085,
            "Romance" to 2,
            "Romantic Subtext" to 486,
            "School" to 14,
            "School Life" to 27,
            "Sci-Fi" to 33,
            "Seinen" to 105,
            "Self-Published" to 577,
            "Sexual Violence" to 536,
            "Shoujo" to 1071,
            "Shounen" to 11,
            "Showbiz" to 429,
            "Slice of Life" to 93,
            "Smut" to 742,
            "Space" to 206,
            "Sports" to 9,
            "Streaming" to 1132,
            "Suggestive" to 1116,
            "Super Power" to 6,
            "Superhero" to 865,
            "Supernatural" to 65,
            "Survival" to 236,
            "Suspense" to 287,
            "Team Sports" to 10,
            "Thriller" to 184,
            "Time Travel" to 37,
            "Tragedy" to 316,
            "Transmigiration" to 1133,
            "Urban Fantasy" to 120,
            "Vampire" to 209,
            "Video Game" to 277,
            "Video Games" to 616,
            "Villainess" to 355,
            "Virtual Reality" to 617,
            "Web Comic" to 48,
            "Webtoon" to 350,
            "Workplace" to 138,
            "Wuxia" to 68,
            "Xianxia" to 718,
            "Zombies" to 1115,
        )
    }
}
