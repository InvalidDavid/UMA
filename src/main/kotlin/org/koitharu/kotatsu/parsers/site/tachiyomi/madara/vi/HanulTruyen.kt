package org.koitharu.kotatsu.parsers.site.tachiyomi.madara.vi

import eu.kanade.tachiyomi.network.interceptor.rateLimit
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.parsers.TachiyomiSource
import org.koitharu.kotatsu.parsers.site.tachiyomi.madara.Madara
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@TachiyomiSource("HANULTRUYEN", "HanulTruyen", "vi")
class HanulTruyen :
    Madara(
        "HanulTruyen",
        "https://hanultruyen.info",
        "vi",
        dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        },
    ) {

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(3)
        .build()
}