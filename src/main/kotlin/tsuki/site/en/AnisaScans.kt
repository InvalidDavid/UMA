package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.parsers.MadaraParser
import tsuki.network.UserAgents

import tsuki.model.MangaParserSource

@MangaSourceParser("ANISASCANS", "AnisaScans", "en")
internal class AnisaScans(context: MangaLoaderContext):
    MadaraParser(context, MangaParserSource.ANISASCANS, "anisascans.in") {
    override val datePattern = "d MMMM, yyyy"
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.remove(userAgentKey)
    }
}
