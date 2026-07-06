package org.koitharu.kotatsu.parsers.site.kotatsu.id

import androidx.collection.arraySetOf
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.parsers.MadaraParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MGKOMIK", "MGKomik", "id")
internal class MGKomik(context: MangaLoaderContext, ):
    MadaraParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc",) {

    override val withoutAjax = true

    override val tagPrefix = "genres/"

    override val datePattern = "dd MMM yy"

    override val selectDesc = "div.description-summary div.summary__content p"

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        return super.fetchAvailableTags()
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        if (order == SortOrder.POPULARITY) {
            val p = page + 1
            val url = buildString {
                append("https://")
                append(domain)
                append("/komik")
                if (p > 1) {
                    append("/page/")
                    append(p)
                }
                append("/?m_orderby=trending")
            }
            return parseMangaList(webClient.httpGet(url).parseHtml())
        }
        return super.getListPage(page, order, filter)
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags(),
    )

    private fun availableTags() = arraySetOf(
        MangaTag("2 hours ago", "2-hours-ago", source),
        MangaTag("7 hours ago", "7-hours-ago", source),
        MangaTag("8 hours ago", "8-hours-ago", source),
        MangaTag("11 hours ago", "11-hours-ago", source),
        MangaTag("13 hours ago", "13-hours-ago", source),
        MangaTag("15 hours ago", "15-hours-ago", source),
        MangaTag("1 day ago", "1-day-ago", source),
        MangaTag("2 days ago", "2-days-ago", source),
        MangaTag("Action", "action", source),
        MangaTag("Adaptation", "adaptation", source),
        MangaTag("Adult", "adult", source),
        MangaTag("Adventure", "adventure", source),
        MangaTag("Animals", "animals", source),
        MangaTag("Apocalypse", "apocalypse", source),
        MangaTag("Comedy", "comedy", source),
        MangaTag("Cooking", "cooking", source),
        MangaTag("Crime", "crime", source),
        MangaTag("Demon", "demon", source),
        MangaTag("Demons", "demons", source),
        MangaTag("Drama", "drama", source),
        MangaTag("Dungeons", "dungeons", source),
        MangaTag("Ecchi", "ecchi", source),
        MangaTag("Fantasy", "fantasy", source),
        MangaTag("Fight", "fight", source),
        MangaTag("Fighting", "fighting", source),
        MangaTag("Full Color", "full-color", source),
        MangaTag("Game", "game", source),
        MangaTag("Gender Bender", "gender-bender", source),
        MangaTag("Gore", "gore", source),
        MangaTag("Harem", "harem", source),
        MangaTag("Historical", "historical", source),
        MangaTag("Horror", "horror", source),
        MangaTag("Isekai", "isekai", source),
        MangaTag("Josei", "josei", source),
        MangaTag("Josei(W)", "joseiw", source),
        MangaTag("Kids", "kids", source),
        MangaTag("Magic", "magic", source),
        MangaTag("Manga", "manga", source),
        MangaTag("Mangatoon", "mangatoon", source),
        MangaTag("Manhua", "manhua", source),
        MangaTag("Manhwa", "manhwa", source),
        MangaTag("Martial Arts", "martial-arts", source),
        MangaTag("Mature", "mature", source),
        MangaTag("Mecha", "mecha", source),
        MangaTag("Medical", "medical", source),
        MangaTag("Military", "military", source),
        MangaTag("Modern", "modern", source),
        MangaTag("Monsters", "monsters", source),
        MangaTag("Murim", "murim", source),
        MangaTag("Music", "music", source),
        MangaTag("Mystery", "mystery", source),
        MangaTag("Office Workers", "office-workers", source),
        MangaTag("One-Shot", "one-shot", source),
        MangaTag("Overpowered", "overpowered", source),
        MangaTag("Police", "police", source),
        MangaTag("Psychological", "psychological", source),
        MangaTag("Regression", "regression", source),
        MangaTag("Reincarnation", "reincarnation", source),
        MangaTag("Revenge", "revenge", source),
        MangaTag("Reverse Harem", "reverse-harem", source),
        MangaTag("Rofan", "rofan", source),
        MangaTag("Romance", "romance", source),
        MangaTag("School", "school", source),
        MangaTag("School Life", "school-life", source),
        MangaTag("Sci-fi", "sci-fi", source),
        MangaTag("Seinen", "seinen", source),
        MangaTag("Shoujo", "shoujo", source),
        MangaTag("Shoujo Ai", "shoujoai", source),
        MangaTag("Shoujo AI", "shoujo-ai", source),
        MangaTag("Shoujo(G)", "shoujog", source),
        MangaTag("Shounen", "shounen", source),
        MangaTag("Slice of Life", "slice-of-life", source),
        MangaTag("Smut", "smut", source),
        MangaTag("Sports", "sports", source),
        MangaTag("Super Power", "super-power", source),
        MangaTag("Superhero", "superhero", source),
        MangaTag("Supernatural", "supernatural", source),
        MangaTag("Supranatural", "supranatural", source),
        MangaTag("Survival", "survival", source),
        MangaTag("System", "system", source),
        MangaTag("Thriller", "thriller", source),
        MangaTag("Time Travel", "time-travel", source),
        MangaTag("Tragedy", "tragedy", source),
        MangaTag("Transmigration", "transmigration", source),
        MangaTag("Vampire", "vampire", source),
        MangaTag("Villainess", "villainess", source),
        MangaTag("Violence", "violence", source),
        MangaTag("War", "war", source),
        MangaTag("Webtoon", "webtoon", source),
        MangaTag("Webtoons", "webtoons", source),
        MangaTag("Wuxia", "wuxia", source),
        MangaTag("Yuri", "yuri", source),
    )
}
