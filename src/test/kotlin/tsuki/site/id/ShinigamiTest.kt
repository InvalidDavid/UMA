package tsuki.site.id

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tsuki.MangaLoaderContext
import tsuki.MangaParser
import tsuki.bitmap.Bitmap
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig
import tsuki.model.Manga
import tsuki.model.MangaParserSource
import tsuki.model.MangaSource
import tsuki.model.RATING_UNKNOWN

class ShinigamiTest {

    private fun createMockContext(handler: (String) -> String): MangaLoaderContext {
        val mockConfig = object : MangaSourceConfig {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(handler(url).toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        return object : MangaLoaderContext() {
            override val httpClient: OkHttpClient = client
            override val cookieJar: CookieJar = CookieJar.NO_COOKIES
            override fun newParserInstance(source: MangaSource): MangaParser = throw UnsupportedOperationException()
            override fun getParserSources(): List<MangaSource> = emptyList()
            override suspend fun evaluateJs(script: String): String = ""
            override suspend fun evaluateJs(baseUrl: String, script: String): String = ""
            override fun getConfig(source: MangaSource): MangaSourceConfig = mockConfig
            override fun getDefaultUserAgent(): String = "TestUA"
            override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = response
            override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
        }
    }

    private fun stubManga(id: String) = Manga(
        id = 1L,
        url = "/manga/detail/$id",
        publicUrl = "https://s1.shinigami.mx/series/$id",
        title = "Test Manga",
        altTitles = emptySet(),
        coverUrl = null,
        largeCoverUrl = null,
        authors = emptySet(),
        tags = emptySet(),
        state = null,
        description = null,
        contentRating = null,
        source = MangaParserSource.SHINIGAMI,
        rating = RATING_UNKNOWN,
    )

    @Test
    fun testGetDetailsPaginatesChaptersWithCursorAndReversesOrder() = runTest {
        val requestedUrls = mutableListOf<String>()
        val parser = Shinigami(createMockContext { url ->
            requestedUrls.add(url)
            when {
                url.endsWith("/manga/detail/m1") -> """{"data":{"manga_id":"m1","status":1,"description":"desc"}}"""
                url.contains("/chapter/m1/list") && !url.contains("cursor=") -> """
                    {
                        "meta":{"has_next":true,"limit":2000,"next_cursor":"cur_2"},
                        "data":[
                            {"chapter_id":"c3","chapter_number":3,"release_date":"2026-01-03T00:00:00Z"},
                            {"chapter_id":"c2","chapter_number":2,"release_date":null}
                        ]
                    }
                """.trimIndent()
                url.contains("cursor=cur_2") -> """
                    {
                        "meta":{"has_next":false,"limit":2000},
                        "data":[
                            {"chapter_id":"c1","chapter_number":1,"release_date":"2026-01-01T00:00:00Z"}
                        ]
                    }
                """.trimIndent()
                else -> error("Unexpected URL: $url")
            }
        })

        val details = parser.getDetails(stubManga("m1"))
        val chapters = details.chapters.orEmpty()

        assertEquals(listOf(1f, 2f, 3f), chapters.map { it.number })
        assertEquals(0L, chapters[1].uploadDate)
        assertTrue(requestedUrls.any { it.endsWith("/chapter/m1/list?limit=2000") })
        assertTrue(requestedUrls.any { it.endsWith("/chapter/m1/list?limit=2000&cursor=cur_2") })
    }

    @Test
    fun testGetDetailsStopsOnRepeatedCursorAndEmptyData() = runTest {
        var chapterCalls = 0
        val parser = Shinigami(createMockContext { url ->
            when {
                url.endsWith("/manga/detail/m2") -> """{"data":{"manga_id":"m2","status":1}}"""
                url.contains("/chapter/m2/list") -> {
                    chapterCalls++
                    """
                        {
                            "meta":{"has_next":true,"limit":2000,"next_cursor":"stuck_cursor"},
                            "data":[{"chapter_id":"c$chapterCalls","chapter_number":$chapterCalls,"release_date":"2026-01-01T00:00:00Z"}]
                        }
                    """.trimIndent()
                }
                else -> error("Unexpected URL: $url")
            }
        })

        val details = parser.getDetails(stubManga("m2"))
        assertEquals(2, chapterCalls)
        assertEquals(2, details.chapters?.size)
    }

    @Test
    fun testGetDetailsHandlesEmptyChapterList() = runTest {
        val parser = Shinigami(createMockContext { url ->
            when {
                url.endsWith("/manga/detail/m3") -> """{"data":{"manga_id":"m3","status":1}}"""
                url.contains("/chapter/m3/list") -> """{"meta":{"has_next":false,"limit":2000},"data":[]}"""
                else -> error("Unexpected URL: $url")
            }
        })

        val details = parser.getDetails(stubManga("m3"))
        assertTrue(details.chapters.orEmpty().isEmpty())
    }

    @Test
    fun testGetDetailsDeduplicatesChaptersAndHandlesNullFields() = runTest {
        val parser = Shinigami(createMockContext { url ->
            when {
                url.endsWith("/manga/detail/m4") -> """{"data":{"manga_id":"m4","status":1}}"""
                url.contains("/chapter/m4/list") && !url.contains("cursor=") -> """
                    {
                        "meta":{"has_next":true,"limit":2000,"next_cursor":"cur_2"},
                        "data":[
                            {"chapter_id":"c2","chapter_number":2.5,"release_date":"2026-01-02T00:00:00Z"},
                            {"chapter_id":null,"chapter_number":99,"release_date":null}
                        ]
                    }
                """.trimIndent()
                url.contains("cursor=cur_2") -> """
                    {
                        "meta":{"has_next":false,"limit":2000},
                        "data":[
                            {"chapter_id":"c2","chapter_number":2.5,"release_date":"2026-01-02T00:00:00Z"},
                            {"chapter_id":"c0","chapter_number":null,"release_date":null}
                        ]
                    }
                """.trimIndent()
                else -> error("Unexpected URL: $url")
            }
        })

        val chapters = parser.getDetails(stubManga("m4")).chapters.orEmpty()
        assertEquals(listOf(0f, 2.5f), chapters.map { it.number })
        assertEquals(listOf("Chapter 0", "Chapter 2.5"), chapters.map { it.title })
    }
}
