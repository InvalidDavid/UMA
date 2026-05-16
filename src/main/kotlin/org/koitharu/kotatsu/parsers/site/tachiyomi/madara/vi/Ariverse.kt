package org.koitharu.kotatsu.parsers.site.tachiyomi.madara.vi

import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.parsers.TachiyomiSource
import org.koitharu.kotatsu.parsers.site.tachiyomi.madara.Madara
import java.text.SimpleDateFormat
import java.util.Locale

@TachiyomiSource("ARIVERSE", "Ariverse", "vi")
class Ariverse :
    Madara(
        "Ariverse",
        "https://arigl.com",
        "vi",
        SimpleDateFormat("dd/MM/yyyy", Locale.ROOT),
    ) {

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()

    override val mangaDetailsSelectorTitle =
        "div.post-content > h3, div.post-title h3, div.post-title h1, #manga-title > h1"

    override val altNameSelector = ".post-content_item:contains(Tên Khác) .summary-content"
}