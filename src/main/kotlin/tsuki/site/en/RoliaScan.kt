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
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Calendar
import java.util.EnumSet
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("ROLIASCAN", "Rolia Scan", "en")
internal class RoliaScan(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ROLIASCAN, API_PAGES_PER_PAGE * API_PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("roliascan.com")

    private val chapterNumberRegex = Regex("""(\d+(?:\.\d+)?)""")

    private val tokenFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH", Locale.US)

    private val genreTags by lazy {
        ROLIA_TAGS.mapTo(LinkedHashSet(ROLIA_TAGS.size)) { (title, id) ->
            tag(id.toString(), title)
        }
    }

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

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = genreTags,
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
        ),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val startApiPage = (page - 1) * API_PAGES_PER_PAGE + 1
        val endApiPage = startApiPage + API_PAGES_PER_PAGE - 1
        val result = ArrayList<Manga>(pageSize)
        val seenIds = HashSet<String>(pageSize * 2)

        for (apiPage in startApiPage..endApiPage) {
            val payload = JSONObject().apply {
                put("page", apiPage)
                put("search", filter.query.orEmpty())
                put("years", "[]")
                put("genres", filter.tags.toGenrePayload())
                put("types", filter.types.toTypePayload())
                put("statuses", filter.states.toStatusPayload())
                put("sort", order.toApiSort())
                put("genreMatchMode", "any")
            }
            val pageJson = postJsonArray("https://$domain/wp-json/manga/v1/load", payload)
            val rawSize = pageJson.length()
            if (rawSize == 0) {
                break
            }
            repeat(rawSize) { index ->
                val item = pageJson.optJSONObject(index) ?: return@repeat
                if (item.optString("type").equals("Novel", ignoreCase = true)) {
                    return@repeat
                }
                val rawUrl = item.optString("url").trim()
                if (rawUrl.isEmpty()) {
                    return@repeat
                }
                val uniqueId = item.optString("id").nullIfEmpty() ?: rawUrl
                if (!seenIds.add(uniqueId)) {
                    return@repeat
                }
                result.add(item.toManga())
            }
            if (rawSize < API_PAGE_SIZE) {
                break
            }
        }
        return result
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val detailUrl = manga.url.urlWithoutFragment().toAbsoluteUrl(domain)
        val document = fetchDocument(detailUrl)
        val jsonLd = document.findSeriesJsonLd()
        val description = document.extractDescription(jsonLd)
        val altTitles = document.extractAltTitles(jsonLd)
        val tags = document.extractTags(jsonLd)
        val authors = document.extractAuthors(jsonLd)
        val state = document.extractStatus()
        val rating = document.extractRating(jsonLd)
        val mangaId = extractMangaId(manga.url)
            ?: document.selectFirst("[data-manga-id]")?.attr("data-manga-id")
            ?: jsonLd?.optString("identifier")?.nullIfEmpty()
            ?: throw ParseException("Unable to determine manga id", detailUrl)
        val chapters = loadChapters(mangaId, detailUrl)
        return manga.copy(
            description = description,
            tags = if (tags.isEmpty()) manga.tags else tags,
            authors = if (authors.isEmpty()) manga.authors else authors,
            altTitles = if (altTitles.isEmpty()) manga.altTitles else altTitles,
            state = state,
            rating = rating,
            chapters = chapters,
        )
    }

    private suspend fun loadChapters(mangaId: String, referer: String): List<MangaChapter> {
        val headers = Headers.headersOf("Referer", referer)
        val result = ArrayList<MangaChapter>()
        val seenIds = HashSet<String>()
        var offset = 0
        val limit = CHAPTER_LIMIT
        var expectedTotal = Int.MAX_VALUE
        while (offset < expectedTotal) {
            val (token, timestamp) = generateChapterToken()
            val requestUrl = buildChapterUrl(mangaId, offset, limit, token, timestamp)
            val raw = webClient.httpGet(requestUrl, headers).parseRaw().trim()
            if (raw.isEmpty()) {
                break
            }
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: break
            if (json.has("success") && !json.optBoolean("success", false)) {
                break
            }
            expectedTotal = json.optInt("total", expectedTotal)
            val chaptersArray = json.optJSONArray("chapters") ?: break
            if (chaptersArray.length() == 0) {
                break
            }
            for (i in 0 until chaptersArray.length()) {
                val item = chaptersArray.getJSONObject(i)
                val chapterUrl = item.optString("url").nullIfEmpty() ?: continue
                val chapterLanguage = item.optString("language").nullIfEmpty()
                if (chapterLanguage != null && !chapterLanguage.equals("en", ignoreCase = true)) {
                    continue
                }
                val key = item.optString("id").ifEmpty { chapterUrl }
                if (!seenIds.add(key)) {
                    continue
                }
                val fallbackNumber = (offset + i + 1).toFloat()
                result.add(item.toMangaChapter(chapterUrl, fallbackNumber))
            }
            offset += chaptersArray.length()
            if (json.has("has_more") && !json.optBoolean("has_more", false)) {
                break
            }
            if (!json.has("has_more") && chaptersArray.length() < limit) {
                break
            }
        }
        return result.reversed()
    }

    private fun buildChapterUrl(
        mangaId: String,
        offset: Int,
        limit: Int,
        token: String,
        timestamp: String,
    ): String {
        return HttpUrl.Builder()
            .scheme("https")
            .host(domain)
            .addPathSegment("auth")
            .addPathSegment("manga-chapters")
            .addQueryParameter("manga_id", mangaId)
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("order", "DESC")
            .addQueryParameter("_t", token)
            .addQueryParameter("_ts", timestamp)
            .build()
            .toString()
    }

    private fun generateChapterToken(): Pair<String, String> {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val hourKey = ZonedDateTime.now(ZoneOffset.UTC).format(tokenFormatter)
        val secret = "mng_ch_$hourKey"
        val hashInput = timestamp + secret
        val token = MessageDigest
            .getInstance("MD5")
            .digest(hashInput.toByteArray())
            .toHex()
            .substring(0, 16)
        return token to timestamp
    }

    private fun ByteArray.toHex(): String {
        if (isEmpty()) {
            return ""
        }
        val chars = CharArray(size * 2)
        var index = 0
        for (element in this) {
            val unsigned = element.toInt() and 0xFF
            chars[index++] = Character.forDigit((unsigned ushr 4) and 0xF, 16)
            chars[index++] = Character.forDigit(unsigned and 0xF, 16)
        }
        return String(chars)
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url
            .toAbsoluteUrl(domain)
            .substringBefore('?')
            .substringAfterLast('/')
            .substringAfterLast('-')
            .nullIfEmpty()
            ?: return emptyList()

        val requestUrl = HttpUrl.Builder()
            .scheme("https")
            .host(domain)
            .addPathSegment("auth")
            .addPathSegment("chapter-content")
            .addQueryParameter("chapter_id", chapterId)
            .build()
            .toString()

        val raw = webClient.httpGet(requestUrl).parseRaw().trim()
        if (raw.isEmpty()) {
            return emptyList()
        }

        val images = runCatching {
            JSONObject(raw).optJSONArray("images")
                ?: JSONArray(raw)
        }.getOrNull() ?: return emptyList()

        val seen = HashSet<String>(images.length())
        val result = ArrayList<MangaPage>(images.length())
        repeat(images.length()) { index ->
            val imageUrl = images.optString(index).nullIfEmpty() ?: return@repeat
            val absoluteUrl = if (imageUrl.startsWith("http", ignoreCase = true)) {
                imageUrl
            } else {
                imageUrl.toAbsoluteUrl(domain)
            }
            if (!seen.add(absoluteUrl)) {
                return@repeat
            }
            result.add(
                MangaPage(
                    id = generateUid(absoluteUrl),
                    url = absoluteUrl,
                    preview = null,
                    source = source,
                ),
            )
        }
        return result
    }

    private fun JSONObject.toManga(): Manga {
        val id = optString("id")
        val rawUrl = optString("url")
        val relativeUrl = if (rawUrl.isBlank()) "/" else rawUrl.toRelativeUrl(domain)
        val internalUrl = relativeUrl.withMangaId(id)
        val publicUrl = rawUrl.nullIfEmpty() ?: relativeUrl.toAbsoluteUrl(domain)
        return Manga(
            id = generateUid(internalUrl),
            title = optString("title"),
            altTitles = emptySet(),
            url = internalUrl,
            publicUrl = publicUrl,
            rating = optString("score").toFloatOrNull() ?: RATING_UNKNOWN,
            contentRating = null,
            coverUrl = optString("cover").nullIfEmpty(),
            tags = emptySet(),
            state = optString("status").toMangaState(),
            authors = emptySet(),
            description = optString("description").nullIfEmpty(),
            source = source,
        )
    }

    private fun JSONObject.toMangaChapter(chapterUrl: String, fallbackNumber: Float): MangaChapter {
        val rawChapter = optString("chapter")
        val chapterLabel = rawChapter.nullIfEmpty() ?: fallbackNumber.toChapterLabel()
        val rawTitle = optString("title").trim()
        val title = when {
            rawTitle.equals("N/A", ignoreCase = true) -> "Chapter $chapterLabel"
            rawTitle == "—" -> "Chapter $chapterLabel"
            rawTitle.isEmpty() -> null
            else -> rawTitle
        }
        val number = chapterNumberRegex.find(rawChapter)?.value?.toFloatOrNull()
        val scanlator = optString("group_name")
            .trim()
            .nullIfEmpty()
            ?.takeUnless { it.equals("N/A", ignoreCase = true) || it == "—" }
        return MangaChapter(
            id = generateUid(chapterUrl),
            title = title,
            number = number ?: fallbackNumber,
            volume = 0,
            url = chapterUrl.toRelativeUrl(domain),
            scanlator = scanlator,
            uploadDate = parseChapterDate(optString("date")),
            branch = null,
            source = source,
        )
    }

    private fun Float.toChapterLabel(): String = if (this % 1f == 0f) {
        toInt().toString()
    } else {
        replaceTrailingZeros()
    }

    private fun Float.replaceTrailingZeros(): String {
        val text = toString()
        val trimmed = text.trimEnd('0')
        return if (trimmed.endsWith('.')) {
            trimmed.dropLast(1)
        } else {
            trimmed
        }
    }

    private fun String?.toMangaState(): MangaState? {
        val normalized = this?.lowercase(Locale.US)?.trim() ?: return null
        return when {
            normalized.contains("ongoing") -> MangaState.ONGOING
            normalized.contains("complete") || normalized.contains("completed") -> MangaState.FINISHED
            normalized.contains("hiatus") -> MangaState.PAUSED
            normalized.contains("canceled") || normalized.contains("cancelled") -> MangaState.ABANDONED
            normalized.contains("upcoming") || normalized.contains("tba") -> MangaState.UPCOMING
            else -> null
        }
    }

    private fun parseChapterDate(raw: String?): Long {
        val value = raw?.trim()?.lowercase(Locale.US)?.nullIfEmpty() ?: return 0L
        if (!value.endsWith("ago")) {
            return 0L
        }
        val amount = chapterNumberRegex.find(value)?.value?.toFloatOrNull() ?: return 0L
        val calendar = Calendar.getInstance()
        when {
            value.contains("second") -> calendar.add(Calendar.SECOND, -amount.toInt())
            value.contains("minute") -> calendar.add(Calendar.MINUTE, -amount.toInt())
            value.contains("hour") -> calendar.add(Calendar.HOUR_OF_DAY, -amount.toInt())
            value.contains("day") -> calendar.add(Calendar.DAY_OF_YEAR, -amount.toInt())
            value.contains("week") -> calendar.add(Calendar.WEEK_OF_YEAR, -amount.toInt())
            value.contains("month") -> calendar.add(Calendar.MONTH, -amount.toInt())
            value.contains("year") -> calendar.add(Calendar.YEAR, -amount.toInt())
            else -> return 0L
        }
        return calendar.timeInMillis
    }

    private fun Collection<MangaState>.toStatusPayload(): String {
        if (isEmpty()) {
            return "[]"
        }
        val mapped = mapNotNull { state ->
            when (state) {
                MangaState.FINISHED -> "Completed"
                MangaState.ONGOING -> "Ongoing"
                else -> null
            }
        }
        return mapped.toStringifiedPayload()
    }

    private fun Collection<ContentType>.toTypePayload(): String {
        if (isEmpty()) {
            return "[]"
        }
        val mapped = mapNotNull { type ->
            when (type) {
                ContentType.MANGA -> "Manga"
                ContentType.MANHWA -> "Manhwa"
                ContentType.MANHUA -> "Manhua"
                ContentType.COMICS -> "Comics"
                else -> null
            }
        }
        return mapped.toStringifiedPayload()
    }

    private fun Collection<String>.toStringifiedPayload(): String {
        if (isEmpty()) {
            return "[]"
        }
        return joinToString(prefix = "[", postfix = "]", separator = ",") { value ->
            "\"${value.escapeJson()}\""
        }
    }

    private fun SortOrder.toApiSort(): String = when (this) {
        SortOrder.POPULARITY -> "popular_desc"
        SortOrder.UPDATED -> "post_desc"
        SortOrder.NEWEST -> "release_desc"
        SortOrder.ALPHABETICAL -> "title_asc"
        SortOrder.ALPHABETICAL_DESC -> "title_desc"
        else -> "post_desc"
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun Document.extractDescription(jsonLd: JSONObject?): String? {
        val fromJson = jsonLd?.optString("description")?.nullIfEmpty()
        if (fromJson != null) {
            return fromJson
        }
        return selectFirst("div[data-description], div#description, div#synopsis, div:matchesOwn((?i)description)")
            ?.nextElementSibling()
            ?.text()
            ?.nullIfEmpty()
            ?: selectFirst("meta[name='description']")?.attr("content")?.nullIfEmpty()
            ?: selectFirst("meta[property='og:description']")?.attr("content")?.nullIfEmpty()
    }

    private fun Document.extractAltTitles(jsonLd: JSONObject?): Set<String> {
        val alt = mutableSetOf<String>()
        jsonLd?.opt("alternateName")?.let { value ->
            when (value) {
                is JSONArray -> {
                    repeat(value.length()) { index ->
                        value.optString(index).nullIfEmpty()?.let(alt::add)
                    }
                }
                is String -> value.nullIfEmpty()?.let(alt::add)
            }
        }
        if (alt.isEmpty()) {
            selectFirst("h1 + p")?.text()?.split(" / ")?.mapNotNull { it.nullIfEmpty() }?.let { alt.addAll(it) }
        }
        return alt
    }

    private fun Document.extractTags(jsonLd: JSONObject?): Set<MangaTag> {
        val tags = LinkedHashSet<MangaTag>()
        jsonLd?.opt("genre")?.let { genre ->
            when (genre) {
                is JSONArray -> repeat(genre.length()) { index ->
                    genre.optString(index).nullIfEmpty()?.let { addTag(tags, it) }
                }
                is String -> genre.nullIfEmpty()?.let { addTag(tags, it) }
            }
        }
        if (tags.isEmpty()) {
            select("a[href*='/genre/']").mapNotNullToSet { anchor ->
                anchor.text().nullIfEmpty()?.let { title ->
                    val href = anchor.attr("href").trimEnd('/')
                    val slug = href.substringAfterLast('/').substringBefore('?').nullIfEmpty()
                        ?: return@let null
                    MangaTag(
                        key = slug,
                        title = title,
                        source = source,
                    )
                }
            }
                .takeIf { it.isNotEmpty() }
                ?.let(tags::addAll)
        }
        return tags
    }

    private fun addTag(target: MutableSet<MangaTag>, value: String) {
        val normalized = value.trim().nullIfEmpty() ?: return
        target.add(
            MangaTag(
                key = normalized.lowercase(Locale.US).replace(' ', '-'),
                title = normalized,
                source = source,
            ),
        )
    }

    private fun Document.extractAuthors(jsonLd: JSONObject?): Set<String> {
        val authors = LinkedHashSet<String>()
        fun addName(value: Any?) {
            when (value) {
                is JSONArray -> repeat(value.length()) { index -> addName(value.opt(index)) }
                is JSONObject -> addName(value.opt("name"))
                is String -> value.nullIfEmpty()?.let(authors::add)
            }
        }
        addName(jsonLd?.opt("author"))
        addName(jsonLd?.opt("creator"))
        if (authors.isEmpty()) {
            select("a[href*='/author/'], a[href*='/artist/']").forEach { anchor ->
                anchor.text().nullIfEmpty()?.let(authors::add)
            }
        }
        return authors
    }

    private fun Document.extractStatus(): MangaState? {
        val label = select("*").firstOrNull { element ->
            element.ownText().trim().equals("Status", ignoreCase = true)
        }
        val statusText = label?.let { element ->
            element.findSiblingText()?.nullIfEmpty()
        }
            ?: selectFirst("[data-status]")?.attr("data-status")?.nullIfEmpty()
            ?: selectFirst("span.status, div.status")?.text()?.nullIfEmpty()
        return statusText.toMangaState()
    }

    private fun Document.extractRating(jsonLd: JSONObject?): Float {
        jsonLd?.optJSONObject("aggregateRating")?.optDouble("ratingValue")?.takeIf { !it.isNaN() }?.let {
            return it.toFloat()
        }
        return selectFirst("svg ~ span.font-semibold")?.text()?.toFloatOrNull() ?: RATING_UNKNOWN
    }

    private fun Document.findSeriesJsonLd(): JSONObject? {
        for (script in select("script[type=application/ld+json]")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) {
                continue
            }
            val obj = raw.toJsonObjectOrNull()
            if (obj != null) {
                if (obj.matchesSeriesType()) {
                    return obj
                }
                continue
            }
            val array = raw.toJsonArrayOrNull() ?: continue
            repeat(array.length()) { index ->
                val candidate = array.optJSONObject(index) ?: return@repeat
                if (candidate.matchesSeriesType()) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun String.toJsonObjectOrNull(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()

    private fun String.toJsonArrayOrNull(): JSONArray? = runCatching { JSONArray(this) }.getOrNull()

    private fun JSONObject.matchesSeriesType(): Boolean {
        val type = optString("@type")
        if (type.isNullOrEmpty()) {
            return false
        }
        return type.equals("ComicSeries", true) ||
                type.equals("ComicStory", true) ||
                type.equals("Book", true) ||
                type.equals("CreativeWorkSeries", true)
    }

    private fun String?.withMangaId(id: String?): String {
        val value = this?.nullIfEmpty() ?: return "/"
        val cleanId = id?.nullIfEmpty() ?: return value
        return if (value.contains('#')) {
            if (value.contains("mid=")) value else "$value&mid=$cleanId"
        } else {
            "$value#mid=$cleanId"
        }
    }

    private fun extractMangaId(rawUrl: String): String? {
        val fragment = rawUrl.substringAfter('#', "")
        if (fragment.isNotEmpty()) {
            fragment.split('&').firstOrNull { it.startsWith("mid=") }?.let { part ->
                return part.substringAfter("mid=").nullIfEmpty()
            }
        }
        val query = rawUrl.substringAfter('?', "")
        if (query.isNotEmpty()) {
            query.split('&').firstOrNull { it.startsWith("mid=") || it.startsWith("mangaId=") }?.let { part ->
                return part.substringAfter('=').nullIfEmpty()
            }
        }
        return null
    }

    private fun String.urlWithoutFragment(): String = substringBefore('#')

    private suspend fun postJsonArray(url: String, payload: JSONObject): JSONArray {
        val raw = webClient.httpPost(url, payload).parseRaw().trim()
        if (raw.isEmpty()) {
            return JSONArray()
        }
        return raw.toJsonArrayOrNull() ?: JSONArray()
    }

    private suspend fun fetchDocument(url: String): Document = webClient.httpGet(url).parseHtml()

    private fun Collection<MangaTag>.toGenrePayload(): String {
        if (isEmpty()) {
            return "[]"
        }
        val ids = LinkedHashSet<String>(size)
        for (tag in this) {
            val numericKey = tag.key?.toIntOrNull()?.let { it.toString() }
                ?: genreTags.firstOrNull { ref ->
                    ref.title.equals(tag.title, ignoreCase = true)
                }?.key?.toIntOrNull()?.let { it.toString() }
            if (numericKey != null) {
                ids.add(numericKey)
            }
        }
        if (ids.isEmpty()) {
            return "[]"
        }
        return buildString(ids.size * 4) {
            append('[')
            ids.forEachIndexed { index, value ->
                if (index > 0) {
                    append(',')
                }
                append(value)
            }
            append(']')
        }
    }

    private fun tag(id: String, title: String) = MangaTag(title = title, key = id, source = source)

    private fun Element.findSiblingText(): String? {
        nextElementSibling()?.text()?.nullIfEmpty()?.let { return it }
        val parent = parent() ?: return null
        val children = parent.children()
        val index = children.indexOf(this)
        if (index == -1) {
            return null
        }
        for (i in index + 1 until children.size) {
            val text = children[i].text().nullIfEmpty()
            if (text != null) {
                return text
            }
        }
        return null
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