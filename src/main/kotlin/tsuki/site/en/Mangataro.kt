package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

import tsuki.model.*
import tsuki.util.*

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl
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

@MangaSourceParser("MANGATAROORG", "Mangataro", "en", ContentType.MANGA)
internal class Mangataro(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGATAROORG, 48) {

    override val configKeyDomain = ConfigKey.Domain("mangataro.org")

    private val chapterNumberRegex = Regex("""(\d+(?:\.\d+)?)""")

    private val tokenFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH", Locale.US)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isYearSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = genreTags,
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.OTHER),
    )


    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val payload = JSONObject().apply {
            put("page", page)
            put("search", filter.query.orEmpty())

            // Year
            val yearArray = JSONArray()
            if (filter.year > 0) yearArray.put(filter.year.toString())
            put("years", yearArray.toString())

            // Genres (tags)
            put("genres", filter.tags.toGenrePayload())

            // Types
            val typeArray = JSONArray()
            filter.types.forEach { ct ->
                val apiType = when (ct) {
                    ContentType.MANGA -> "Manga"
                    ContentType.MANHWA -> "Manhwa"
                    ContentType.MANHUA -> "Manhua"
                    ContentType.OTHER -> "Novel"
                    else -> null
                }
                if (apiType != null) typeArray.put(apiType)
            }
            put("types", typeArray.toString())

            // Statuses
            val statusArray = JSONArray()
            filter.states.forEach { state ->
                val apiStatus = when (state) {
                    MangaState.ONGOING -> "Ongoing"
                    MangaState.FINISHED -> "Completed"
                    else -> null
                }
                if (apiStatus != null) statusArray.put(apiStatus)
            }
            put("statuses", statusArray.toString())

            put("sort", order.toApiSort())
            put("genreMatchMode", "any")
        }
        val json = postJsonArray("https://$domain/wp-json/manga/v1/load", payload)
        return List(json.length()) { index ->
            val item = json.getJSONObject(index)
            item.toManga()
        }
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
            tags = tags.ifEmpty { manga.tags },
            authors = authors.ifEmpty { manga.authors },
            altTitles = altTitles.ifEmpty { manga.altTitles },
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
        val limit = 500
        var expectedTotal = Int.MAX_VALUE
        while (offset < expectedTotal) {
            val (token, timestamp) = generateChapterToken()
            val requestUrl = buildChapterUrl(mangaId, offset, limit, token, timestamp)
            val raw = webClient.httpGet(requestUrl, headers).parseRaw().trim()
            if (raw.isEmpty()) {
                break
            }
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: break
            if (!json.optBoolean("success", false)) {
                break
            }
            expectedTotal = json.optInt("total", expectedTotal)
            val chaptersArray = json.optJSONArray("chapters") ?: break
            if (chaptersArray.length() == 0) {
                break
            }
            for (i in 0 until chaptersArray.length()) {
                val item = chaptersArray.getJSONObject(i)
                val key = item.optString("id").ifEmpty { item.optString("url") }
                if (!seenIds.add(key)) {
                    continue
                }
                val fallbackNumber = (offset + i + 1).toFloat()
                result.add(item.toMangaChapter(fallbackNumber))
            }
            offset += chaptersArray.length()
            if (!json.optBoolean("has_more", false)) {
                break
            }
        }
        return result
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
            .addQueryParameter("order", "ASC")
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
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val chapterId = chapterUrl.extractChapterId()
            ?: fetchDocument(chapterUrl).body().attr("data-chapter-id").nullIfEmpty()
        if (chapterId != null) {
            val pages = loadChapterPages(chapterId, chapterUrl)
            if (pages.isNotEmpty()) {
                return pages
            }
        }
        val document = fetchDocument(chapterUrl)
        val containers = document.select("div.comic-image-container img")
        val seen = HashSet<String>(containers.size)
        return containers.mapNotNull { element ->
            val imageUrl = element.resolveImageUrl()
                ?.takeUnless { it.startsWith("data:") }
                ?: return@mapNotNull null
            if (!seen.add(imageUrl)) {
                return@mapNotNull null
            }
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun loadChapterPages(chapterId: String, referer: String): List<MangaPage> {
        val headers = Headers.headersOf(
            "Accept",
            "application/json",
            "Referer",
            referer,
        )
        val raw = webClient.httpGet("https://$domain/auth/chapter-content?chapter_id=$chapterId", headers)
            .parseRaw()
            .trim()
        if (raw.isEmpty()) {
            return emptyList()
        }
        val json = raw.toJsonObjectOrNull() ?: return emptyList()
        if (!json.optBoolean("success", false) || json.optString("chapter_type") == "text") {
            return emptyList()
        }
        val images = json.optJSONArray("images") ?: return emptyList()
        return List(images.length()) { index ->
            val imageUrl = images.getString(index)
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
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

    private fun JSONObject.toMangaChapter(fallbackNumber: Float): MangaChapter {
        val chapterUrl = optString("url")
        val rawChapter = optString("chapter")
        val chapterLabel = rawChapter.nullIfEmpty() ?: fallbackNumber.toChapterLabel()
        val rawTitle = optString("title").trim()
        val title = when {
            rawTitle.equals("N/A", ignoreCase = true) -> "Chapter $chapterLabel"
            rawTitle.isEmpty() -> null
            else -> rawTitle
        }
        val number = chapterNumberRegex.find(rawChapter)?.value?.toFloatOrNull()
        return MangaChapter(
            id = generateUid(chapterUrl),
            title = title,
            number = number ?: fallbackNumber,
            volume = 0,
            url = chapterUrl.toRelativeUrl(domain),
            scanlator = null,
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

    private fun SortOrder.toApiSort(): String = when (this) {
        SortOrder.POPULARITY -> "popular_desc"
        SortOrder.UPDATED -> "post_desc"
        SortOrder.NEWEST -> "release_desc"
        SortOrder.ALPHABETICAL -> "title_desc"
        else -> "post_desc"
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

    private fun String.extractChapterId(): String? = substringBefore('#')
        .substringBefore('?')
        .substringAfterLast('-')
        .takeIf { it.all(Char::isDigit) }
        ?.nullIfEmpty()

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
            val numericKey = tag.key.toIntOrNull()?.toString()
                ?: genreTags.firstOrNull { ref ->
                                ref.title.equals(tag.title, ignoreCase = true)
                            }?.key?.toIntOrNull()?.toString()
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

    private fun Element.resolveImageUrl(): String? {
        return attrAsAbsoluteUrlOrNull("data-src")
            ?: attrAsAbsoluteUrlOrNull("data-lazy-src")
            ?: attrAsAbsoluteUrlOrNull("src")
    }

    private val genreTags by lazy {
        setOf(
            tag("7", "Action"),
            tag("12", "Adventure"),
            tag("17", "Award Winning"),
            tag("80", "Boys Love"),
            tag("20", "Comedy"),
            tag("8", "Drama"),
            tag("40", "Ecchi"),
            tag("71", "Erotica"),
            tag("2", "Fantasy"),
            tag("34", "Full Color"),
            tag("49", "Girls Love"),
            tag("64", "Harem"),
            tag("18", "Historical"),
            tag("44", "Horror"),
            tag("3", "Isekai"),
            tag("43", "Josei"),
            tag("1172", "Long Strip"),
            tag("14", "Manga"),
            tag("33", "Manhua"),
            tag("6", "Manhwa"),
            tag("4", "Martial Arts"),
            tag("24", "Mystery"),
            tag("48", "One-shot"),
            tag("26", "Psychological"),
            tag("5", "Reincarnation"),
            tag("29", "Romance"),
            tag("10", "School"),
            tag("15", "Sci-Fi"),
            tag("19", "Seinen"),
            tag("42", "Shoujo"),
            tag("13", "Shounen"),
            tag("47", "Slice of Life"),
            tag("25", "Sports"),
            tag("9", "Supernatural"),
            tag("32", "Time Travel"),
            tag("39", "Webtoon"),
            tag("2094", "4-Koma"),
            tag("1050", "Abandoned Children"),
            tag("2386", "Ability Steal"),
            tag("2397", "Absent Parents"),
            tag("4012", "Academy"),
            tag("999", "Accelerated Growth"),
            tag("1351", "Adaptation"),
            tag("1000", "Adapted to Anime"),
            tag("5072", "Adapted to Drama CD"),
            tag("1001", "Adapted to Game"),
            tag("5069", "Adapted to Manga"),
            tag("963", "Adapted to Manhua"),
            tag("1002", "Adapted to Manhwa"),
            tag("5070", "Adapted to Visual Novel"),
            tag("1058", "Adopted Children"),
            tag("88", "Adult"),
            tag("16", "Adult Cast"),
            tag("2861", "Age Regression"),
            tag("964", "Alchemy"),
            tag("965", "Aliens"),
            tag("966", "Alternate World"),
            tag("2862", "Ancient Times"),
            tag("1135", "Animals"),
            tag("2096", "Anthology"),
            tag("93", "Anthropomorphic"),
            tag("5071", "Appearance Different from Actual Age"),
            tag("1037", "Aristocracy"),
            tag("2387", "Army"),
            tag("1005", "Army Building"),
            tag("1047", "Arranged Marriage"),
            tag("2401", "Arrogant Characters"),
            tag("4551", "Artbook"),
            tag("4004", "Artifacts"),
            tag("2863", "Assassins"),
            tag("98", "Avant Garde"),
            tag("5279", "Battle Academy"),
            tag("2388", "Battle Competition"),
            tag("1137", "BD"),
            tag("968", "Beast Companions"),
            tag("969", "Beautiful Female Lead"),
            tag("2864", "Betrayal"),
            tag("971", "Blacksmith"),
            tag("4237", "Boys' Love"),
            tag("1038", "Brotherhood"),
            tag("2398", "Business Management"),
            tag("1006", "Calm Protagonist"),
            tag("2392", "Cautious Protagonist"),
            tag("94", "CGDCT"),
            tag("4929", "Character growth"),
            tag("51", "Childcare"),
            tag("4818", "Children's"),
            tag("2393", "Cold Love Interests"),
            tag("2394", "Cold Protagonist"),
            tag("92", "Combat Sports"),
            tag("2118", "Cooking"),
            tag("1753", "Crime"),
            tag("81", "Crossdressing"),
            tag("2395", "Cruel Characters"),
            tag("1039", "Cunning Protagonist"),
            tag("4817", "Cute Stuffs"),
            tag("4930", "Dark"),
            tag("1044", "Death of Loved Ones"),
            tag("52", "Delinquents"),
            tag("1376", "Demons"),
            tag("975", "Dense Protagonist"),
            tag("58", "Detective"),
            tag("4008", "Devoted love interests"),
            tag("79", "Doujinshi"),
            tag("1040", "Dragons"),
            tag("1048", "Drugs"),
            tag("4795", "Dungeon"),
            tag("1011", "Dungeons"),
            tag("66", "Educational"),
            tag("976", "Elemental Magic"),
            tag("4010", "Elves"),
            tag("4005", "Family"),
            tag("59", "Gag Humor"),
            tag("36", "Game"),
            tag("979", "Game Elements"),
            tag("106", "Gender Bender"),
            tag("2093", "Genderswap"),
            tag("1134", "Ghosts"),
            tag("4739", "Gyaru"),
            tag("76", "High Stakes Game"),
            tag("82", "Hentai"),
            tag("4932", "Human nonhuman relationship"),
            tag("95", "Idols (Female)"),
            tag("101", "Idols (Male)"),
            tag("109", "Indonesian"),
            tag("91", "Iyashikei"),
            tag("107", "Kids"),
            tag("4006", "Kingdoms"),
            tag("1020", "Level System"),
            tag("55", "Light Novel"),
            tag("4207", "Loli"),
            tag("89", "Lolicon"),
            tag("1173", "Web Comic"),
            tag("41", "Love Polygon"),
            tag("100", "Love Status Quo"),
            tag("2061", "Mafia"),
            tag("35", "Magic"),
            tag("2095", "Magical Girls"),
            tag("68", "Magical Sex Shift"),
            tag("97", "Mahou Shoujo"),
            tag("4933", "Male"),
            tag("4792", "Mangataro Exclusive"),
            tag("1414", "Monster Girls"),
            tag("37", "Monsters"),
            tag("1024", "Multiple POV"),
            tag("38", "Murim"),
            tag("83", "Music"),
            tag("53", "Mythology"),
            tag("4601", "Ninja"),
            tag("103", "OEL"),
            tag("2062", "Office Workers"),
            tag("4834", "Official Colored"),
            tag("4210", "Oneshot"),
            tag("50", "Organized Crime"),
            tag("70", "Otaku Culture"),
            tag("21", "Parody"),
            tag("72", "Performing Arts"),
            tag("105", "Pets"),
            tag("63", "Philosophical"),
            tag("1136", "Police"),
            tag("1557", "Post-Apocalyptic"),
            tag("102", "Racing"),
            tag("4014", "Reincarnated in another world"),
            tag("61", "Reverse Harem"),
            tag("28", "Romantic Subtext"),
            tag("60", "Samurai"),
            tag("73", "School Life"),
            tag("1413", "Self-Published"),
            tag("4116", "Sexual Violence"),
            tag("90", "Shoujo Ai"),
            tag("104", "Shounen Ai"),
            tag("54", "Showbiz"),
            tag("4009", "Skill books"),
            tag("85", "Smut"),
            tag("84", "Space"),
            tag("77", "Strategy Game"),
            tag("22", "Super Power"),
            tag("4098", "Superhero"),
            tag("56", "Survival"),
            tag("75", "Suspense"),
            tag("995", "Sword And Magic"),
            tag("27", "Team Sports"),
            tag("591", "Thai"),
            tag("1341", "Thriller"),
            tag("4742", "Traditional Games"),
            tag("65", "Tragedy"),
            tag("4011", "Transported to another world"),
            tag("110", "Urban Fantasy"),
            tag("45", "Vampire"),
            tag("2102", "Vampires"),
            tag("31", "Video Game"),
            tag("1366", "Video Games"),
            tag("62", "Villainess"),
            tag("4485", "Virtual Reality"),
            tag("57", "Visual Arts"),
            tag("69", "Workplace"),
            tag("1350", "Wuxia"),
            tag("2391", "Xianxia"),
            tag("962", "Xuanhuan"),
            tag("86", "Yaoi"),
            tag("99", "Yuri"),
            tag("1342", "Zombies"),
        )
    }
}