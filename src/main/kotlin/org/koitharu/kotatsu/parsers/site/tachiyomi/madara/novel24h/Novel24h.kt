package org.koitharu.kotatsu.parsers.site.tachiyomi.madara.novel24h

import org.koitharu.kotatsu.parsers.TachiyomiSource
import org.koitharu.kotatsu.parsers.site.tachiyomi.madara.Madara

@TachiyomiSource("TWENTYFOURHNOVEL", "24HNovel", "en")
class Novel24h : Madara("24HNovel", "https://24hnovel.com", "en") {
    override val useLoadMoreRequest = LoadMoreStrategy.Never
    override val useNewChapterEndpoint = true
}