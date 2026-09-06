package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.parsers.MadaraParser
import tsuki.network.UserAgents

import tsuki.model.MangaParserSource

@MangaSourceParser("DRAGONTEA", "DragonTea", "en")
internal class DragonTea(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.DRAGONTEA, "dragontea.ink") {
    override val datePattern = "MM/dd/yyyy"
    override val postReq = false
    override val listUrl = "novel/"
    override val tagPrefix = "novel-genre/"

    override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.remove(userAgentKey)
    }
}
