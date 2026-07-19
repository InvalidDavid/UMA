package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.network.CommonHeaders
import tsuki.parsers.LikeMangaParser

import tsuki.model.MangaParserSource

import okhttp3.Interceptor
import okhttp3.Response

private const val USER_AGENT = "Usagi/1 (Android)"

@MangaSourceParser("LIKEMANGA", "LikeManga", "en")
internal class LikeManga(context: MangaLoaderContext) :
    LikeMangaParser(context, MangaParserSource.LIKEMANGA, "likemanga.ink") {
    override val userAgentKey = ConfigKey.UserAgent(USER_AGENT)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val newRequest = request.newBuilder()
            .header(CommonHeaders.USER_AGENT, USER_AGENT)
            .build()

        return chain.proceed(newRequest)
    }
}