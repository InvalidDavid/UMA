package tsuki.site.id.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.MangaParserAuthProvider
import tsuki.parsers.MangaThemesia
import tsuki.exception.ParseException

import tsuki.model.*
import tsuki.util.*

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

@MangaSourceParser("KOMIKTAP", "Komiktap", "id", ContentType.HENTAI)
internal class Komiktap(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.KOMIKTAP, "komiktap.info", pageSize = 25), MangaParserAuthProvider, Interceptor {

    private val selectScript = "script[src^='data:text/javascript;base64,']"
    private val selectTestScript = "script:containsData(ts_reader.run)"
    private val selectPage = "div#readerarea img"

    override val authUrl: String get() = "https://$domain/"

    override suspend fun isAuthorized(): Boolean {
        val response = webClient.httpGet("https://$domain/")
        return response.headers["x-sucuri-cache"] != null ||
                response.headers["x-sucuri-id"] == null
    }

    override suspend fun getUsername(): String = ""

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl).parseHtml()

        val test = doc.select(selectTestScript)
        if (test.isEmpty()) {
            return doc.select(selectPage).mapNotNull { img ->
                val url = img.imgAttr().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MangaPage(id = generateUid(url), url = url, preview = null, source = source)
            }
        }

        val images = try {
            val base64Script = doc.select(selectScript)
            var decode = ""
            for (elem in base64Script) {
                val src = elem.attr("src")
                if (src.startsWith("data:text/javascript;base64,")) {
                    decode = Base64.getDecoder()
                        .decode(src.removePrefix("data:text/javascript;base64,"))
                        .toString(Charsets.UTF_8)
                    if (decode.startsWith("ts_reader.run")) break
                }
            }

            if (decode.startsWith("ts_reader.run")) {
                extractImagesFromScript(decode)
            } else {
                val scriptElem = doc.selectFirstOrThrow(selectTestScript)
                extractImagesFromScript(scriptElem.data())
            }
        } catch (e: Exception) {
            throw ParseException("Failed to extract images: ${e.message}", chapterUrl)
        }

        return (0 until images.length()).map { i ->
            val url = images.getString(i)
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    private fun extractImagesFromScript(scriptBody: String): JSONArray {
        val json = scriptBody.substringAfter('(').substringBeforeLast(')')
        return JSONObject(json)
            .getJSONArray("sources")
            .getJSONObject(0)
            .getJSONArray("images")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val mime = response.headers["Content-Type"]
        if (response.isSuccessful && mime == "application/octet-stream") {
            val newBody = response.body.source().asResponseBody("image/jpeg".toMediaTypeOrNull()!!)
            return response.newBuilder().body(newBody).build()
        }
        return response
    }
}
