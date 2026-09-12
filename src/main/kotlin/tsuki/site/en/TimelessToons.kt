package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.KeyoApp

import tsuki.model.MangaParserSource

@MangaSourceParser("TIMELESSTOONS", "TimelessToons", "en")
internal class TimelessToons(context: MangaLoaderContext) :
    KeyoApp(context, MangaParserSource.TIMELESSTOONS, "timelesstoons.org") {

    override fun popularMangaSelector() =
        "div:has(> h2:contains(Trending)) + div .group"

    override fun latestUpdatesSelector() =
        "div.grid > div.group.latest-poster"
}
