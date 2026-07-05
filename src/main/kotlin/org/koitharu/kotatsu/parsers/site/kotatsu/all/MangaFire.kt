package org.koitharu.kotatsu.parsers.site.kotatsu.all

import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.getCookies
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.ownTextOrNull
import org.koitharu.kotatsu.parsers.util.parseFailed
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.EnumSet
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.min
import okhttp3.Protocol
import okhttp3.Response

private const val PIECE_SIZE = 200
private const val MIN_SPLIT_COUNT = 5

@Suppress("CustomX509TrustManager")
internal abstract class MangaFireParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    private val siteLang: String,
) : PagedMangaParser(context, source, 30), Interceptor, MangaParserAuthProvider {

    private val imageHttp11Client by lazy {
        context.httpClient.newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private val client: WebClient by lazy {
        val newHttpClient = context.httpClient.newBuilder()
            .sslSocketFactory(SSLUtils.sslSocketFactory!!, SSLUtils.trustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(
                    request.newBuilder()
                        .addHeader("Referer", "https://$domain/")
                        .build(),
                )

                if (request.url.fragment?.startsWith("scrambled") == true) {
                    return@addInterceptor context.redrawImageResponse(response) { bitmap ->
                        val offset = request.url.fragment!!.substringAfter("_").toInt()
                        val width = bitmap.width
                        val height = bitmap.height

                        val result = context.createBitmap(width, height)

                        val pieceWidth = min(PIECE_SIZE, width.ceilDiv(MIN_SPLIT_COUNT))
                        val pieceHeight = min(PIECE_SIZE, height.ceilDiv(MIN_SPLIT_COUNT))
                        val xMax = width.ceilDiv(pieceWidth) - 1
                        val yMax = height.ceilDiv(pieceHeight) - 1

                        for (y in 0..yMax) {
                            for (x in 0..xMax) {
                                val xDst = pieceWidth * x
                                val yDst = pieceHeight * y
                                val w = min(pieceWidth, width - xDst)
                                val h = min(pieceHeight, height - yDst)

                                val xSrc = pieceWidth * when (x) {
                                    xMax -> x // margin
                                    else -> (xMax - x + offset) % xMax
                                }
                                val ySrc = pieceHeight * when (y) {
                                    yMax -> y // margin
                                    else -> (yMax - y + offset) % yMax
                                }

                                val srcRect = Rect(xSrc, ySrc, xSrc + w, ySrc + h)
                                val dstRect = Rect(xDst, yDst, xDst + w, yDst + h)

                                result.drawBitmap(bitmap, srcRect, dstRect)
                            }
                        }

                        result
                    }
                }

                response
            }
            .build()
        OkHttpWebClient(newHttpClient, source)
    }

    override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("mangafire.to")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.RELEVANCE,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val authUrl: String
        get() = "https://${domain}"

    override suspend fun isAuthorized(): Boolean {
        return context.cookieJar.getCookies(domain).any {
            it.value.contains("user")
        }
    }

    override suspend fun getUsername(): String {
        val body = client.httpGet("https://${domain}/user/profile").parseHtml().body()
        return body.selectFirst("form.ajax input[name*=username]")?.attr("value")
            ?: body.parseFailed("Cannot find username")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val newRequest = request.newBuilder()
            .removeHeader("Referer")
            .addHeader("Referer", "https://$domain/")
            .build()

        val response = if (request.url.host.contains("mfcdn")) {
            imageHttp11Client.newCall(newRequest).execute()
        } else {
            chain.proceed(newRequest)
        }

        if (request.url.fragment?.startsWith("scrambled") == true) {
            return context.redrawImageResponse(response) { bitmap ->
                val offset = request.url.fragment!!.substringAfter("_").toInt()
                val width = bitmap.width
                val height = bitmap.height

                val result = context.createBitmap(width, height)

                val pieceWidth = min(PIECE_SIZE, width.ceilDiv(MIN_SPLIT_COUNT))
                val pieceHeight = min(PIECE_SIZE, height.ceilDiv(MIN_SPLIT_COUNT))
                val xMax = width.ceilDiv(pieceWidth) - 1
                val yMax = height.ceilDiv(pieceHeight) - 1

                for (y in 0..yMax) {
                    for (x in 0..xMax) {
                        val xDst = pieceWidth * x
                        val yDst = pieceHeight * y
                        val w = min(pieceWidth, width - xDst)
                        val h = min(pieceHeight, height - yDst)

                        val xSrc = pieceWidth * when (x) {
                            xMax -> x
                            else -> (xMax - x + offset) % xMax
                        }
                        val ySrc = pieceHeight * when (y) {
                            yMax -> y
                            else -> (yMax - y + offset) % yMax
                        }

                        val srcRect = Rect(xSrc, ySrc, xSrc + w, ySrc + h)
                        val dstRect = Rect(xDst, yDst, xDst + w, yDst + h)

                        result.drawBitmap(bitmap, srcRect, dstRect)
                    }
                }

                result
            }
        }

        return response
    }

    private val tags = suspendLazy(soft = true) {
        client.httpGet("https://$domain/filter").parseHtml()
            .select(".genres > li").map {
                MangaTag(
                    title = it.selectFirstOrThrow("label").ownText().toTitleCase(sourceLocale),
                    key = it.selectFirstOrThrow("input").attr("value"),
                    source = source,
                )
            }.associateBy { it.title }
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = tags.get().values.toSet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.ABANDONED,
            MangaState.PAUSED,
            MangaState.UPCOMING,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        val url = buildString {
            append("/filter")
            append("?page=")
            append(page)
            append("&language[]=")
            append(siteLang)

            when {
                !query.isNullOrEmpty() -> {
                    append("&keyword=")
                    append(encodeKeyword(query))
                    append("&vrf=")
                    append(VrfGenerator.generate(query))
                    append("&sort=")
                    append(
                        when (order) {
                            SortOrder.UPDATED -> "recently_updated"
                            SortOrder.POPULARITY -> "most_viewed"
                            SortOrder.RATING -> "scores"
                            SortOrder.NEWEST -> "release_date"
                            SortOrder.ALPHABETICAL -> "title_az"
                            SortOrder.RELEVANCE -> "most_relevance"
                            else -> ""
                        },
                    )
                }

                else -> {
                    filter.tagsExclude.forEach { tag ->
                        append("&genre[]=-")
                        append(tag.key)
                    }
                    filter.tags.forEach { tag ->
                        append("&genre[]=")
                        append(tag.key)
                    }
                    filter.locale?.let {
                        append("&language[]=")
                        append(it.language)
                    }
                    filter.states.forEach { state ->
                        append("&status[]=")
                        append(
                            when (state) {
                                MangaState.ONGOING -> "releasing"
                                MangaState.FINISHED -> "completed"
                                MangaState.ABANDONED -> "discontinued"
                                MangaState.PAUSED -> "on_hiatus"
                                MangaState.UPCOMING -> "info"
                                else -> throw IllegalArgumentException("$state not supported")
                            },
                        )
                    }
                    append("&sort=")
                    append(
                        when (order) {
                            SortOrder.UPDATED -> "recently_updated"
                            SortOrder.POPULARITY -> "most_viewed"
                            SortOrder.RATING -> "scores"
                            SortOrder.NEWEST -> "release_date"
                            SortOrder.ALPHABETICAL -> "title_az"
                            SortOrder.RELEVANCE -> "most_relevance"
                            else -> ""
                        },
                    )
                }
            }
        }

        return client.httpGet(url.toAbsoluteUrl(domain)).parseHtml().parseMangaList()
    }

    private fun Document.parseMangaList(): List<Manga> {
        return select(".manga-card").mapNotNull { el ->
            val href = el.attr("href")
            if (href.isNullOrBlank()) return@mapNotNull null
            val mangaUrl = href
            val img = el.selectFirst("img") ?: return@mapNotNull null
            Manga(
                id = generateUid(mangaUrl),
                url = mangaUrl,
                publicUrl = mangaUrl.toAbsoluteUrl(domain),
                title = el.selectFirst(".manga-card__title")?.ownText()
                    ?: img.attr("alt"),
                coverUrl = img.attrAsAbsoluteUrl("src"),
                source = source,
                altTitles = emptySet(),
                largeCoverUrl = null,
                authors = emptySet(),
                contentRating = null,
                rating = RATING_UNKNOWN,
                state = null,
                tags = emptySet(),
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val document = client.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val cover = document.selectFirst(".title-detail__poster img")
            ?.attrAsAbsoluteUrl("src") ?: manga.coverUrl
        val availableTags = tags.get()
        var isAdult = false
        var isSuggestive = false
        val author = document.select("div.meta a[href*=/author/]")
            .joinToString { it.ownText() }.nullIfEmpty()

        return manga.copy(
            title = document.selectFirst(".title-detail__title")?.ownText() ?: manga.title,
            altTitles = setOfNotNull(
                document.selectFirst(".title-detail__alt-text")?.ownTextOrNull()
            ),
            rating = document.selectFirst("div.rating-box")?.attr("data-score")
                ?.toFloatOrNull()?.div(10) ?: RATING_UNKNOWN,
            coverUrl = cover,
            tags = document.select(".title-detail__tag").mapNotNullToSet { tagEl ->
                val tag = tagEl.ownText()
                if (tag == "Hentai") isAdult = true
                else if (tag == "Ecchi") isSuggestive = true
                availableTags[tag.toTitleCase(sourceLocale)]
            },
            contentRating = when {
                isAdult -> ContentRating.ADULT
                isSuggestive -> ContentRating.SUGGESTIVE
                else -> ContentRating.SAFE
            },
            state = document.selectFirst(".title-detail__meta .badge--status")?.ownText()?.let {
                when (it.lowercase()) {
                    "releasing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    "discontinued" -> MangaState.ABANDONED
                    "on_hiatus" -> MangaState.PAUSED
                    "info" -> MangaState.UPCOMING
                    else -> null
                }
            },
            authors = setOfNotNull(author),
            description = document.selectFirst(".title-detail__synopsis")?.html() ?: "",
            chapters = getChapters(manga.url, document),
        )
    }

    private data class ChapterBranch(
        val type: String,
        val langCode: String,
        val langTitle: String,
    )

    private suspend fun getChapters(mangaUrl: String, document: Document): List<MangaChapter> {
        val availableTypes = document.select(".chapvol-tab a[data-name]")
            .map { it.attr("data-name") }
            .toSet()

        val branches = document.select(".m-list .tab-content").flatMap { tab ->
            val type = tab.attr("data-name")
            tab.select(".list-menu .dropdown-item").map { item ->
                ChapterBranch(
                    type = type,
                    langCode = item.attr("data-code").lowercase(),
                    langTitle = item.attr("data-title"),
                )
            }
        }.filter {
            it.langCode == siteLang && availableTypes.contains(it.type)
        }

        val mangaId = mangaUrl.trimEnd('/').substringAfterLast('/')

        return branches.flatMap {
            getChaptersBranch(mangaId, it)
        }
    }

    private suspend fun getChaptersBranch(mangaId: String, branch: ChapterBranch): List<MangaChapter> {
        val readVrf = VrfGenerator.generate("$mangaId@${branch.type}@${branch.langCode}")

        val response = client
            .httpGet("https://$domain/ajax/read/$mangaId/${branch.type}/${branch.langCode}?vrf=$readVrf")

        val chapterElements = response.parseJson()
            .getJSONObject("result")
            .getString("html")
            .let(Jsoup::parseBodyFragment)
            .select(".title-detail__row-link")

        if (branch.type == "chapter") {
            val doc = client
                .httpGet("https://$domain/ajax/manga/$mangaId/${branch.type}/${branch.langCode}")
                .parseJson()
                .getString("result")
                .let(Jsoup::parseBodyFragment)

            doc.select(".title-detail__row-link").withIndex().forEach { (i, it) ->
                val date = it.selectFirst(".title-detail__row-date")?.ownText() ?: ""
                chapterElements[i].attr("upload-date", date)
                chapterElements[i].attr("other-title", it.attr("title"))
            }
        }

        return chapterElements.mapChapters(reversed = true) { _, it ->
            val chapterId = it.attr("data-id")
            MangaChapter(
                id = generateUid(it.attr("href")),
                title = it.attr("title").ifBlank {
                    "${branch.type.toTitleCase()} ${it.attr("data-number")}"
                },
                number = it.attr("data-number").toFloatOrNull() ?: -1f,
                volume = it.attr("other-title").let { title ->
                    volumeNumRegex.find(title)?.groupValues?.getOrNull(2)?.toInt() ?: 0
                },
                url = "$mangaId/${branch.type}/${branch.langCode}/$chapterId",
                scanlator = null,
                uploadDate = dateFormat.parseSafe(it.attr("upload-date")),
                branch = "${branch.langTitle} ${branch.type.toTitleCase()}",
                source = source,
            )
        }
    }

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
    private val volumeNumRegex = Regex("""vol(ume)?\s*(\d+)""", RegexOption.IGNORE_CASE)

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val document = client.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml()
        return document.select(".manga-card").mapNotNull { el ->
            val url = el.attrAsRelativeUrl("href")
            val title = el.selectFirst(".manga-card__title")?.ownText() ?: return@mapNotNull null
            val cover = el.selectFirst("img")?.attrAsAbsoluteUrl("src") ?: return@mapNotNull null
            Manga(
                id = generateUid(url),
                url = url,
                publicUrl = url.toAbsoluteUrl(domain),
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
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split('/')
        if (parts.size < 4) error("Invalid chapter url: ${chapter.url}")

        val mangaId = parts[0]
        val type = parts[1]
        val lang = parts[2]
        val chapterId = parts[3]

        val vrf = VrfGenerator.generate("$type@$chapterId")

        val images = client.httpGet(
            "https://$domain/ajax/read/$type/$chapterId?vrf=$vrf"
        ).parseJson()
            .getJSONObject("result")
            .getJSONArray("images")

        return List(images.length()) { i ->
            val img = images.getJSONArray(i)
            val url = img.getString(0)
            val offset = img.getInt(2)

            MangaPage(
                id = generateUid(url),
                url = if (offset < 1) url else "$url#scrambled_$offset",
                preview = null,
                source = source,
            )
        }
    }

    private fun Int.ceilDiv(other: Int) = (this + (other - 1)) / other

    private fun encodeKeyword(input: String): String {
        val sb = StringBuilder()
        for (c in input) {
            when {
                c == ' ' -> sb.append('+')
                c.isLetterOrDigit() || c.code > 0x7F -> sb.append(c)
                else -> sb.append(String.format("%%%02X", c.code))
            }
        }
        return sb.toString()
    }

    @MangaSourceParser("MANGAFIRE_EN", "MangaFire (English)", "en")
    class English(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_EN, "en")

    @MangaSourceParser("MANGAFIRE_ES", "MangaFire (Spanish)", "es")
    class Spanish(context: MangaLoaderContext) : MangaFireParser(context, MangaParserSource.MANGAFIRE_ES, "es")

    @MangaSourceParser("MANGAFIRE_ESLA", "MangaFire Spanish (Latim)", "es")
    class SpanishLatim(context: MangaLoaderContext) :
        MangaFireParser(context, MangaParserSource.MANGAFIRE_ESLA, "es-la")

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

public object SSLUtils {
    public val trustAllCerts: Array<TrustManager> = arrayOf(@Suppress("CustomX509TrustManager")
    object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
    })

    public val sslSocketFactory: SSLSocketFactory? = SSLContext.getInstance("SSL").apply {
        init(null, trustAllCerts, SecureRandom())
    }.socketFactory

    public val trustManager: X509TrustManager = trustAllCerts[0] as X509TrustManager
}

public object VrfGenerator {
    // New keys (from Python crack)
    private val rc4Keys = mapOf(
        "r1" to "FgxyJUQDPUGSzwbAq/ToWn4/e8jYzvabE+dLMb1XU1o=",
        "L1" to "CQx3CLwswJAnM1VxOqX+y+f3eUns03ulxv8Z+0gUyik=",
        "M"  to "fAS+otFLkKsKAJzu3yU+rGOlbbFVq+u+LaS6+s1eCJs=",
        "t1" to "Oy45fQVK9kq9019+VysXVlz1F9S1YwYKgXyzGlZrijo=",
        "n1" to "aoDIdXezm2l3HrcnQdkPJTDT8+W6mcl2/02ewBHfPzg="
    )

    private val salts = mapOf(
        "q" to "l9PavRg=",
        "I" to "Ml2v7ag1Jg==",
        "V" to "i/Va0UxrbMo=",
        "N" to "WFjKAHGEkQM=",
        "T" to "5Rr27rWd"
    )

    private val seeds32 = mapOf(
        "q" to "yH6MXnMEcDVWO/9a6P9W92BAh1eRLVFxFlWTHUqQ474=",
        "I" to "RK7y4dZ0azs9Uqz+bbFB46Bx2K9EHg74ndxknY9uknA=",
        "V" to "rqr9HeTQOg8TlFiIGZpJaxcvAaKHwMwrkqojJCpcvoc=",
        "N" to "/4GPpmZXYpn5RpkP7FC/dt8SXz7W30nUZTe8wb+3xmU=",
        "T" to "wsSGSBXKWA9q1oDJpjtJddVxH+evCfL5SO9HZnUDFU8="
    )

    // Lookup tables (10×256 bytes) extracted from Python OPT, stored as Base64 for compactness
    private val optTablesB64 = mapOf(
        "q" to "ISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0BBQkNERUZHSElKS0xNTk9QUVJTVFVWV1hZWltcXV5fYGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6e3x9fn+AgYKDhIWGh4iJiouMjY6PkJGSk5SVlpeYmZqbnJ2en6ChoqOkpaanqKmqq6ytrq+wsbKztLW2t7i5uru8vb6/wMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t/g4eLj5OXm5+jp6uvs7e7v8PHy8/T19vf4+fr7/P3+/wABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fIAAQIDBAUGBwgJCgsMDQ4PABESExQVFhcYGRobHB0eHxAhIiMkJSYnKCkqKywtLi8gMTIzNDU2Nzg5Ojs8PT4/MEFCQ0RFRkdISUpLTE1OT0BRUlNUVVZXWFlaW1xdXl9QYWJjZGVmZ2hpamtsbW5vYHFyc3R1dnd4eXp7fH1+f3CBgoOEhYaHiImKi4yNjo+AkZKTlJWWl5iZmpucnZ6fkKGio6SlpqeoqaqrrK2ur6CxsrO0tba3uLm6u7y9vr+wwcLDxMXGx8jJysvMzc7PwNHS09TV1tfY2drb3N3e39Dh4uPk5ebn6Onq6+zt7u/g8fLz9PX29/j5+vv8/f7/8AECAwQFBgcICQoLDA0ODwAREhMUFRYXGBkaGxwdHh8QISIjJCUmJygpKissLS4vIDEyMzQ1Njc4OTo7PD0+PzBBQkNERUZHSElKS0xNTk9AUVJTVFVWV1hZWltcXV5fUGFiY2RlZmdoaWprbG1ub2BxcnN0dXZ3eHl6e3x9fn9wgYKDhIWGh4iJiouMjY6PgJGSk5SVlpeYmZqbnJ2en5ChoqOkpaanqKmqq6ytrq+gsbKztLW2t7i5uru8vb6/sMHCw8TFxsfIycrLzM3Oz8DR0tPU1dbX2Nna29zd3t/Q4eLj5OXm5+jp6uvs7e7v4PHy8/T19vf4+fr7/P3+//6uvs7e7v8PHy8/T19vf4+fr7/P3+/wABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj9AQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5/gIGCg4SFhoeIiYqLjI2Oj5CRkpOUlZaXmJmam5ydnp+goaKjpKWmp6ipqqusra6vsLGys7S1tre4ubq7vL2+v8DBwsPExcbHyMnKy8zNzs/Q0dLT1NXW19jZ2tvc3d7f4OHi4+Tl5ufo6QACBAYICgwOEBIUFhgaHB4gIiQmKCosLjAyNDY4Ojw+QEJERkhKTE5QUlRWWFpcXmBiZGZoamxucHJ0dnh6fH6AgoSGiIqMjpCSlJaYmpyeoKKkpqiqrK6wsrS2uLq8vsDCxMbIyszO0NLU1tja3N7g4uTm6Ors7vDy9Pb4+vz+AQMFBwkLDQ8RExUXGRsdHyEjJScpKy0vMTM1Nzk7PT9BQ0VHSUtNT1FTVVdZW11fYWNlZ2lrbW9xc3V3eXt9f4GDhYeJi42PkZOVl5mbnZ+ho6Wnqautr7Gztbe5u72/wcPFx8nLzc/R09XX2dvd3+Hj5efp6+3v8fP19/n7/f8AQIDAAUGBwQJCgsIDQ4PDBESExAVFhcUGRobGB0eHxwhIiMgJSYnJCkqKygtLi8sMTIzMDU2NzQ5Ojs4PT4/PEFCQ0BFRkdESUpLSE1OT0xRUlNQVVZXVFlaW1hdXl9cYWJjYGVmZ2RpamtobW5vbHFyc3B1dnd0eXp7eH1+f3yBgoOAhYaHhImKi4iNjo+MkZKTkJWWl5SZmpuYnZ6fnKGio6ClpqekqaqrqK2ur6yxsrOwtba3tLm6u7i9vr+8wcLDwMXGx8TJysvIzc7PzNHS09DV1tfU2drb2N3e39zh4uPg5ebn5Onq6+jt7u/s8fLz8PX29/T5+vv4/f7//AAIEBggKDA4QEhQWGBocHiAiJCYoKiwuMDI0Njg6PD5AQkRGSEpMTlBSVFZYWlxeYGJkZmhqbG5wcnR2eHp8foCChIaIioyOkJKUlpianJ6goqSmqKqsrrCytLa4ury+wMLExsjKzM7Q0tTW2Nrc3uDi5Obo6uzu8PL09vj6/P4BAwUHCQsNDxETFRcZGx0fISMlJykrLS8xMzU3OTs9P0FDRUdJS01PUVNVV1lbXV9hY2VnaWttb3FzdXd5e31/gYOFh4mLjY+Rk5WXmZudn6Gjpaepq62vsbO1t7m7vb/Bw8XHycvNz9HT1dfZ293f4ePl5+nr7e/x8/X3+fv9/yEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj9AQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5/gIGCg4SFhoeIiYqLjI2Oj5CRkpOUlZaXmJmam5ydnp+goaKjpKWmp6ipqqusra6vsLGys7S1tre4ubq7vL2+v8DBwsPExcbHyMnKy8zNzs/Q0dLT1NXW19jZ2tvc3d7f4OHi4+Tl5ufo6err7O3u7/Dx8vP09fb3+Pn6+/z9/v8AAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAAAgQGCAoMDhASFBYYGhweICIkJigqLC4wMjQ2ODo8PkBCREZISkxOUFJUVlhaXF5gYmRmaGpsbnBydHZ4enx+gIKEhoiKjI6QkpSWmJqcnqCipKaoqqyusLK0tri6vL7AwsTGyMrMztDS1NbY2tze4OLk5ujq7O7w8vT2+Pr8/gEDBQcJCw0PERMVFxkbHR8hIyUnKSstLzEzNTc5Oz0/QUNFR0lLTU9RU1VXWVtdX2FjZWdpa21vcXN1d3l7fX+Bg4WHiYuNj5GTlZeZm52foaOlp6mrra+xs7W3ubu9v8HDxcfJy83P0dPV19nb3d/h4+Xn6evt7/Hz9ff5+/3/AAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/AEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl9gYWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1+f0CBgoOEhYaHiImKi4yNjo+QkZKTlJWWl5iZmpucnZ6foKGio6SlpqeoqaqrrK2ur7CxsrO0tba3uLm6u7y9vr+AwcLDxMXGx8jJysvMzc7P0NHS09TV1tfY2drb3N3e3+Dh4uPk5ebn6Onq6+zt7u/w8fLz9PX29/j5+vv8/f7/w==",
        "I" to "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",
        "V" to "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",
        "N" to "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",
        "T" to "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"
    )

    private val saltLengths = mapOf("q" to 5, "I" to 7, "V" to 8, "N" to 8, "T" to 6)

    // Pipeline order: (rc4Key, roundId)
    private val pipeline = listOf(
        "r1" to "q",
        "L1" to "I",
        "M"  to "V",
        "t1" to "N",
        "n1" to "T"
    )

    private fun unpackOptTable(b64: String): Array<ByteArray> {
        val raw = Base64.getDecoder().decode(b64)
        require(raw.size == 2560) { "Invalid opt table size: ${raw.size}" }
        return Array(10) { i -> raw.copyOfRange(i * 256, (i + 1) * 256) }
    }

    private val optTables: Map<String, Array<ByteArray>> by lazy {
        optTablesB64.mapValues { (_, b64) -> unpackOptTable(b64) }
    }

    private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val temp = s[i]; s[i] = s[j]; s[j] = temp
        }
        var i = 0; j = 0
        val out = ByteArray(data.size)
        for (k in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val temp = s[i]; s[i] = s[j]; s[j] = temp
            val t = (s[i] + s[j]) and 0xFF
            out[k] = (data[k].toInt() xor s[t]).toByte()
        }
        return out
    }

    private fun diffuse(data: ByteArray, roundId: String): ByteArray {
        val salt = Base64.getDecoder().decode(salts[roundId]!!)
        val seed = Base64.getDecoder().decode(seeds32[roundId]!!)
        val opt = optTables[roundId]!!
        val sl = saltLengths[roundId]!!
        val keyLen = seed.size

        val out = ByteArray(data.size + sl)
        for (i in data.indices) {
            if (i < sl) out[i] = salt[i]
            val idx = i % 10
            val xored = (data[i].toInt() and 0xFF) xor (seed[i % keyLen].toInt() and 0xFF)
            out[i + sl] = opt[idx][xored]
        }
        return out
    }

    public fun generate(input: String): String {
        var encoded = URLEncoder.encode(input, "UTF-8")
            .replace("+", "%20")
        // encodeURIComponent also encodes ! ' ( ) *
        encoded = encoded
            .replace("!", "%21")
            .replace("'", "%27")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace("*", "%2A")
        var bytes = encoded.toByteArray(Charsets.UTF_8)

        for ((rc4Key, roundId) in pipeline) {
            bytes = rc4(Base64.getDecoder().decode(rc4Keys[rc4Key]!!), bytes)
            bytes = diffuse(bytes, roundId)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
