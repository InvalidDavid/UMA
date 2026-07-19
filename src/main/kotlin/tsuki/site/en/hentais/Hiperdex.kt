package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.HiperParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

import okhttp3.Headers

@MangaSourceParser("HIPERDEX", "Hiperdex", "en", ContentType.HENTAI)
internal class Hiperdex(context: MangaLoaderContext) :
    HiperParser(context, MangaParserSource.HIPERDEX, "hiperdex.com") {

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("x-cfg-auth", "yceqt7qgu004")
        .build()
}
