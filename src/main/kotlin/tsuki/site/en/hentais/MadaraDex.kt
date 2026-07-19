package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.network.CommonHeaders
import tsuki.network.UserAgents
import tsuki.parsers.MadaraParser
import tsuki.exception.ParseException

import tsuki.model.ContentType
import tsuki.model.MangaChapter
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource

import tsuki.util.generateUid
import tsuki.util.getCookies
import tsuki.util.insertCookies
import tsuki.util.nullIfEmpty
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document

private const val F_URL = "fullUrl="

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
        .set(CommonHeaders.USER_AGENT, UserAgents.CHROME_DESKTOP)
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
                .fragment(_root_ide_package_.tsuki.site.en.hentais.F_URL + fullUrl)
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

        val fullUrl = url.fragment
            ?.substringAfter(_root_ide_package_.tsuki.site.en.hentais.F_URL, "")

        if (!fullUrl.isNullOrEmpty()) {

            copyCookies()

            val cleanUrl = url.newBuilder()
                .fragment(null)
                .build()

            val newRequest = request.newBuilder()
                .header(
                    CommonHeaders.REFERER,
                    fullUrl
                )
                .header(
                    CommonHeaders.USER_AGENT,
                    UserAgents.CHROME_DESKTOP
                )
                .url(cleanUrl)
                .build()

            return chain.proceed(newRequest)
        }

        return super.intercept(chain)
    }


    private fun copyCookies() {
        val cookies = context.cookieJar.getCookies(domain)

        cookies.forEach {
            context.cookieJar.insertCookies(
                "cdn.$domain",
                "${it.name}=${it.value}"
            )
        }
    }
}