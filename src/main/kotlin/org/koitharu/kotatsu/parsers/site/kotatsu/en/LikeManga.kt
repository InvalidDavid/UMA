package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.LikeMangaParser
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.network.CommonHeaders

private const val USER_AGENT = "Usagi/1 (Android)"

@MangaSourceParser("LIKEMANGA", "LikeManga", "en")
internal class LikeManga(context: MangaLoaderContext) :
    LikeMangaParser(context, MangaParserSource.LIKEMANGA, "likemanga.ink") {
    override val userAgentKey = ConfigKey.UserAgent(USER_AGENT,)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val newRequest = request.newBuilder()
            .header(CommonHeaders.USER_AGENT, USER_AGENT)
            .build()

        return chain.proceed(newRequest)
    }
}