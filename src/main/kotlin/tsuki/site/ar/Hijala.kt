package tsuki.site.ar

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia
import tsuki.network.OkHttpWebClient

import tsuki.model.MangaChapter
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.imageio.ImageIO

@MangaSourceParser("HIJALA", "Hijala", "ar")
internal class Hijala(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.HIJALA, "hijala.com") { // adjust domain if needed

    override val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("ar"))

    override val webClient = OkHttpWebClient(
        context.httpClient.newBuilder()
            .addInterceptor(::unscrambleInterceptor)
            .build(),
        source
    )

    private fun unscrambleInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.host != SCRAMBLED_HOST) {
            return chain.proceed(request)
        }

        val leftUrl = url.queryParameter("leftImage") ?: error("Missing leftImage")
        val rightUrl = url.queryParameter("rightImage") ?: error("Missing rightImage")

        // Fetch both pieces
        val leftResponse = chain.proceed(request.newBuilder().url(leftUrl).build())
        val rightResponse = chain.proceed(request.newBuilder().url(rightUrl).build())

        return try {
            val leftBytes = leftResponse.body?.bytes() ?: error("Left image empty")
            val rightBytes = rightResponse.body?.bytes() ?: error("Right image empty")

            val leftImage = ImageIO.read(ByteArrayInputStream(leftBytes))
            val rightImage = ImageIO.read(ByteArrayInputStream(rightBytes))

            val width = leftImage.width + rightImage.width
            val height = maxOf(leftImage.height, rightImage.height)

            val combined = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = combined.createGraphics()
            g.drawImage(leftImage, 0, 0, null)
            g.drawImage(rightImage, leftImage.width, 0, null)
            g.dispose()

            val outputStream = ByteArrayOutputStream()
            ImageIO.write(combined, "jpeg", outputStream)
            val combinedBytes = outputStream.toByteArray()

            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(combinedBytes.toResponseBody("image/jpeg".toMediaType()))
                .build()
        } finally {
            leftResponse.close()
            rightResponse.close()
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        val pages = mutableListOf<MangaPage>()
        val jsonRegex = Regex(""""images"\s*:\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
        val jsonMatch = jsonRegex.find(doc.html())
        if (jsonMatch != null) {
            val jsonArray = org.json.JSONArray(jsonMatch.groupValues[1])
            for (i in 0 until jsonArray.length()) {
                val url = jsonArray.getString(i)
                pages.add(MangaPage(id = generateUid(url), url = url, preview = null, source = source))
            }
        } else {
            doc.select(pageSelector).forEach { img ->
                val url = img.imgAttr()
                if (url.isNotBlank()) {
                    pages.add(MangaPage(id = generateUid(url), url = url, preview = null, source = source))
                }
            }
        }

        // if chapter is not scrambled return
        if (doc.selectFirst("#chapter-pages-js-before") == null) return pages

        // group into left/right pairs and create synthetic URLs
        val pairs = mutableListOf<List<String>>()
        var i = 0
        while (i < pages.size) {
            val pair = pages.subList(i, minOf(i + 2, pages.size))
            if (pair.size == 2) {
                pairs.add(pair.map { it.url })
            }
            i += 2
        }

        return pairs.mapIndexed { _, pair ->
            val imageUrl = HttpUrl.Builder()
                .scheme("http")
                .host(SCRAMBLED_HOST)
                .addQueryParameter("leftImage", pair[0])
                .addQueryParameter("rightImage", pair[1])
                .build()
                .toString()
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    companion object {
        private const val SCRAMBLED_HOST = "127.0.0.1"
    }
}
