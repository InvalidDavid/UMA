package org.koitharu.kotatsu.parsers.site.kotatsu.pt.hentais

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.HiperParser

@MangaSourceParser("LERHENTAIS", "LerHentais", "pt", ContentType.HENTAI)
internal class LerHentais(context: MangaLoaderContext) :
    HiperParser(context, MangaParserSource.LERHENTAIS, "lerhentais.com") {

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("x-flux-node", "G2ZsDdWhUwdU82Vw")
        .build()
}