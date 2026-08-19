package tsuki

import kotlinx.coroutines.test.runTest
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
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaParserSource
import tsuki.model.MangaListFilter
import tsuki.model.MangaSource
import tsuki.model.MangaTag
import tsuki.model.SortOrder
import tsuki.site.ru.libsocial.parseLibSocialSummary
import tsuki.site.ru.parseWaMangaAlternativeTitles
import tsuki.site.ru.parseWaMangaGenres
import tsuki.site.ru.parseWaMangaSearchText
import java.util.concurrent.ConcurrentHashMap

class RussianSourcesContractTest {

    @Test
    fun `Russian catalog and representative reading contracts remain valid`() = runTest {
        verifyCatalog()
        verifyLibSocialSummary()
        verifyAComics()
        verifyDesu()
        verifyMangaOneLove()
        verifyMangaMammy()
        verifyMangaWtfFilters()
        verifyReadManga()
        verifyWaManga()
    }

    private fun verifyCatalog() {
        val expectedSources = setOf(
            "ACOMICS",
            "ALLHENTAI",
            "ASTRAMANGA",
            "BEST_MANGA",
            "COMX",
            "DESUME",
            "HENCHAN",
            "HENTAILIB",
            "MANGABALL_RU",
            "MANGACHAN",
            "MANGALIB",
            "MANGAMAMMY",
            "MANGAONELOVE",
            "MANGAPLUSPARSER_RU",
            "MANGAZAVR",
            "MANGA_WTF",
            "MINTMANGA",
            "NINEMANGA_RU",
            "NUDEMOON",
            "READMANGA_RU",
            "REMANGA",
            "SEIMANGA",
            "SELFMANGA",
            "USAGI",
            "WAMANGA",
            "YAOICHAN",
            "YAOILIB",
            "ZENMANGA",
        )
        val expectedBrokenSources = setOf(
            "ALLHENTAI",
            "BEST_MANGA",
            "HENCHAN",
            "HENTAILIB",
            "MANGAZAVR",
            "MINTMANGA",
            "NINEMANGA_RU",
            "NUDEMOON",
            "ZENMANGA",
        )
        val sources = MangaParserSource.entries.filter { it.locale == "ru" }

        assertEquals(expectedSources, sources.mapTo(mutableSetOf()) { it.name })
        assertEquals(expectedBrokenSources, sources.filter { it.isBroken }.mapTo(mutableSetOf()) { it.name })

        val context = FixtureMangaLoaderContext.builder().build()
        sources.forEach { source ->
            assertEquals(source, source.newParser(context).source)
        }
        assertEquals("ru.ninemanga.com", MangaParserSource.NINEMANGA_RU.newParser(context).domain)
    }

    private fun verifyLibSocialSummary() {
        val structured = JSONObject(
            """
            {
              "summary": {
                "type": "doc",
                "content": [
                  {
                    "type": "paragraph",
                    "content": [
                      {"type": "text", "text": "First line"},
                      {"type": "hardBreak"},
                      {"type": "text", "text": "Second line"}
                    ]
                  },
                  {
                    "type": "paragraph",
                    "content": [{"type": "text", "text": "Next paragraph"}]
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("First line\nSecond line\nNext paragraph", structured.parseLibSocialSummary())
        assertEquals(
            "Plain description",
            JSONObject("""{"summary":"Plain description"}""").parseLibSocialSummary(),
        )
    }

    private suspend fun verifyAComics() {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://acomics.ru/comics?ratings[]=1&ratings[]=2&ratings[]=3&ratings[]=4&ratings[]=5&ratings[]=6&skip=0&sort=last_update",
                "/fixtures/acomics/catalog.html",
            )
            .get("https://acomics.ru/~fixture/about", "/fixtures/acomics/details.html")
            .get("https://acomics.ru/comics", "/fixtures/acomics/catalog.html")
            .get("https://acomics.ru/~fixture/1", "/fixtures/acomics/page.html")
            .build()
        val parser = MangaParserSource.ACOMICS.newParser(context)

        val manga = parser.getList(0, SortOrder.UPDATED, MangaListFilter.EMPTY).single()
        assertEquals("Fixture Comic", manga.title)
        assertEquals("https://acomics.ru/upload/fixture-cover.jpg", manga.coverUrl)

        val details = parser.getDetails(manga)
        assertEquals("Current AComics description.", details.description)
        assertEquals(setOf("Fantasy"), details.tags.mapTo(linkedSetOf()) { it.title })
        assertEquals(setOf("Fixture Author"), details.authors)

        val pages = parser.getPages(details.chapters.orEmpty().single())
        assertEquals(3, pages.size)
        assertEquals("https://acomics.ru/upload/fixture-page.jpg", parser.getPageUrl(pages.first()))
        context.verifyAllFixturesUsed()
    }

    private suspend fun verifyDesu() {
        val context = FixtureMangaLoaderContext.builder()
            .get("https://desu.uno/manga/?page=1", "/fixtures/desu/catalog.html")
            .get("https://desu.uno/manga/example.42/", "/fixtures/desu/details.html")
            .get("https://desu.uno/api/manga/42/chapters/690", "/fixtures/desu/pages.json")
            .build()

        verifyReadingContract(
            source = MangaParserSource.DESUME,
            context = context,
            expectedTitle = "Пример манги",
            sortOrder = SortOrder.UPDATED,
            expectedPageUrl = "https://img3.desu.uno/manga/rus/example/001.webp",
        )
    }

    private suspend fun verifyMangaOneLove() {
        val context = FixtureMangaLoaderContext.builder()
            .post(
                "https://mangaonelove.su/wp-admin/admin-ajax.php",
                "/fixtures/mangaonelove/list.html",
            )
            .get("https://mangaonelove.su/manga/example-title", "/fixtures/mangaonelove/details.html")
            .get(
                "https://mangaonelove.su/chapter/example-title-1?style=list",
                "/fixtures/mangaonelove/pages.html",
            )
            .build()

        verifyReadingContract(
            source = MangaParserSource.MANGAONELOVE,
            context = context,
            expectedTitle = "Example Title",
            sortOrder = SortOrder.UPDATED,
        )
    }

    private suspend fun verifyMangaMammy() {
        val context = FixtureMangaLoaderContext.builder()
            .post(
                "https://p.nimanga.com/wp-admin/admin-ajax.php",
                "/fixtures/mangamammy/list.html",
            )
            .get("https://p.nimanga.com/manga/example-title", "/fixtures/mangamammy/details.html")
            .get(
                "https://p.nimanga.com/chapter/example-title-1?style=list",
                "/fixtures/mangamammy/pages.html",
            )
            .build()

        verifyReadingContract(
            source = MangaParserSource.MANGAMAMMY,
            context = context,
            expectedTitle = "Example Title",
            sortOrder = SortOrder.UPDATED,
        )
    }

    private suspend fun verifyMangaWtfFilters() {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://api.inkstory.net/v2/books?page=0&size=20&type=COMIC&sort=updatedAt%2Cdesc&labelsInclude=action&labelsExclude=romance",
                "/fixtures/mangawtf/list.json",
            )
            .build()
        val source = MangaParserSource.MANGA_WTF
        val manga = source.newParser(context).getList(
            offset = 0,
            order = SortOrder.UPDATED,
            filter = MangaListFilter.EMPTY.copy(
                tags = setOf(MangaTag("Action", "action", source)),
                tagsExclude = setOf(MangaTag("Romance", "romance", source)),
            ),
        ).single()

        assertEquals("Test title", manga.title)
        context.verifyAllFixturesUsed()
    }

    private suspend fun verifyReadManga() {
        val context = FixtureMangaLoaderContext.builder()
            .get(
                "https://a.zazaza.me/search/advancedResults?q=Artifact&offset=0&years=1900%2C2099&sortType=DATE_CREATE",
                "/fixtures/readmanga/search.html",
            )
            .get(
                "https://a.zazaza.me/igrok__kotoryi_est_artefakty",
                "/fixtures/readmanga/details.html",
            )
            .get(
                "https://a.zazaza.me/igrok__kotoryi_est_artefakty/vol1/45?mtr=1",
                "/fixtures/readmanga/pages.html",
            )
            .build()
        val parser = MangaParserSource.READMANGA_RU.newParser(context)
        val manga = parser.getList(
            offset = 0,
            order = SortOrder.NEWEST,
            filter = MangaListFilter.EMPTY.copy(query = "Artifact"),
        ).single()
        assertEquals("Игрок, который ест артефакты", manga.title)

        val details = parser.getDetails(manga)
        assertEquals("Current ReadManga description.", details.description)
        assertEquals(setOf("Боевик", "Драма"), details.tags.mapTo(linkedSetOf()) { it.title })
        assertEquals("https://rm.one-way.work/uploads/pics/example.jpg", details.largeCoverUrl)

        val chapter = details.chapters.orEmpty().single()
        assertEquals(45f, chapter.number)
        assertEquals(1, chapter.volume)

        val pages = parser.getPages(chapter)
        assertFalse(pages.isEmpty())
        assertEquals("https://images.example/page-01.jpg", parser.getPageUrl(pages.single()))
        context.verifyAllFixturesUsed()
    }

    private suspend fun verifyWaManga() {
        val context = FixtureMangaLoaderContext.builder()
            .get("https://wamanga.ru/api/v1/manga", "/fixtures/wamanga/catalog.json")
            .get("https://wamanga.ru/api/v1/manga/manga-id", "/fixtures/wamanga/details.json")
            .get("https://wamanga.ru/api/v1/manga/manga-id/chapters", "/fixtures/wamanga/chapters.json")
            .get("https://wamanga.ru/api/v1/chapters/chapter-id", "/fixtures/wamanga/pages.json")
            .build()

        verifyReadingContract(
            source = MangaParserSource.WAMANGA,
            context = context,
            expectedTitle = "Example Manga",
            sortOrder = SortOrder.ALPHABETICAL,
            expectedPageUrl = "https://wamanga.ru/app/uploads/example/001.webp",
        )

        val item = JSONObject(
            """{
              "title":"Russian title",
              "titleEnglish":"English title",
              "alternateTitles":["Original title"],
              "genres":["Action","Drama"]
            }""",
        )
        assertEquals(setOf("English title", "Original title"), item.parseWaMangaAlternativeTitles())
        assertEquals(setOf("Action", "Drama"), item.parseWaMangaGenres())
        assertEquals(
            setOf("Russian title", "English title", "Original title"),
            item.parseWaMangaSearchText(),
        )
    }

    private suspend fun verifyReadingContract(
        source: MangaParserSource,
        context: FixtureMangaLoaderContext,
        expectedTitle: String,
        sortOrder: SortOrder,
        expectedPageUrl: String? = null,
    ) {
        val parser = source.newParser(context)
        val manga = parser.getList(0, sortOrder, MangaListFilter.EMPTY).single()
        assertEquals(source, manga.source)
        assertEquals(expectedTitle, manga.title)

        val details = parser.getDetails(manga)
        assertEquals(manga.id, details.id)
        assertEquals(manga.url, details.url)
        assertEquals(source, details.source)

        val chapter = details.chapters.orEmpty().single()
        assertEquals(source, chapter.source)

        val pages = parser.getPages(chapter)
        assertFalse(pages.isEmpty())
        assertEquals(source, pages.single().source)
        if (expectedPageUrl != null) {
            assertEquals(expectedPageUrl, parser.getPageUrl(pages.single()))
        }
        context.verifyAllFixturesUsed()
    }
}

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
