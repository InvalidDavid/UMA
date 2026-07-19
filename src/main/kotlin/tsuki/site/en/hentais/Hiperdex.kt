package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.HiperParser
import tsuki.Broken

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

import okhttp3.Headers

@Broken
@MangaSourceParser("HIPERDEX", "Hiperdex", "en", ContentType.HENTAI)
internal class Hiperdex(context: MangaLoaderContext) :
    HiperParser(context, MangaParserSource.HIPERDEX, "hiperdex.com") {

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("x-svc-gate", "f5pabmx7sdek")
        .build()
}