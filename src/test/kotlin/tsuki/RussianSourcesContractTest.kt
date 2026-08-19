package tsuki

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect
import tsuki.config.ConfigKey
import tsuki.config.MangaSourceConfig
import tsuki.model.MangaParserSource
import tsuki.model.MangaSource

class RussianSourcesContractTest {

    @Test
    fun `Russian catalog metadata and parser construction remain valid`() {
        val expectedSources = setOf(
            "ACOMICS",
            "ASTRAMANGA",
            "COMX",
            "DESUME",
            "MANGABALL_RU",
            "MANGACHAN",
            "MANGALIB",
            "MANGAMAMMY",
            "MANGAONELOVE",
            "MANGAPLUSPARSER_RU",
            "MANGA_WTF",
            "READMANGA_RU",
            "REMANGA",
            "SEIMANGA",
            "SELFMANGA",
            "USAGI",
            "WAMANGA",
            "YAOICHAN",
            "YAOILIB",
        )
        val sources = MangaParserSource.entries.filter { it.locale == "ru" }

        assertEquals(expectedSources, sources.mapTo(mutableSetOf()) { it.name })
        assertEquals(
            emptySet<String>(),
            sources.filter { it.isBroken }.mapTo(mutableSetOf()) { it.name },
            "Broken sources must not be shipped",
        )

        val context = CatalogMangaLoaderContext()
        sources.forEach { source ->
            assertEquals(source, source.newParser(context).source)
        }
    }
}

@Suppress("OVERRIDE_DEPRECATION")
private class CatalogMangaLoaderContext : MangaLoaderContext() {

    override val httpClient: OkHttpClient = OkHttpClient()

    override val cookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

        override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
    }

    override fun newParserInstance(source: MangaSource): MangaParser {
        require(source is MangaParserSource) { "Unsupported catalog source: $source" }
        return source.newParser(this)
    }

    override fun getParserSources(): List<MangaSource> = MangaParserSource.entries

    override suspend fun evaluateJs(script: String): String? {
        error("JavaScript evaluation is not available in catalog tests")
    }

    override suspend fun evaluateJs(baseUrl: String, script: String): String? {
        error("JavaScript evaluation is not available in catalog tests: $baseUrl")
    }

    override fun getConfig(source: MangaSource): MangaSourceConfig = object : MangaSourceConfig {
        override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
    }

    override fun getDefaultUserAgent(): String = "MangaRepoCatalogTest/1.0"

    override fun redrawImageResponse(
        response: Response,
        redraw: (image: Bitmap) -> Bitmap,
    ): Response = error("Image redraw is not available in catalog tests")

    override fun createBitmap(width: Int, height: Int): Bitmap = object : Bitmap {
        override val width: Int = width
        override val height: Int = height

        override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
            error("Bitmap drawing is not available in catalog tests")
        }
    }
}
