package org.koitharu.kotatsu.parsers.site.kotatsu.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.parsers.MadaraParser
import org.koitharu.kotatsu.parsers.util.*

private const val F_URL = "fullUrl="
private val UA = "Usagi/1 (Android)"

@MangaSourceParser("MADARADEX", "MadaraDex", "en", ContentType.HENTAI)
internal class MadaraDex(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MADARADEX, "madaradex.org") {

    init {
        context.cookieJar.insertCookies(domain, "wpmanga-adault=1")
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.remove(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders()
        .newBuilder()
        .set(CommonHeaders.USER_AGENT, UA)
        .set(CommonHeaders.REFERER, "https://$domain/")
        .build()

    override val authUrl: String
        get() = "https://$domain"

    override suspend fun isAuthorized(): Boolean {
        return context.cookieJar.getCookies(domain).any {
            it.name.contains("cm_uaid")
        }
    }

    override val listUrl = "title/"
    override val tagPrefix = "genre/"
    override val postReq = true
    override val stylePage = ""


    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {

        val fullUrl = chapter.url.toAbsoluteUrl(domain)

        val doc = webClient
            .httpGet(fullUrl)
            .parseHtml()


        val images = extractImages(doc)


        if (images.isEmpty()) {
            throw ParseException(
                "No images found, try to log in",
                fullUrl
            )
        }


        return images.map { image ->

            val imageUrl = image
                .toRelativeUrl(domain)
                .toHttpUrl()
                .newBuilder()
                .fragment(F_URL + fullUrl)
                .build()
                .toString()


            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }


    private fun extractImages(document: Document): List<String> {

        val pageBreakImages =
            document
                .select("div.page-break img")
                .mapNotNull {
                    it.attr("data-src")
                        .ifBlank {
                            it.attr("src")
                        }
                        .nullIfEmpty()
                }


        if (pageBreakImages.isNotEmpty()) {
            return pageBreakImages
        }


        return document
            .body()
            .select(selectBodyPage)
            .flatMap { body ->
                body.select(selectPage)
                    .flatMap { page ->
                        page.select("img")
                            .mapNotNull {
                                it.attr("src")
                                    .nullIfEmpty()
                            }
                    }
            }
    }


    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val fullUrl =
            url.fragment
                ?.substringAfter(F_URL, "")

        if (!fullUrl.isNullOrEmpty()) {
            copyCookies()
            val cleanUrl =
                url.newBuilder()
                    .fragment(null)
                    .build()
            val newRequest =
                request.newBuilder()
                    .header(
                        CommonHeaders.REFERER,
                        fullUrl
                    )
                    .header(
                        CommonHeaders.USER_AGENT,
                        UA
                    )
                    .url(cleanUrl)
                    .build()
            return chain.proceed(newRequest)
        }
        return super.intercept(chain)
    }


    private fun copyCookies() {
        context.cookieJar.copyCookies(
            domain,
            "cdn.$domain"
        )
    }
}
