package org.koitharu.kotatsu.parsers.site.kotatsu.en

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.parsers.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("AQUAMANGA", "AquaManga", "en")
internal class AquaManga(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.AQUAMANGA, "aquareader.org", 20) {

    override val withoutAjax = true
    override val stylePage = ""

    override val selectChapter = ".aqua-ch-item"

    override val availableSortOrders: EnumSet<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = setOf(
            MangaTag("Academy", "academy", source),
            MangaTag("Action", "action", source),
            MangaTag("Adaptation", "adaptation", source),
            MangaTag("Adventure", "adventure", source),
            MangaTag("Comedy", "comedy", source),
            MangaTag("Cooking", "cooking", source),
            MangaTag("Crime", "crime", source),
            MangaTag("Cultivation", "cultivation", source),
            MangaTag("Delinquents", "delinquents", source),
            MangaTag("Demons", "demons", source),
            MangaTag("Drama", "drama", source),
            MangaTag("Dungeons", "dungeons", source),
            MangaTag("Ecchi", "ecchi", source),
            MangaTag("Fantasy", "fantasy", source),
            MangaTag("Game", "game", source),
            MangaTag("Gore", "gore", source),
            MangaTag("Harem", "harem", source),
            MangaTag("Historical", "historical", source),
            MangaTag("Horror", "horror", source),
            MangaTag("Isekai", "isekai", source),
            MangaTag("Josei", "josei", source),
            MangaTag("Magic", "magic", source),
            MangaTag("Manga", "manga", source),
            MangaTag("Manhua", "manhua", source),
            MangaTag("Manhwa", "manhwa", source),
            MangaTag("Martial Arts", "martial-arts", source),
            MangaTag("Mecha", "mecha", source),
            MangaTag("Medical", "medical", source),
            MangaTag("Military", "military", source),
            MangaTag("Monsters", "monsters", source),
            MangaTag("Murim", "murim", source),
            MangaTag("Music", "music", source),
            MangaTag("Mystery", "mystery", source),
            MangaTag("Necromancer", "necromancer", source),
            MangaTag("Ninja", "ninja", source),
            MangaTag("Office Workers", "office-workers", source),
            MangaTag("OP-MC", "op-mc", source),
            MangaTag("Overpowered", "overpowered", source),
            MangaTag("Philosophical", "philosophical", source),
            MangaTag("Post-Apocalyptic", "post-apocalyptic", source),
            MangaTag("Psychological", "psychological", source),
            MangaTag("Rebirth", "rebirth", source),
            MangaTag("Regression", "regression", source),
            MangaTag("Reincarnation", "reincarnation", source),
            MangaTag("Returner", "returner", source),
            MangaTag("Revenge", "revenge", source),
            MangaTag("Romance", "romance", source),
            MangaTag("School Life", "school-life", source),
            MangaTag("Sci-fi", "sci-fi", source),
            MangaTag("Seinen", "seinen", source),
            MangaTag("Shounen", "shounen", source),
            MangaTag("Slice-of-Life", "slice-of-life", source),
            MangaTag("Sports", "sports", source),
            MangaTag("Super Power", "super-power", source),
            MangaTag("Superhero", "superhero", source),
            MangaTag("Supernatural", "supernatural", source),
            MangaTag("Survival", "survival", source),
            MangaTag("System", "system", source),
            MangaTag("Thriller", "thriller", source),
            MangaTag("Time Travel", "time-travel", source),
            MangaTag("Tower", "tower", source),
            MangaTag("Tragedy", "tragedy", source),
            MangaTag("Vampire", "vampire", source),
            MangaTag("Video Games", "video-games", source),
            MangaTag("Villainess", "villainess", source),
            MangaTag("Virtual Reality", "virtual-reality", source),
            MangaTag("Voilence", "voilence", source),
            MangaTag("Webcomic", "webcomic", source),
            MangaTag("Wuxia", "wuxia", source),
            MangaTag("Zombies", "zombies", source),
        ),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
    )

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val title = doc.selectFirstOrThrow(".aqua-series-info__title").text()
        val thumbnail = doc.selectFirstOrThrow(".aqua-series-cover__img").requireSrc()
        val description = doc.selectFirst(".aqua-series-synopsis")?.html().orEmpty()
        val status = doc.selectFirst(".aqua-series-meta__status")?.text()
        val genres = doc.select(".aqua-series-genre-pill").map { it.text() }.toSet()
        val author = doc.selectFirst(".aqua-series-info__creator-value a")?.ownText()
        val artist = doc.selectFirst(".aqua-series-info__creator-value a")?.ownText()

        val tags = genres.mapTo(mutableSetOf()) { MangaTag(title = it, key = it, source = source) }
        val statusText = status?.lowercase().orEmpty()
        val state = when (statusText) {
            in ongoing -> MangaState.ONGOING
            in finished -> MangaState.FINISHED
            in abandoned -> MangaState.ABANDONED
            in paused -> MangaState.PAUSED
            else -> null
        }

        val chapters = getChapters(manga, doc)
        return manga.copy(
            title = title,
            coverUrl = thumbnail,
            description = description,
            tags = tags,
            state = state,
            authors = setOfNotNull(author, artist).filter { it.isNotBlank() }.toSet(),
            chapters = chapters,
        )
    }

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
        return doc.select(selectChapter).mapChapters(reversed = true) { i, el ->
            val a = el.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val name = el.selectFirstOrThrow(".aqua-ch-item__name").text()
            val dateText = el.selectFirst(".aqua-ch-item__time")?.text()
            MangaChapter(
                id = generateUid(href),
                url = href + stylePage,
                title = name,
                number = i + 1f,
                volume = 0,
                uploadDate = parseChapterDate(dateFormat, dateText),
                source = source,
                scanlator = null,
                branch = null,
            )
        }
    }
}
