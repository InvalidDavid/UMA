package tsuki

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaParserSource
import tsuki.model.MangaSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Suppress("OVERRIDE_DEPRECATION")
internal class LiveMangaLoaderContext : MangaLoaderContext() {

    private val storedCookies = CopyOnWriteArrayList<Cookie>()
    private val parsers = ConcurrentHashMap<MangaSource, MangaParser>()

    override val cookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                storedCookies.removeIf {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                if (cookie.expiresAt > System.currentTimeMillis()) {
                    storedCookies.add(cookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            storedCookies.removeIf { it.expiresAt <= System.currentTimeMillis() }
            return storedCookies.filter { it.matches(url) }
        }
    }

    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(::interceptSourceRequest)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun newParserInstance(source: MangaSource): MangaParser {
        require(source is MangaParserSource) { "Unsupported live source: $source" }
        return parsers.computeIfAbsent(source) { source.newParser(this) }
    }

    override fun getParserSources(): List<MangaSource> = MangaParserSource.entries

    override suspend fun evaluateJs(script: String): String? {
        error("JavaScript evaluation requires an Android consumer runtime")
    }

    override suspend fun evaluateJs(baseUrl: String, script: String): String? {
        if (script.startsWith("window.localStorage.getItem(")) {
            return null
        }
        error("JavaScript evaluation requires an Android consumer runtime: $baseUrl")
    }

    override fun getConfig(source: MangaSource): MangaSourceConfig = object : MangaSourceConfig {
        override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
    }

    override fun getDefaultUserAgent(): String =
        "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    override fun redrawImageResponse(
        response: Response,
        redraw: (image: Bitmap) -> Bitmap,
    ): Response = error("Image redraw requires an Android consumer runtime")

    override fun createBitmap(width: Int, height: Int): Bitmap = object : Bitmap {
        override val width: Int = width
        override val height: Int = height

        override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
            error("Bitmap drawing requires an Android consumer runtime")
        }
    }

    private fun interceptSourceRequest(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val source = request.tag(MangaSource::class.java)
            ?: return chain.proceed(request.withDefaultUserAgent())
        val parser = newParserInstance(source)
        val headers = request.headers.newBuilder()
        val parserHeaders = parser.getRequestHeaders()
        parserHeaders.names().forEach { name ->
            if (headers[name] == null) {
                parserHeaders.values(name).forEach { value -> headers.add(name, value) }
            }
        }
        if (headers["User-Agent"] == null) {
            headers["User-Agent"] = getDefaultUserAgent()
        }
        if (headers["Referer"] == null) {
            headers["Referer"] = "https://${parser.domain}/"
        }
        val taggedRequest = request.newBuilder().headers(headers.build()).build()
        return parser.intercept(SourceRequestChain(chain, taggedRequest))
    }

    private fun Request.withDefaultUserAgent(): Request {
        if (header("User-Agent") != null) {
            return this
        }
        return newBuilder().header("User-Agent", getDefaultUserAgent()).build()
    }

    private class SourceRequestChain(
        private val delegate: Interceptor.Chain,
        private val taggedRequest: Request,
    ) : Interceptor.Chain by delegate {
        override fun request(): Request = taggedRequest
    }
}
