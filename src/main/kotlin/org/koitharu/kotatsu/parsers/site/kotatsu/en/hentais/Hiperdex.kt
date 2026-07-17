package org.koitharu.kotatsu.parsers.site.kotatsu.en.hentais

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.HiperParser

@Broken
@MangaSourceParser("HIPERDEX", "Hiperdex", "en", ContentType.HENTAI)
internal class Hiperdex(context: MangaLoaderContext) :
    HiperParser(context, MangaParserSource.HIPERDEX, "hiperdex.com") {

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("x-svc-gate", "f5pabmx7sdek")
        .build()
}
