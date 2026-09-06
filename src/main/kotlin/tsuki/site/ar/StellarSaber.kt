package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

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

import tsuki.util.attrAsRelativeUrl
import tsuki.util.await
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.toAbsoluteUrl

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.cipherSource
import org.jsoup.nodes.Document
import java.util.EnumSet
import java.util.Base64 as JavaBase64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@MangaSourceParser("STELLARSABER", "StellarSaber", "ar")
internal class StellarSaber(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.STELLARSABER, pageSize = 24), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("stellarsaber.pro")
    private val baseUrl = "https://$domain"

    private var nonce: String? = null

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = false,
        isMultipleTagsSupported = false,
    )

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .build()

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            return searchManga(page, query)
        }
        val url = when (order) {
            SortOrder.POPULARITY -> "$baseUrl/manga/${page.pageNumber()}?sort=rating"
            else -> "$baseUrl/manga/${page.pageNumber()}?sort=latest"
        }
        val doc = webClient.httpGet(url).parseHtml()
        fetchNonce(doc)
        return parseMangaList(doc)
    }

    private suspend fun searchManga(page: Int, query: String): List<Manga> {
        val nonce = fetchNonce()
        val body = MultipartBody.Builder().apply {
            setType(MultipartBody.FORM)
            addFormDataPart("action", "flavor_ajax_filter_content")
            addFormDataPart("nonce", nonce)
            addFormDataPart("page", page.toString())
            addFormDataPart("keyword", query)
        }.build()

        val request = Request.Builder()
            .url("$baseUrl/wp-admin/admin-ajax.php")
            .post(body)
            .headers(getRequestHeaders())
            .build()

        val response = context.httpClient.newCall(request).await()
        val json = response.parseJson()
        val results = json.optJSONObject("data")?.optJSONArray("results") ?: return emptyList()

        val list = mutableListOf<Manga>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val type = item.optString("type")
            if (type in listOf("novel", "anime")) continue
            list.add(
                Manga(
                    id = generateUid(item.getString("url")),
                    url = item.getString("url"),
                    publicUrl = item.getString("url").toAbsoluteUrl(domain),
                    title = item.getString("title"),
                    coverUrl = item.optString("cover").takeIf { it.isNotBlank() },
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            )
        }
        return list
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".card-grid .card").mapNotNull { element ->
            val a = element.select("a").firstOrNull() ?: return@mapNotNull null
            val title = element.selectFirst(".card__title")?.text() ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                coverUrl = element.selectFirst("img")?.attr("abs:src"),
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    private suspend fun fetchNonce(): String {
        nonce?.let { return it }
        val doc = webClient.httpGet("$baseUrl/manga/").parseHtml()
        return fetchNonce(doc)
    }

    private fun fetchNonce(doc: Document): String {
        nonce?.let { return it }
        val script = doc.selectFirst("#flavor-ajax-js-extra")?.data()
            ?: throw ParseException("Nonce script not found", "$baseUrl/manga/")
        val nonceValue = script.extract("nonce\":\"", '"')
        nonce = nonceValue
        return nonceValue
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val updated = parseMangaDetails(doc, manga)
        val chapters = parseChapterList(doc)
        return updated.copy(chapters = chapters)
    }

    private fun parseMangaDetails(doc: Document, original: Manga): Manga {
        val element = doc.selectFirst(".detail-content")
            ?: return original

        val title = element.selectFirst(".detail-info__title")?.text() ?: original.title
        val cover = element.selectFirst(".detail-poster img")?.attr("abs:src")
        val genres = element.select(".detail-genres a").map { it.text() }.toSet()
        val description = element.select("#detail-desc p").joinToString { it.wholeText().trim() }
        val otherName = element.select(".detail-info__alt-title").text()
        val authors = mutableSetOf<String>()
        val artists = mutableSetOf<String>()
        var state: MangaState? = null

        element.select(".detail-meta__label").forEach { labelEl ->
            val label = labelEl.text()
            val value = labelEl.nextElementSibling()?.text() ?: return@forEach
            when {
                label.contains("المؤلف") -> authors.add(value)
                label.contains("الرسّام") -> artists.add(value)
                label.contains("الحالة") -> state = when {
                    "مستمر" in value -> MangaState.ONGOING
                    "مكتمل" in value -> MangaState.FINISHED
                    "ملغا" in value -> MangaState.ABANDONED
                    "متوقف" in value -> MangaState.PAUSED
                    else -> null
                }
            }
        }

        return original.copy(
            title = title,
            coverUrl = cover ?: original.coverUrl,
            description = buildString {
                if (description.isNotBlank()) append(description)
                if (otherName.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(otherName)
                }
            }.trim().ifBlank { null },
            authors = authors + artists,
            tags = genres.map { MangaTag(it, it.lowercase(Locale.ROOT).replace(" ", "-"), source) }.toSet(),
            state = state,
        )
    }

    private fun parseChapterList(doc: Document): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        doc.select(".volume-group").forEach { volume ->
            val volumeName = volume.selectFirst(".volume-group__label")?.text()
            volume.select(".chapter-item").forEach { element ->
                val a = element.selectFirst("a") ?: return@forEach
                val href = a.attrAsRelativeUrl("href")
                val title = element.selectFirst(".chapter-item__title")?.text() ?: return@forEach
                val number = element.selectFirst(".chapter-item__number")?.text().orEmpty()
                val name = buildString {
                    if (!volumeName.isNullOrEmpty()) append("$volumeName - ")
                    if (number.isNotEmpty() && !title.contains(number)) append("$number: ")
                    append(title)
                }
                chapters.add(
                    MangaChapter(
                        id = generateUid(href),
                        title = name,
                        number = number.toFloatOrNull() ?: -1f,
                        volume = 0,
                        url = href,
                        uploadDate = parseRelativeDate(element.selectFirst(".chapter-item__date")?.text()),
                        scanlator = element.selectFirst(".chapter-item__team")?.text(),
                        branch = null,
                        source = source,
                    )
                )
            }
        }
        return chapters.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val key = fetchCdnKey(doc)
        return doc.select("img.reader__page[data-cdn-url]")
            .mapIndexed { index, img ->
                val url = img.attr("abs:data-cdn-url")
                MangaPage(
                    id = generateUid(url),
                    url = "$url#$key",
                    preview = null,
                    source = source,
                )
            }
    }

    private suspend fun fetchCdnKey(doc: Document): String {
        val script = doc.selectFirst("script:containsData(flavorReaderData)")?.data()
            ?: throw ParseException("Script not found", doc.location())
        val nonce = script.extract("cdnNonce: '", '\'')
        val chapterId = script.extract("chapterId: ", ',')

        val formBody = FormBody.Builder()
            .add("action", "flavor_cdn_get_key")
            .add("nonce", nonce)
            .add("chapter_id", chapterId)
            .build()

        val request = Request.Builder()
            .url("$baseUrl/wp-admin/admin-ajax.php")
            .post(formBody)
            .headers(getRequestHeaders())
            .build()

        val response = context.httpClient.newCall(request).await()
        val json = response.parseJson()
        return json.getJSONObject("data").getString("key")
    }

    private fun parseRelativeDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val trimmed = date.trim()
        val now = System.currentTimeMillis()
        val number = Regex("""(\d+)""").find(trimmed)?.value?.toIntOrNull()

        val duration: Duration? = if (number == null) {
            when {
                trimmed.contains("دقيقتين") -> 2.minutes
                trimmed.contains("دقيقة") -> 1.minutes
                trimmed.contains("ساعتين") -> 2.hours
                trimmed.contains("ساعة") -> 1.hours
                trimmed.contains("يومين") -> 2.days
                trimmed.contains("يوم") -> 1.days
                trimmed.contains("أسبوعين") -> 14.days
                trimmed.contains("أسبوع") -> 7.days
                trimmed.contains("شهرين") -> 60.days
                trimmed.contains("شهر") -> 30.days
                trimmed.contains("سنتين") -> 730.days
                trimmed.contains("سنة") -> 365.days
                else -> null
            }
        } else {
            when {
                trimmed.contains("دقيقة") || trimmed.contains("دقائق") -> number.minutes
                trimmed.contains("ساعة") || trimmed.contains("ساعات") -> number.hours
                trimmed.contains("يوم") || trimmed.contains("أيام") -> number.days
                trimmed.contains("أسبوع") || trimmed.contains("أسابيع") -> (number * 7).days
                trimmed.contains("شهر") || trimmed.contains("أشهر") -> (number * 30).days
                trimmed.contains("سنة") || trimmed.contains("سنوات") -> (number * 365).days
                else -> null
            }
        }
        return duration?.let { now - it.inWholeMilliseconds } ?: 0L
    }

    private fun String.extract(startKey: String, endChar: Char): String {
        val startIndex = indexOf(startKey)
        require(startIndex != -1) { "$startKey not found" }
        val valueStart = startIndex + startKey.length
        val valueEnd = indexOf(endChar, valueStart)
        require(valueEnd != -1) { "End char not found for $startKey" }
        return substring(valueStart, valueEnd)
    }

    private fun Int.pageNumber() = if (this > 1) "page/$this/" else ""

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val fragment = request.url.fragment ?: return response

        val keyBytes = JavaBase64.getDecoder().decode(fragment)
        val source = response.body?.source() ?: return response
        val iv = source.readByteArray(12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, iv),
            )
        }

        val cipherSource = source.cipherSource(cipher)
        return response.newBuilder()
            .body(cipherSource.buffer().asResponseBody("image/jpeg".toMediaType(), -1))
            .build()
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
