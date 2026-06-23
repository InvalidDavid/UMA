package org.koitharu.kotatsu.parsers.site.tachiyomi.all.hentai.imhentai

import org.koitharu.kotatsu.parsers.site.tachiyomi.parsers.galleryadults.GalleryAdults
import org.koitharu.kotatsu.parsers.site.tachiyomi.parsers.galleryadults.imgAttr
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.TachiyomiSource
import org.koitharu.kotatsu.parsers.model.ContentType
import java.io.IOException

@TachiyomiSource("IMHENTAI_JA", "IMHentai (Japanese)", "ja", ContentType.HENTAI)
class IMHentaiJA : IMHentai("ja", LANGUAGE_JAPANESE)

@TachiyomiSource("IMHENTAI_ES", "IMHentai (Spanish)", "es", ContentType.HENTAI)
class IMHentaiES : IMHentai("es", LANGUAGE_SPANISH)

@TachiyomiSource("IMHENTAI_FR", "IMHentai (French)", "fr", ContentType.HENTAI)
class IMHentaiFR : IMHentai("fr", LANGUAGE_FRENCH)

@TachiyomiSource("IMHENTAI_KO", "IMHentai (Korean)", "ko", ContentType.HENTAI)
class IMHentaiKO : IMHentai("ko", LANGUAGE_KOREAN)

@TachiyomiSource("IMHENTAI_DE", "IMHentai (German)", "de", ContentType.HENTAI)
class IMHentaiDE : IMHentai("de", LANGUAGE_GERMAN)

@TachiyomiSource("IMHENTAI_RU", "IMHentai (Russian)", "ru", ContentType.HENTAI)
class IMHentaiRU : IMHentai("ru", LANGUAGE_RUSSIAN)

@TachiyomiSource("IMHENTAI_EN", "IMHentai (English)", "en", ContentType.HENTAI)
class IMHentaiEN : IMHentai("", LANGUAGE_ENGLISH)

//@TachiyomiSource("IMHENTAI_ALL", "IMHentai (All)", "", ContentType.HENTAI)
//class IMHentaiAll : IMHentai("", LANGUAGE_MULTI)

abstract class IMHentai(
    lang: String = "all",
    override val mangaLang: String = LANGUAGE_MULTI,
) : GalleryAdults(
    "IMHentai",
    "https://imhentai.xxx",
    lang = lang,
) {
    override val name = "IMHentai (All)"

    override val supportsLatest = true
    override val useIntermediateSearch: Boolean = true
    override val supportAdvancedSearch: Boolean = true
    override val supportSpeechless: Boolean = true

    override fun Element.mangaLang() = select("a:has(.thumb_flag)").attr("href")
        .removeSuffix("/").substringAfterLast("/")
        .let {
            // Include Speechless in search results
            if (it == LANGUAGE_SPEECHLESS) mangaLang else it
        }

    override val client: OkHttpClient = network.client
        .newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers.newBuilder()
                .removeAll("Accept-Encoding")
                .build()
            chain.proceed(request.newBuilder().headers(headers).build())
        }
        .addInterceptor(
            fun(chain): Response {
                val response = chain.proceed(chain.request())
                if (!response.headers("Content-Type").toString().contains("text/html")) return response

                val responseContentType = response.body.contentType()
                val responseString = response.body.string()

                if (responseString.contains("Overload... Please use the advanced search")) {
                    response.close()
                    throw IOException("IMHentai search is overloaded try again later")
                }

                return response.newBuilder()
                    .body(responseString.toResponseBody(responseContentType))
                    .build()
            },
        ).build()

    /* Details */
    override fun Element.getInfo(tag: String): String = select("li:has(.tags_text:contains($tag:)) a.tag")
        .joinToString {
            val name = it.ownText()
            if (tag.contains(regexTag)) {
                genres[name] = it.attr("href")
                    .removeSuffix("/").substringAfterLast('/')
            }
            listOf(
                name,
                it.select(".split_tag").text()
                    .trim()
                    .removePrefix("| "),
            )
                .filter { s -> s.isNotBlank() }
                .joinToString()
        }

    override fun Element.getCover() = selectFirst(".left_cover img")?.imgAttr()

    override val mangaDetailInfoSelector = ".gallery_first"

    /* Pages */
    override val thumbnailSelector = ".gthumb"
    override val pageUri = "view"
}