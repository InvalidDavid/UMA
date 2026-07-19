package tsuki.site.pt.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.HiperParser

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

import okhttp3.Headers

@MangaSourceParser("LERHENTAIS", "LerHentais", "pt", ContentType.HENTAI)
internal class LerHentais(context: MangaLoaderContext) :
    HiperParser(context, MangaParserSource.LERHENTAIS, "lerhentais.com") {

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("x-flux-node", "G2ZsDdWhUwdU82Vw")
        .build()
}