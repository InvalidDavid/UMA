package tsuki.site.es

import org.jsoup.nodes.Document
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("LMTOS", "Lmtos", "es")
internal class Lmtos(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.LMTOS, 20) {

    override val configKeyDomain = ConfigKey.Domain("lmtos.net")
    private val baseUrl = "https://$domain"

    @Volatile
    private var mangaCache: List<MangaDto>? = null

    @Volatile
    private var cacheTimestamp = 0L
    private val cacheDuration = 10 * 60 * 1000L

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .set("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        .set("User-Agent", config[userAgentKey])
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GENRES.map { genre ->
            MangaTag(key = genre, title = genre, source = source)
        }.toSet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHUA,
            ContentType.MANHWA,
            ContentType.ONE_SHOT,
        ),
    )

    private suspend fun fetchMangas(): List<MangaDto> {
        val cached = mangaCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cacheTimestamp < cacheDuration) return cached

        val doc = webClient.httpGet("$baseUrl/series").parseHtml()

        val mangasArray = doc.extractNextJs { payload ->
            payload is JSONArray && payload.length() > 0 &&
                    payload.optJSONObject(0)?.has("slug") == true &&
                    payload.optJSONObject(0)?.has("title") == true
        } as? JSONArray
            ?: throw Exception("Could not find 'mangas' array in Next.js payload")

        val list = ArrayList<MangaDto>(mangasArray.length())
        for (i in 0 until mangasArray.length()) {
            list.add(MangaDto.fromJson(mangasArray.getJSONObject(i)))
        }

        mangaCache = list
        cacheTimestamp = now
        return list
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val allMangas = fetchMangas()
        val query = filter.query?.trim().orEmpty()
        val selectedGenres = filter.tags.map { it.key }
        val selectedState = filter.states.firstOrNull()
        val selectedType = filter.types.firstOrNull()

        val filtered = allMangas.asSequence()
            .filter { manga ->
                query.isEmpty() ||
                        manga.title.contains(query, ignoreCase = true) ||
                        manga.alternativeTitles?.any { it.contains(query, ignoreCase = true) } == true
            }
            .filter { manga ->
                when (filter.contentRating.firstOrNull()) {
                    ContentRating.ADULT -> manga.isAdult
                    ContentRating.SAFE -> !manga.isAdult
                    else -> true
                }
            }
            .filter { manga ->
                when (selectedType) {
                    null -> true
                    ContentType.MANGA -> manga.type == "manga"
                    ContentType.MANHUA -> manga.type == "manhua"
                    ContentType.MANHWA -> manga.type == "manhwa"
                    ContentType.ONE_SHOT -> manga.type == "oneshot"
                    else -> true
                }
            }
            .filter { manga ->
                when (selectedState) {
                    null -> true
                    MangaState.ONGOING -> manga.status == "ongoing"
                    MangaState.FINISHED -> manga.status == "completed"
                    MangaState.PAUSED -> manga.status == "paused"
                    else -> true
                }
            }
            .filter { manga ->
                selectedGenres.isEmpty() ||
                        selectedGenres.all { g -> manga.genres?.contains(g) == true }
            }
            .let { sequence ->
                when (order) {
                    SortOrder.ALPHABETICAL -> sequence.sortedBy { it.title }
                    SortOrder.UPDATED -> sequence.sortedByDescending { it.latestChapterCreatedAt ?: 0L }
                    SortOrder.POPULARITY -> sequence.sortedByDescending { it.totalViews ?: 0 }
                    else -> sequence
                }
            }
            .toList()

        return filtered.drop((page - 1) * pageSize).take(pageSize)
            .map { it.toManga(baseUrl, source) }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()

        val mangaObj = doc.extractNextJs { payload ->
            payload is JSONObject &&
                    payload.has("slug") &&
                    payload.has("title") &&
                    payload.has("genres")
        } as? JSONObject ?: throw Exception("Could not find 'manga' object")

        val chaptersArray = doc.extractNextJs { payload ->
            payload is JSONArray && payload.length() > 0 &&
                    payload.optJSONObject(0)?.has("slug") == true &&
                    payload.optJSONObject(0)?.has("number") == true
        } as? JSONArray ?: JSONArray()

        val dto = MangaDto.fromJson(mangaObj)
        val mangaSlug = manga.url.substringAfterLast("/")

        val chapters = ArrayList<MangaChapter>(chaptersArray.length())
        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.getJSONObject(i)
            val slug = ch.optString("slug")
            val chNumber = ch.optDouble("number", -1.0).toFloat()
            val chHref = "/manga/$mangaSlug/$slug"
            chapters.add(
                MangaChapter(
                    id = generateUid(source, chHref),
                    url = chHref,
                    title = "Ch. ${chNumber.toString().removeSuffix(".0")}",
                    number = chNumber,
                    volume = 0,
                    uploadDate = parseDate(ch.optString("createdAt")),
                    source = source,
                    scanlator = null,
                    branch = null,
                )
            )
        }
        chapters.sortBy { it.number }

        return dto.toManga(baseUrl, source).copy(
            chapters = chapters,
            description = dto.description ?: manga.description,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val chapterObj = doc.extractNextJs { payload ->
            payload is JSONObject && payload.has("pages")
        } as? JSONObject ?: throw Exception("Could not find 'chapter' object")

        val pagesArray = chapterObj.optJSONArray("pages") ?: JSONArray()

        val pages = ArrayList<MangaPage>(pagesArray.length())
        for (i in 0 until pagesArray.length()) {
            val pageUrl = pagesArray.getString(i)
            pages.add(
                MangaPage(
                    id = generateUid(source, pageUrl),
                    url = pageUrl,
                    preview = null,
                    source = source,
                )
            )
        }
        return pages
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching { createDateFormat().parse(dateStr)?.time ?: 0L }.getOrDefault(0L)
    }

    private data class MangaDto(
        val slug: String,
        val title: String,
        val alternativeTitles: List<String>? = null,
        val description: String? = null,
        val coverImage: String? = null,
        val isAdult: Boolean = false,
        val type: String? = null,
        val status: String? = null,
        val demographic: String? = null,
        val genres: List<String>? = null,
        val author: String? = null,
        val artist: String? = null,
        val latestChapterCreatedAt: Long? = null,
        val totalViews: Int? = null,
    ) {
        fun toManga(baseUrl: String, source: MangaSource): Manga {
            val path = "/manga/$slug"
            val tags = genres?.map { g ->
                MangaTag(key = g.lowercase(), title = g, source = source)
            }.orEmpty().toSet()
            val state = when (status?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "paused" -> MangaState.PAUSED
                else -> null
            }
            return Manga(
                id = generateUid(source, path),
                url = path,
                publicUrl = "$baseUrl$path",
                title = title,
                altTitles = alternativeTitles.orEmpty().toSet(),
                coverUrl = coverImage?.takeIf { it.isNotEmpty() }?.let {
                    if (it.startsWith("http")) it else "$baseUrl/$it"
                } ?: "",
                rating = RATING_UNKNOWN,
                contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
                tags = tags,
                state = state,
                authors = setOfNotNull(author, artist),
                source = source,
            )
        }

        companion object {
            fun fromJson(obj: JSONObject): MangaDto {
                val altTitles = obj.optJSONArray("alternativeTitles")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
                val genres = obj.optJSONArray("genres")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
                val latest = obj.optString("latestChapterCreatedAt")
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { createDateFormat().parse(it)?.time }.getOrNull() }
                return MangaDto(
                    slug = obj.optString("slug"),
                    title = obj.optString("title"),
                    alternativeTitles = altTitles,
                    description = obj.optString("description").takeIf { it.isNotEmpty() },
                    coverImage = obj.optString("coverImage").takeIf { it.isNotEmpty() },
                    isAdult = obj.optBoolean("isAdult", false),
                    type = obj.optString("type").takeIf { it.isNotEmpty() },
                    status = obj.optString("status").takeIf { it.isNotEmpty() },
                    demographic = obj.optString("demographic").takeIf { it.isNotEmpty() },
                    genres = genres,
                    author = obj.optString("author").takeIf { it.isNotEmpty() },
                    artist = obj.optString("artist").takeIf { it.isNotEmpty() },
                    latestChapterCreatedAt = latest,
                    totalViews = obj.optInt("totalViews").takeIf { it > 0 },
                )
            }
        }
    }

    companion object {
        private fun createDateFormat(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        private val GENRES = listOf(
            "Acción", "Artes Marciales", "Aventuras", "Carreras", "Ciencia Ficción",
            "Comedia", "Demencia", "Demonios", "Deportes", "Drama", "Ecchi",
            "Escolares", "Gore", "Harem", "Isekai", "Juegos", "Magia", "Mecha",
            "Militar", "Misterio", "Música", "Parodia", "Policía", "Psicológico",
            "Recuentos de la vida", "Romance", "Romcom", "Samurai", "Sobrenatural",
            "Superpoderes", "Suspenso", "Terror", "Vampiros", "Yaoi", "Yuri",
        )
    }
}

private val NEXT_F_REGEX =
    Regex("""self\.__next_f\.push\(\s*(\[.*])\s*\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL)

private fun extractValueNextJs(payload: Any, predicate: (Any) -> Boolean): Any? {
    if (payload !is JSONObject && payload !is JSONArray) return null
    if (predicate(payload)) return payload

    if (payload is JSONObject) {
        val keys = payload.keys()
        while (keys.hasNext()) {
            val child = payload.opt(keys.next())
            if (child != null) extractValueNextJs(child, predicate)?.let { return it }
        }
    } else if (payload is JSONArray) {
        for (i in 0 until payload.length()) {
            val child = payload.opt(i)
            if (child != null) extractValueNextJs(child, predicate)?.let { return it }
        }
    }
    return null
}

private fun Document.extractAppRouterPayloads(): List<Any> =
    select("script:not([src])")
        .map { it.data() }
        .filter { "self.__next_f.push" in it }
        .flatMap { script ->
            try {
                val raw = NEXT_F_REGEX.find(script)?.groupValues?.get(1)
                    ?: return@flatMap emptyList()
                val arr = JSONArray(raw)
                val content = arr.optString(1, null) ?: return@flatMap emptyList()
                extractRscPayloads(content)
            } catch (_: Exception) {
                emptyList()
            }
        }

private fun Document.extractPagesRouterPayloads(): List<Any> {
    val data = selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
    return try {
        val root = JSONObject(data)
        val pageProps = root.optJSONObject("props")?.optJSONObject("pageProps")
        listOfNotNull(pageProps, root)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun extractRscPayloads(body: String): List<Any> {
    val results = mutableListOf<Any>()
    var pos = 0

    while (pos < body.length) {
        val colonIdx = body.indexOf(':', pos)
        if (colonIdx == -1) break

        val id = body.substring(pos, colonIdx)
        if (id.isEmpty() || !id.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            pos++
            continue
        }

        pos = colonIdx + 1
        if (pos >= body.length) break

        if (body[pos] == 'T') {
            pos++
            val commaIdx = body.indexOf(',', pos)
            if (commaIdx == -1) break
            val byteLen = body.substring(pos, commaIdx).toIntOrNull(16) ?: break
            pos = commaIdx + 1
            var bytes = 0
            val start = pos
            while (pos < body.length && bytes < byteLen) {
                when {
                    body[pos].code < 0x80 -> bytes += 1
                    body[pos].code < 0x800 -> bytes += 2
                    Character.isHighSurrogate(body[pos]) -> {
                        bytes += 4
                        pos++
                    }
                    else -> bytes += 3
                }
                pos++
            }
            try {
                results.add(parseJsonType(body.substring(start, pos)))
            } catch (_: Exception) {
            }
        } else {
            val (element, end) = parseJsonAt(body, pos)
            if (element != null) results.add(element)
            pos = end
        }
    }
    return results
}

private fun parseJsonType(jsonStr: String): Any {
    val trimmed = jsonStr.trim()
    return when {
        trimmed.startsWith("{") -> JSONObject(trimmed)
        trimmed.startsWith("[") -> JSONArray(trimmed)
        else -> throw JSONException("Not a JSONObject or JSONArray")
    }
}

private fun parseJsonAt(body: String, start: Int): Pair<Any?, Int> {
    if (start >= body.length) return Pair(null, start)

    var depth = 0
    var inString = false
    var escape = false
    var i = start

    while (i < body.length) {
        val c = body[i++]
        if (escape) {
            escape = false
            continue
        }
        if (c == '\\' && inString) {
            escape = true
            continue
        }
        if (c == '"') {
            inString = !inString
            continue
        }
        if (inString) continue
        when (c) {
            '{', '[' -> depth++
            '}', ']' -> if (--depth == 0) {
                return try {
                    Pair(parseJsonType(body.substring(start, i)), i)
                } catch (_: Exception) {
                    Pair(null, i)
                }
            }
        }
        if (depth == 0 && c.isWhitespace()) {
            return try {
                Pair(parseJsonType(body.substring(start, i - 1)), i)
            } catch (_: Exception) {
                Pair(null, i)
            }
        }
    }
    return Pair(null, i)
}

private fun Document.extractNextJs(predicate: (Any) -> Boolean): Any? {
    val payloads = extractAppRouterPayloads().ifEmpty { extractPagesRouterPayloads() }
    for (payload in payloads) {
        extractValueNextJs(payload, predicate)?.let { return it }
    }
    return null
}
