package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.MangaParserAuthProvider
import tsuki.parsers.MadaraParser

import tsuki.model.MangaParserSource

import okhttp3.Interceptor
import okhttp3.Headers

@MangaSourceParser("MGKOMIK", "MGKomik", "id")
internal class MGKomik(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc"), Interceptor, MangaParserAuthProvider {

    override val datePattern = "dd MMM yy"
    override val tagPrefix = "genres/"
    override val listUrl = "komik/"
    override val selectDesc = "div.description-summary div.summary__content p"
    override val stylePage = ""
    override val withoutAjax = true
    override val postReq = true

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Referer", "https://$domain/")
        .build()
}
