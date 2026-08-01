package tsuki

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaParserSource
import tsuki.model.MangaSource
import java.util.concurrent.ConcurrentHashMap

@Suppress("OVERRIDE_DEPRECATION")
internal class FixtureMangaLoaderContext private constructor(
    routes: Map<FixtureRequest, String>,
) : MangaLoaderContext() {

    private val fixtureInterceptor = FixtureHttpInterceptor(routes)

    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(fixtureInterceptor)
        .build()

    override val cookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }

    override fun newParserInstance(source: MangaSource): MangaParser {
        require(source is MangaParserSource) { "Unsupported fixture source: $source" }
        return source.newParser(this)
    }

    override fun getParserSources(): List<MangaSource> = MangaParserSource.entries

    override suspend fun evaluateJs(script: String): String? {
        error("JavaScript evaluation is not registered for fixture tests")
    }

    override suspend fun evaluateJs(baseUrl: String, script: String): String? {
        error("JavaScript evaluation is not registered for fixture tests: $baseUrl")
    }

    override fun getConfig(source: MangaSource): MangaSourceConfig = object : MangaSourceConfig {
        override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
    }

    override fun getDefaultUserAgent(): String = "MangaRepoContractTest/1.0"

    override fun redrawImageResponse(
        response: Response,
        redraw: (image: Bitmap) -> Bitmap,
    ): Response = error("Image redraw is not registered for fixture tests")

    override fun createBitmap(width: Int, height: Int): Bitmap = object : Bitmap {
        override val width: Int = width
        override val height: Int = height

        override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
            error("Bitmap drawing is not registered for fixture tests")
        }
    }

    fun verifyAllFixturesUsed() {
        fixtureInterceptor.verifyAllFixturesUsed()
    }

    internal class Builder {
        private val routes = linkedMapOf<FixtureRequest, String>()

        fun get(url: String, resourcePath: String): Builder = route("GET", url, resourcePath)

        fun post(url: String, resourcePath: String): Builder = route("POST", url, resourcePath)

        private fun route(method: String, url: String, resourcePath: String): Builder = apply {
            val key = FixtureRequest(method, url)
            require(routes.put(key, resourcePath) == null) { "Duplicate fixture route: $method $url" }
        }

        fun build(): FixtureMangaLoaderContext = FixtureMangaLoaderContext(routes.toMap())
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}

internal data class FixtureRequest(
    val method: String,
    val url: String,
)

private class FixtureHttpInterceptor(
    private val routes: Map<FixtureRequest, String>,
) : Interceptor {

    private val usedRoutes = ConcurrentHashMap.newKeySet<FixtureRequest>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = FixtureRequest(request.method, request.url.toString())
        val resourcePath = routes[key]
            ?: error("No fixture registered for ${request.method} ${request.url}")
        val body = loadFixture(resourcePath)
        usedRoutes.add(key)
        return response(request, body)
    }

    fun verifyAllFixturesUsed() {
        val unusedRoutes = routes.keys - usedRoutes
        check(unusedRoutes.isEmpty()) {
            "Unused fixture routes: ${unusedRoutes.joinToString { "${it.method} ${it.url}" }}"
        }
    }

    private fun loadFixture(resourcePath: String): ByteArray {
        val stream = FixtureMangaLoaderContext::class.java.getResourceAsStream(resourcePath)
            ?: error("Fixture resource not found: $resourcePath")
        return stream.use { it.readBytes() }
    }

    private fun response(request: Request, body: ByteArray): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
        .build()
}
