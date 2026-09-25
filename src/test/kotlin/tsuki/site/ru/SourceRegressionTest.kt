package tsuki.site.ru

import kotlinx.coroutines.runBlocking
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tsuki.MangaLoaderContext
import tsuki.MangaParser
import tsuki.newParser
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaParserSource
import tsuki.model.MangaSource
import tsuki.model.SortOrder

internal class SourceRegressionTest {

    @Test
    fun `AComics reads the current issue count and image`() = runBlocking {
        val context = FixtureContext { request ->
            assertEquals("https://acomics.ru/~my-mom-is-strange/1", request.url.toString())
            """<html><nav class="reader-navigator" data-issue-count="97"></nav>
                <h1 class="reader-issue-title"><span class="number-without-name">1/97</span></h1>
                <section class="reader-issue"><img class="issue" src="/upload/test.png"></section></html>"""
        }
        val parser = AComics(context)
        val pages = parser.getPages(chapter("https://acomics.ru/~my-mom-is-strange/", MangaParserSource.ACOMICS))

        assertEquals(97, pages.size)
        assertEquals("https://acomics.ru/~my-mom-is-strange/97", pages.last().url)
        assertEquals("https://acomics.ru/upload/test.png", parser.getPageUrl(pages.first()))
    }

    @Test
    fun `MangaLib catalog uses its working API host`() = runBlocking {
        val context = FixtureContext { request ->
            assertEquals("api2.mangalib.me", request.url.host)
            assertEquals("/api/manga", request.url.encodedPath)
            """{"data":[{"id":1,"rus_name":"Test manga","name":"Original title",
                "slug_url":"test-manga","cover":{"thumbnail":"https://example.org/thumb.jpg",
                "default":"https://example.org/cover.jpg"},"status":{"id":1}}]}"""
        }

        val manga = MangaLib(context).getList(0, SortOrder.UPDATED, MangaListFilter.EMPTY)

        assertEquals(1, manga.size)
        assertEquals("Test manga", manga.single().title)
    }

    @Test
    fun `MangaLib reader resolves pages through the same API host`() = runBlocking {
        val context = FixtureContext { request ->
            assertEquals("api2.mangalib.me", request.url.host)
            when (request.url.encodedPath) {
                "/api/manga/test-manga/chapter" ->
                    """{"data":{"pages":[{"id":42,"url":"//manga/test-manga/chapter/page.png"}]}}"""

                "/api/constants" ->
                    """{"data":{"imageServers":[{"id":"main","url":"https://img2.imglib.info",
                        "site_ids":[1]},{"id":"compress","url":"https://img3.cdnlibs.org",
                        "site_ids":[1]}]}}"""

                else -> error("Unexpected MangaLib request: ${request.url}")
            }
        }

        val pages = MangaLib(context).getPages(
            chapter("test-manga/chapter?number=1&volume=1", MangaParserSource.MANGALIB),
        )

        assertEquals(1, pages.size)
        assertEquals("https://img2.imglib.info/manga/test-manga/chapter/page.png", pages.single().url)
    }

    @Test
    fun `InkStory requests a concrete image format for reader pages`() = runBlocking {
        val context = FixtureContext { request ->
            assertEquals("https://api.inkstory.net/v2/chapters/chapter-1", request.url.toString())
            """{"pages":[{"id":"page-1","image":"https://gstatic.inuko.me/book/example/page.jpeg?format=webp"},
                {"id":"page-2","image":"https://gstatic.inuko.me/book/example/next.jpeg?width=320"}]}"""
        }

        val pages = MangaWtf(context).getPages(chapter("chapter-1", MangaParserSource.MANGAWTF))

        assertEquals("https://gstatic.inuko.me/book/example/page.jpeg?format=webp&width=1600", pages[0].url)
        assertEquals("https://gstatic.inuko.me/book/example/next.jpeg?format=webp&width=1600", pages[1].url)
    }

    private fun chapter(url: String, source: MangaParserSource) = MangaChapter(
        id = 1L,
        title = null,
        number = 1f,
        volume = 0,
        url = url,
        scanlator = null,
        uploadDate = 0L,
        branch = null,
        source = source,
    )
}

@Suppress("OVERRIDE_DEPRECATION")
private class FixtureContext(private val bodyFor: (Request) -> String) : MangaLoaderContext() {
    override val httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bodyFor(request).toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }).build()

    override val cookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }

    override fun newParserInstance(source: MangaSource): MangaParser =
        (source as MangaParserSource).newParser(this)

    override fun getParserSources(): List<MangaSource> = emptyList()
    override suspend fun evaluateJs(script: String): String? = error("JavaScript is not used by these fixtures")
    override suspend fun evaluateJs(baseUrl: String, script: String): String? = error("JavaScript is not used by these fixtures")
    override fun getConfig(source: MangaSource): MangaSourceConfig = object : MangaSourceConfig {
        override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
    }

    override fun getDefaultUserAgent(): String = "UMA source regression test"
    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
        error("Image redraw is not used by these fixtures")

    override fun createBitmap(width: Int, height: Int): Bitmap = object : Bitmap {
        override val width = width
        override val height = height
        override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) =
            error("Bitmap drawing is not used by these fixtures")
    }
}
