package org.koitharu.kotatsu.parsers.site.kotatsu.all

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.network.WebClient
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.insertCookies
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.ownTextOrNull
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

private const val DOMAIN = "mangaball.net"

internal abstract class MangaBallParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    private val siteLanguages: Set<String>,
) : PagedMangaParser(context, source, pageSize = 20), Interceptor {

    override val configKeyDomain = ConfigKey.Domain(DOMAIN)

    private val showSuspiciousContentKey = ConfigKey.ShowSuspiciousContent(false)
    private val rawHttpClient: OkHttpClient by lazy { context.httpClient.newBuilder().build() }
    private val client: WebClient by lazy {
        OkHttpWebClient(
            context.httpClient.newBuilder()
                .addInterceptor(this)
                .build(),
            source,
        )
    }

    @Volatile
    private var csrfToken: String? = null

    private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.RELEVANCE,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
        )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(showSuspiciousContentKey)
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = TAG_DEFINITIONS.mapTo(LinkedHashSet(TAG_DEFINITIONS.size)) {
            MangaTag(key = it.id, title = it.title, source = source)
        },
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
        ),
        availableDemographics = EnumSet.of(
            Demographic.SHOUNEN,
            Demographic.SHOUJO,
            Demographic.SEINEN,
            Demographic.JOSEI,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        ensureAdultCookie()
        val query = filter.query?.trim().orEmpty()
        if (page == 1 && query.startsWith("http")) {
            return resolveSearchUrl(query)
        }
        val isQueryOnly = query.isNotEmpty() && !filter.hasNonSearchOptions()
        return when {
            isQueryOnly && page == 1 -> smartSearch(query)
            isQueryOnly -> advancedSearch(page - 1, order, filter)
            else -> advancedSearch(page, order, filter)
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        detailsCache[manga.url]?.let { return it }

        ensureAdultCookie()
        val doc = client.httpGet(getMangaUrl(manga.url)).parseHtml()
        updateCsrf(doc)

        val tags = doc.select("#comicDetail span[data-tag-id]").mapTo(LinkedHashSet()) { tag ->
            MangaTag(
                key = tag.attr("data-tag-id"),
                title = tag.ownTextOrNull().orEmpty().trim(),
                source = source,
            )
        }
        val altTitles = doc.select("div.alternate-name-container").text()
            .split('/')
            .mapNotNullTo(LinkedHashSet()) { it.trim().nullIfEmpty() }
        val authors = doc.select("#comicDetail span[data-person-id]")
            .mapNotNullTo(LinkedHashSet()) { it.text().trim().nullIfEmpty() }
        val status = when (doc.selectFirst("span.badge-status")?.text()?.trim()) {
            "Ongoing" -> MangaState.ONGOING
            "Completed" -> MangaState.FINISHED
            "Hiatus" -> MangaState.PAUSED
            "Cancelled" -> MangaState.ABANDONED
            else -> null
        }
        val title = doc.selectFirst("#comicDetail h6")?.ownTextOrNull()?.trim()?.nullIfEmpty() ?: manga.title
        val cover = doc.selectFirst("img.featured-cover")?.absUrl("src")?.nullIfEmpty() ?: manga.coverUrl
        val description = doc.selectFirst("#descriptionContent p")?.wholeText()?.trim()?.nullIfEmpty()
        val contentRating = when {
            tags.any { it.title in ADULT_TAG_TITLES } -> ContentRating.ADULT
            else -> manga.contentRating
        }

        val result = manga.copy(
            title = title,
            altTitles = if (altTitles.isEmpty()) manga.altTitles else altTitles,
            publicUrl = getMangaUrl(manga.url),
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags,
            state = status,
            authors = authors,
            description = description,
            chapters = getChapterList(manga.url),
            contentRating = contentRating,
        )
        detailsCache[manga.url] = result
        return result
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        ensureAdultCookie()
        val doc = client.httpGet(getChapterUrl(chapter)).parseHtml()
        updateCsrf(doc)
        val script = doc.select("script")
            .firstOrNull { it.data().contains("chapterImages") }
            ?.data()
            .orEmpty()
        val rawImages = CHAPTER_IMAGES_REGEX.find(script)?.groupValues?.getOrNull(1).orEmpty()
        if (rawImages.isEmpty()) {
            return emptyList()
        }
        val images = JSONArray(rawImages)
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

    override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
        if (link.host != domain) {
            return null
        }
        val pathType = link.pathSegments.firstOrNull() ?: return null
        val slug = when (pathType) {
            "title-detail" -> link.pathSegments.getOrNull(1)
            "chapter-detail" -> resolveChapterSlug(link.toString())
            else -> null
        } ?: return null
        return getDetails(seedManga(slug))
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        ensureAdultCookie()
        val request = chain.request()
        var builder = request.newBuilder()
        if (
            (request.url.host == domain || request.url.host.endsWith(".poke-black-and-white.net")) &&
            request.header("Referer").isNullOrEmpty()
        ) {
            builder = builder.header("Referer", "https://$domain/")
        }
        if (request.url.host == domain && request.url.pathSegments.firstOrNull() == "api") {
            builder = builder
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRF-TOKEN", getCsrf())
        }
        var response = chain.proceed(builder.build())
        if (response.code == 403 && request.url.host == domain && request.url.pathSegments.firstOrNull() == "api") {
            response.close()
            refreshCsrf()
            response = chain.proceed(
                request.newBuilder()
                    .header("Referer", "https://$domain/")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("X-CSRF-TOKEN", getCsrf())
                    .build(),
            )
        }
        return response
    }

    private suspend fun smartSearch(query: String): List<Manga> {
        val payload = mapOf("search_input" to query)
        val json = postApi("$BASE_URL/api/v1/smart-search/search/".toHttpUrl(), payload).parseJson()
        val mangaArray = json.getJSONObject("data").getJSONArray("manga")
        return List(mangaArray.length()) { index ->
            val item = mangaArray.getJSONObject(index)
            val slug = extractSlug(item.getString("url"))
            Manga(
                id = generateUid(slug),
                title = item.getString("title"),
                altTitles = emptySet(),
                url = slug,
                publicUrl = getMangaUrl(slug),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = item.getString("img").nullIfEmpty(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    private suspend fun advancedSearch(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (page < 1) {
            return emptyList()
        }
        val payload = buildAdvancedPayload(page, order, filter)
        val json = postApi("$BASE_URL/api/v1/title/search-advanced/".toHttpUrl(), payload).parseJson()
        val data = json.optJSONArray("data") ?: return emptyList()
        return List(data.length()) { index ->
            val item = data.getJSONObject(index)
            val slug = extractSlug(item.getString("url"))
            Manga(
                id = generateUid(slug),
                title = item.getString("name"),
                altTitles = emptySet(),
                url = slug,
                publicUrl = getMangaUrl(slug),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = item.optString("cover").nullIfEmpty(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    private fun buildAdvancedPayload(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val includedTags = LinkedHashSet<String>()
        includedTags += filter.tags.map { it.key }
        filter.types.oneOrThrowIfMany()?.let { type ->
            includedTags += TYPE_TAG_IDS.getValue(type)
        }

        return buildList {
            addEncoded("search_input", filter.query?.trim().orEmpty())
            addEncoded("filters[sort]", sortValue(order))
            addEncoded("filters[page]", page.toString())
            includedTags.forEach { addEncoded("filters[tag_included_ids][]", it) }
            addEncoded("filters[tag_included_mode]", "and")
            filter.tagsExclude.forEach { addEncoded("filters[tag_excluded_ids][]", it.key) }
            addEncoded("filters[tag_excluded_mode]", "or")
            addEncoded("filters[contentRating]", "any")
            addEncoded("filters[demographic]", demographicValue(filter.demographics.oneOrThrowIfMany()))
            addEncoded("filters[person]", "any")
            addEncoded("filters[publicationYear]", filter.year.takeIf { it > 0 }?.toString().orEmpty())
            addEncoded("filters[publicationStatus]", stateValue(filter.states.oneOrThrowIfMany()))
            siteLanguages.forEach { addEncoded("filters[translatedLanguage][]", it) }
        }.joinToString("&")
    }

    private suspend fun getChapterList(mangaSlug: String): List<MangaChapter> {
        val titleId = mangaSlug.substringAfterLast('-')
        val json = postApi(
            "$BASE_URL/api/v1/chapter/chapter-listing-by-title-id/".toHttpUrl(),
            mapOf("title_id" to titleId),
        ).parseJson()
        val containers = json.getJSONArray("ALL_CHAPTERS")
        val chapterCandidates = ArrayList<ChapterCandidate>(containers.length())
        for (i in 0 until containers.length()) {
            val container = containers.getJSONObject(i)
            val number = container.optDouble("number_float", 0.0).toFloat()
            val translations = container.getJSONArray("translations")
            for (j in 0 until translations.length()) {
                val translation = translations.getJSONObject(j)
                val language = translation.optString("language")
                if (language !in siteLanguages) {
                    continue
                }
                val group = translation.getJSONObject("group")
                val groupId = group.optString("_id")
                val groupName = group.optString("name").trim()
                val scanlator = buildString {
                    append(groupName)
                    if (!GROUP_ID_REGEX.matches(groupId)) {
                        append(" (")
                        append(groupId)
                        append(')')
                    }
                }.nullIfEmpty()
                val volume = translation.optInt("volume", 0)
                val translationName = translation.optString("name").trim()
                val numberText = number.toString().removeSuffix(".0")
                val chapter = MangaChapter(
                    id = generateUid(translation.getString("id")),
                    title = buildChapterTitle(numberText, volume, translationName),
                    number = number,
                    volume = volume,
                    url = translation.getString("id"),
                    scanlator = scanlator,
                    uploadDate = chapterDateFormat.parseSafe(translation.optString("date")),
                    branch = language.takeIf { siteLanguages.size > 1 },
                    source = source,
                )
                chapterCandidates += ChapterCandidate(language = language, chapter = chapter)
            }
        }
        val scanlatorCounts = chapterCandidates.groupingBy {
            "${it.language}|${it.chapter.scanlator.orEmpty()}"
        }.eachCount()
        return chapterCandidates
            .groupBy { "${it.language}|${it.chapter.number}" }
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull { left, right ->
                    compareChapterCandidates(
                        left = left,
                        right = right,
                        scanlatorCounts = scanlatorCounts,
                    )
                }?.chapter
            }
            .sortedWith(
                compareBy<MangaChapter> { it.number }
                    .thenBy { if (it.volume > 0) 1 else 0 }
                    .thenBy { it.uploadDate }
                    .thenBy { it.branch.orEmpty() }
                    .thenBy { it.title.orEmpty() },
            )
    }

    private fun buildChapterTitle(numberText: String, volume: Int, rawName: String): String {
        val baseTitle = buildString {
            if (volume > 0) {
                append("Vol. ")
                append(volume)
                append(' ')
            }
            append("Chapter ")
            append(numberText)
        }
        val name = rawName.trim()
        if (name.isEmpty()) {
            return baseTitle
        }
        val compactName = name.replace(Regex("\\s+"), " ")
        val chapterPrefixRegex = Regex("^(Vol\\.?\\s*\\d+\\s+)?Ch(?:apter)?\\.?\\s*", RegexOption.IGNORE_CASE)
        val normalized = chapterPrefixRegex.find(compactName)?.let { match ->
            val volumePrefix = match.groups[1]?.value?.trim().orEmpty()
            buildString {
                if (volumePrefix.isNotEmpty()) {
                    append(volumePrefix.replaceFirst(Regex("(?i)^vol\\.?"), "Vol."))
                    append(' ')
                }
                append("Chapter ")
                append(compactName.removeRange(match.range))
            }
        }?.trim() ?: compactName.trim()
        return if (normalized.startsWith("Chapter $numberText", ignoreCase = true) ||
            normalized.startsWith("Vol. $volume Chapter $numberText", ignoreCase = true)
        ) {
            normalized
        } else {
            "$baseTitle: $normalized"
        }
    }

    private fun compareChapterCandidates(
        left: ChapterCandidate,
        right: ChapterCandidate,
        scanlatorCounts: Map<String, Int>,
    ): Int {
        val leftChapter = left.chapter
        val rightChapter = right.chapter
        val leftScore = scanlatorCounts["${left.language}|${leftChapter.scanlator.orEmpty()}"] ?: 0
        val rightScore = scanlatorCounts["${right.language}|${rightChapter.scanlator.orEmpty()}"] ?: 0
        if (leftScore != rightScore) {
            return leftScore.compareTo(rightScore)
        }
        if ((leftChapter.volume > 0) != (rightChapter.volume > 0)) {
            return if (leftChapter.volume == 0) 1 else -1
        }
        val leftTitle = leftChapter.title.orEmpty()
        val rightTitle = rightChapter.title.orEmpty()
        if (leftTitle.length != rightTitle.length) {
            return rightTitle.length.compareTo(leftTitle.length)
        }
        if (leftChapter.uploadDate != rightChapter.uploadDate) {
            return leftChapter.uploadDate.compareTo(rightChapter.uploadDate)
        }
        return rightChapter.id.compareTo(leftChapter.id)
    }

    private suspend fun resolveSearchUrl(query: String): List<Manga> {
        val slug = when {
            query.startsWith("https://") || query.startsWith("http://") -> {
                val link = query.toHttpUrl()
                when (link.pathSegments.firstOrNull()) {
                    "title-detail" -> link.pathSegments.getOrNull(1)
                    "chapter-detail" -> resolveChapterSlug(query)
                    else -> null
                }
            }
            else -> null
        } ?: return emptyList()
        return listOf(getDetails(seedManga(slug)))
    }

    private suspend fun resolveChapterSlug(chapterUrl: String): String? {
        val doc = client.httpGet(chapterUrl).parseHtml()
        updateCsrf(doc)
        val yoastRaw = doc.selectFirst("script.yoast-schema-graph")?.data()?.nullIfEmpty() ?: return null
        val graph = JSONObject(yoastRaw).optJSONArray("@graph") ?: return null
        for (i in 0 until graph.length()) {
            val item = graph.getJSONObject(i)
            if (item.optString("@type") == "WebPage") {
                val url = item.optString("url").nullIfEmpty() ?: continue
                return url.toHttpUrl().pathSegments.getOrNull(1)
            }
        }
        return null
    }

    private fun seedManga(slug: String): Manga = Manga(
        id = generateUid(slug),
        title = slug.substringBeforeLast('-').replace('-', ' ').trim(),
        altTitles = emptySet(),
        url = slug,
        publicUrl = getMangaUrl(slug),
        rating = RATING_UNKNOWN,
        contentRating = null,
        coverUrl = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
    )

    private fun extractSlug(url: String): String = url.toAbsoluteUrl(domain).toHttpUrl().pathSegments[1]

    private fun getMangaUrl(slug: String): String = "$BASE_URL/title-detail/$slug/"

    private fun getChapterUrl(chapter: MangaChapter): String = "$BASE_URL/chapter-detail/${chapter.url}/"

    private fun getHeadersWithReferer(): Headers = getRequestHeaders().newBuilder()
        .add("Referer", "$BASE_URL/")
        .build()

    private fun getApiHeaders(): Headers = getHeadersWithReferer().newBuilder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("X-CSRF-TOKEN", getCsrf())
        .build()

    private suspend fun postApi(url: HttpUrl, form: Map<String, String>): Response {
        return try {
            client.httpPost(url, form, getApiHeaders())
        } catch (e: HttpStatusException) {
            if (e.statusCode != 403) throw e
            refreshCsrf()
            client.httpPost(url, form, getApiHeaders())
        }
    }

    private suspend fun postApi(url: HttpUrl, payload: String): Response {
        return try {
            client.httpPost(url, payload, getApiHeaders())
        } catch (e: HttpStatusException) {
            if (e.statusCode != 403) throw e
            refreshCsrf()
            client.httpPost(url, payload, getApiHeaders())
        }
    }

    @Synchronized
    private fun ensureAdultCookie() {
        context.cookieJar.insertCookies(domain, "show18PlusContent=${config[showSuspiciousContentKey]}")
    }

    @Synchronized
    private fun getCsrf(): String {
        if (csrfToken.isNullOrEmpty()) {
            refreshCsrf()
            if (csrfToken.isNullOrEmpty()) {
                refreshCsrf()
            }
            if (csrfToken.isNullOrEmpty()) {
                error("CSRF token not found after two attempts")
            }
        }
        return csrfToken!!
    }

    @Synchronized
    private fun refreshCsrf(document: Document? = null) {
        ensureAdultCookie()
        if (document != null) {
            updateCsrf(document)
            if (!csrfToken.isNullOrEmpty()) return
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .headers(getHeadersWithReferer())
            .header("Cache-Control", "no-cache")
            .build()
        val response = rawHttpClient.newCall(request).execute()
        updateCsrf(response.parseHtml())
        response.close()
    }

    @Synchronized
    private fun updateCsrf(document: Document) {
        document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()?.nullIfEmpty()?.let {
            csrfToken = it
        }
    }

    @get:Synchronized
    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 5
    }

    private fun MutableList<String>.addEncoded(key: String, value: String) {
        add("${key.urlEncoded()}=${value.urlEncoded()}")
    }

    private fun sortValue(order: SortOrder): String = when (order) {
        SortOrder.UPDATED -> "updated_chapters_desc"
        SortOrder.POPULARITY -> "views_desc"
        SortOrder.NEWEST -> "created_at_desc"
        SortOrder.ALPHABETICAL -> "name_asc"
        SortOrder.ALPHABETICAL_DESC -> "name_desc"
        SortOrder.RELEVANCE -> "updated_chapters_desc"
        else -> "updated_chapters_desc"
    }

    private fun stateValue(state: MangaState?): String = when (state) {
        MangaState.ONGOING -> "ongoing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        MangaState.ABANDONED -> "cancelled"
        else -> "any"
    }

    private fun demographicValue(demographic: Demographic?): String = when (demographic) {
        Demographic.SHOUNEN -> "shounen"
        Demographic.SHOUJO -> "shoujo"
        Demographic.SEINEN -> "seinen"
        Demographic.JOSEI -> "josei"
        else -> "any"
    }

    private data class ChapterCandidate(
        val language: String,
        val chapter: MangaChapter,
    )

    companion object {

        private const val BASE_URL = "https://$DOMAIN"
        private val GROUP_ID_REGEX = Regex("[a-z0-9]{24}")
        private val CHAPTER_IMAGES_REGEX = Regex("""const\s+chapterImages\s*=\s*JSON\.parse\(`([^`]+)`\)""")
        private val ADULT_TAG_TITLES = setOf(
            "Adult",
            "Ecchi",
            "Mature",
            "Smut",
            "Hentai",
            "Manhwa 18+",
            "Sexual Violence",
        )

        private val TYPE_TAG_IDS = mapOf(
            ContentType.COMICS to "68ecab8507ec62d87e62780f",
            ContentType.MANGA to "68ecab1e07ec62d87e627806",
            ContentType.MANHUA to "68ecab4807ec62d87e62780b",
            ContentType.MANHWA to "68ecab3b07ec62d87e627809",
        )

        private data class TagDef(
            val id: String,
            val title: String,
        )

        private val TAG_DEFINITIONS = listOf(
            TagDef("685148d115e8b86aae68e4f3", "Gore"),
            TagDef("685146c5f3ed681c80f257e7", "Sexual Violence"),
            TagDef("685148d115e8b86aae68e4ec", "4-Koma"),
            TagDef("685148cf15e8b86aae68e4de", "Adaptation"),
            TagDef("685148e915e8b86aae68e558", "Anthology"),
            TagDef("685148fe15e8b86aae68e5a7", "Award Winning"),
            TagDef("6851490e15e8b86aae68e5da", "Doujinshi"),
            TagDef("6851498215e8b86aae68e704", "Fan Colored"),
            TagDef("685148d615e8b86aae68e502", "Full Color"),
            TagDef("685148d915e8b86aae68e517", "Long Strip"),
            TagDef("6851493515e8b86aae68e64a", "Official Colored"),
            TagDef("685148eb15e8b86aae68e56c", "Oneshot"),
            TagDef("6851492e15e8b86aae68e633", "Self-Published"),
            TagDef("685148d715e8b86aae68e50d", "Web Comic"),
            TagDef("685146c5f3ed681c80f257e3", "Action"),
            TagDef("689371f0a943baf927094f03", "Adult"),
            TagDef("685146c5f3ed681c80f257e6", "Adventure"),
            TagDef("685148ef15e8b86aae68e573", "Boys' Love"),
            TagDef("685146c5f3ed681c80f257e5", "Comedy"),
            TagDef("685148da15e8b86aae68e51f", "Crime"),
            TagDef("685148cf15e8b86aae68e4dd", "Drama"),
            TagDef("6892a73ba943baf927094e37", "Ecchi"),
            TagDef("685146c5f3ed681c80f257ea", "Fantasy"),
            TagDef("685148da15e8b86aae68e524", "Girls' Love"),
            TagDef("685148db15e8b86aae68e527", "Historical"),
            TagDef("685148da15e8b86aae68e520", "Horror"),
            TagDef("685146c5f3ed681c80f257e9", "Isekai"),
            TagDef("6851490d15e8b86aae68e5d4", "Magical Girls"),
            TagDef("68932d11a943baf927094e7b", "Mature"),
            TagDef("6851490c15e8b86aae68e5d2", "Mecha"),
            TagDef("6851494e15e8b86aae68e66e", "Medical"),
            TagDef("685148d215e8b86aae68e4f4", "Mystery"),
            TagDef("685148e215e8b86aae68e544", "Philosophical"),
            TagDef("685148d715e8b86aae68e507", "Psychological"),
            TagDef("685148cf15e8b86aae68e4db", "Romance"),
            TagDef("685148cf15e8b86aae68e4da", "Sci-Fi"),
            TagDef("689f0ab1f2e66744c6091524", "Shounen Ai"),
            TagDef("685148d015e8b86aae68e4e3", "Slice of Life"),
            TagDef("689371f2a943baf927094f04", "Smut"),
            TagDef("685148f515e8b86aae68e588", "Sports"),
            TagDef("6851492915e8b86aae68e61c", "Superhero"),
            TagDef("685148d915e8b86aae68e51e", "Thriller"),
            TagDef("685148db15e8b86aae68e529", "Tragedy"),
            TagDef("68932c3ea943baf927094e77", "User Created"),
            TagDef("6851490715e8b86aae68e5c3", "Wuxia"),
            TagDef("68932f68a943baf927094eaa", "Yaoi"),
            TagDef("6896a885a943baf927094f66", "Yuri"),
            TagDef("6851490d15e8b86aae68e5d5", "Aliens"),
            TagDef("685148e715e8b86aae68e54b", "Animals"),
            TagDef("68bf09ff8fdeab0b6a9bc2b7", "Comics"),
            TagDef("685148d215e8b86aae68e4f8", "Cooking"),
            TagDef("685148df15e8b86aae68e534", "Crossdressing"),
            TagDef("685148d915e8b86aae68e519", "Delinquents"),
            TagDef("685146c5f3ed681c80f257e4", "Demons"),
            TagDef("685148d715e8b86aae68e505", "Genderswap"),
            TagDef("685148d615e8b86aae68e501", "Ghosts"),
            TagDef("685148d015e8b86aae68e4e8", "Gyaru"),
            TagDef("685146c5f3ed681c80f257e8", "Harem"),
            TagDef("68bfceaf4dbc442a26519889", "Hentai"),
            TagDef("685148f215e8b86aae68e584", "Incest"),
            TagDef("685148d715e8b86aae68e506", "Loli"),
            TagDef("685148d915e8b86aae68e518", "Mafia"),
            TagDef("685148d715e8b86aae68e509", "Magic"),
            TagDef("68f5f5ce5f29d3c1863dec3a", "Manhwa 18+"),
            TagDef("6851490615e8b86aae68e5c2", "Martial Arts"),
            TagDef("685148e215e8b86aae68e541", "Military"),
            TagDef("685148db15e8b86aae68e52c", "Monster Girls"),
            TagDef("685146c5f3ed681c80f257e2", "Monsters"),
            TagDef("685148d015e8b86aae68e4e4", "Music"),
            TagDef("685148d715e8b86aae68e508", "Ninja"),
            TagDef("685148d315e8b86aae68e4fd", "Office Workers"),
            TagDef("6851498815e8b86aae68e714", "Police"),
            TagDef("685148e215e8b86aae68e540", "Post-Apocalyptic"),
            TagDef("685146c5f3ed681c80f257e1", "Reincarnation"),
            TagDef("685148df15e8b86aae68e533", "Reverse Harem"),
            TagDef("6851490415e8b86aae68e5b9", "Samurai"),
            TagDef("685148d015e8b86aae68e4e7", "School Life"),
            TagDef("685148d115e8b86aae68e4ed", "Shota"),
            TagDef("685148db15e8b86aae68e528", "Supernatural"),
            TagDef("685148cf15e8b86aae68e4dc", "Survival"),
            TagDef("6851490c15e8b86aae68e5d1", "Time Travel"),
            TagDef("6851493515e8b86aae68e645", "Traditional Games"),
            TagDef("685148f915e8b86aae68e597", "Vampires"),
            TagDef("685148e115e8b86aae68e53c", "Video Games"),
            TagDef("6851492115e8b86aae68e602", "Villainess"),
            TagDef("68514a1115e8b86aae68e83e", "Virtual Reality"),
            TagDef("6851490c15e8b86aae68e5d3", "Zombies"),
        )
    }

    @MangaSourceParser("MANGABALL_AR", "Manga Ball (Arabic)", "ar")
    class Arabic(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_AR, setOf("ar"))

    @MangaSourceParser("MANGABALL_BG", "Manga Ball (Bulgarian)", "bg")
    class Bulgarian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_BG, setOf("bg"))

    @MangaSourceParser("MANGABALL_BN", "Manga Ball (Bengali)", "bn")
    class Bengali(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_BN, setOf("bn"))

    @MangaSourceParser("MANGABALL_CA", "Manga Ball (Catalan)", "ca")
    class Catalan(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_CA,
        setOf("ca", "ca-ad", "ca-es", "ca-fr", "ca-it", "ca-pt"),
    )

    @MangaSourceParser("MANGABALL_CS", "Manga Ball (Czech)", "cs")
    class Czech(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_CS, setOf("cs"))

    @MangaSourceParser("MANGABALL_DA", "Manga Ball (Danish)", "da")
    class Danish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_DA, setOf("da"))

    @MangaSourceParser("MANGABALL_DE", "Manga Ball (German)", "de")
    class German(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_DE, setOf("de"))

    @MangaSourceParser("MANGABALL_EL", "Manga Ball (Greek)", "el")
    class Greek(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_EL, setOf("el"))

    @MangaSourceParser("MANGABALL_EN", "Manga Ball (English)", "en")
    class English(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_EN, setOf("en"))

    @MangaSourceParser("MANGABALL_ES", "Manga Ball (Spanish)", "es")
    class Spanish(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_ES,
        setOf("es", "es-ar", "es-mx", "es-es", "es-la", "es-419"),
    )

    @MangaSourceParser("MANGABALL_FA", "Manga Ball (Persian)", "fa")
    class Persian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FA, setOf("fa"))

    @MangaSourceParser("MANGABALL_FI", "Manga Ball (Finnish)", "fi")
    class Finnish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FI, setOf("fi"))

    @MangaSourceParser("MANGABALL_FR", "Manga Ball (French)", "fr")
    class French(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_FR, setOf("fr"))

    @MangaSourceParser("MANGABALL_HE", "Manga Ball (Hebrew)", "he")
    class Hebrew(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HE, setOf("he"))

    @MangaSourceParser("MANGABALL_HI", "Manga Ball (Hindi)", "hi")
    class Hindi(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HI, setOf("hi"))

    @MangaSourceParser("MANGABALL_HU", "Manga Ball (Hungarian)", "hu")
    class Hungarian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_HU, setOf("hu"))

    @MangaSourceParser("MANGABALL_ID", "Manga Ball (Indonesian)", "id")
    class Indonesian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_ID, setOf("id"))

    @MangaSourceParser("MANGABALL_IT", "Manga Ball (Italian)", "it")
    class Italian(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_IT,
        setOf("it", "it-it"),
    )

    @MangaSourceParser("MANGABALL_IS", "Manga Ball (Icelandic)", "is")
    class Icelandic(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_IS,
        setOf("ib", "ib-is", "is"),
    )

    @MangaSourceParser("MANGABALL_JA", "Manga Ball (Japanese)", "ja")
    class Japanese(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_JA, setOf("jp"))

    @MangaSourceParser("MANGABALL_KO", "Manga Ball (Korean)", "ko")
    class Korean(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_KO, setOf("kr"))

    @MangaSourceParser("MANGABALL_KN", "Manga Ball (Kannada)", "kn")
    class Kannada(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_KN,
        setOf("kn", "kn-in", "kn-my", "kn-sg", "kn-tw"),
    )

    @MangaSourceParser("MANGABALL_ML", "Manga Ball (Malayalam)", "ml")
    class Malayalam(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_ML,
        setOf("ml", "ml-in", "ml-my", "ml-sg", "ml-tw"),
    )

    @MangaSourceParser("MANGABALL_MS", "Manga Ball (Malay)", "ms")
    class Malay(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_MS, setOf("ms"))

    @MangaSourceParser("MANGABALL_NE", "Manga Ball (Nepali)", "ne")
    class Nepali(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_NE, setOf("ne"))

    @MangaSourceParser("MANGABALL_NL", "Manga Ball (Dutch)", "nl")
    class Dutch(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_NL,
        setOf("nl", "nl-be"),
    )

    @MangaSourceParser("MANGABALL_NO", "Manga Ball (Norwegian)", "no")
    class Norwegian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_NO, setOf("no"))

    @MangaSourceParser("MANGABALL_PL", "Manga Ball (Polish)", "pl")
    class Polish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_PL, setOf("pl"))

    @MangaSourceParser("MANGABALL_PTBR", "Manga Ball (Portuguese)", "pt")
    class PortugueseBrazil(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_PTBR,
        setOf("pt-br", "pt-pt"),
    )

    @MangaSourceParser("MANGABALL_RO", "Manga Ball (Romanian)", "ro")
    class Romanian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_RO, setOf("ro"))

    @MangaSourceParser("MANGABALL_RU", "Manga Ball (Russian)", "ru")
    class Russian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_RU, setOf("ru"))

    @MangaSourceParser("MANGABALL_SK", "Manga Ball (Slovak)", "sk")
    class Slovak(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SK, setOf("sk"))

    @MangaSourceParser("MANGABALL_SL", "Manga Ball (Slovenian)", "sl")
    class Slovenian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SL, setOf("sl"))

    @MangaSourceParser("MANGABALL_SQ", "Manga Ball (Albanian)", "sq")
    class Albanian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SQ, setOf("sq"))

    @MangaSourceParser("MANGABALL_SR", "Manga Ball (Serbian)", "sr")
    class Serbian(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_SR,
        setOf("sr", "sr-cyrl"),
    )

    @MangaSourceParser("MANGABALL_SV", "Manga Ball (Swedish)", "sv")
    class Swedish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_SV, setOf("sv"))

    @MangaSourceParser("MANGABALL_TA", "Manga Ball (Tamil)", "ta")
    class Tamil(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_TA, setOf("ta"))

    @MangaSourceParser("MANGABALL_TH", "Manga Ball (Thai)", "th")
    class Thai(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_TH,
        setOf("th", "th-hk", "th-kh", "th-la", "th-my", "th-sg"),
    )

    @MangaSourceParser("MANGABALL_TR", "Manga Ball (Turkish)", "tr")
    class Turkish(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_TR, setOf("tr"))

    @MangaSourceParser("MANGABALL_UK", "Manga Ball (Ukrainian)", "uk")
    class Ukrainian(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_UK, setOf("uk"))

    @MangaSourceParser("MANGABALL_VI", "Manga Ball (Vietnamese)", "vi")
    class Vietnamese(context: MangaLoaderContext) : MangaBallParser(context, MangaParserSource.MANGABALL_VI, setOf("vi"))

    @MangaSourceParser("MANGABALL_ZH", "Manga Ball (Chinese)", "zh")
    class Chinese(context: MangaLoaderContext) : MangaBallParser(
        context,
        MangaParserSource.MANGABALL_ZH,
        setOf("zh", "zh-cn", "zh-hk", "zh-mo", "zh-sg", "zh-tw"),
    )
}
