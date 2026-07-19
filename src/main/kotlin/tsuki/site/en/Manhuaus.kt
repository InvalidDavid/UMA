package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.MangaListFilterCapabilities
import tsuki.parsers.MadaraParser

import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag

import androidx.collection.arraySetOf

@MangaSourceParser("MANHUAUS", "Manhuaus", "en")
internal class Manhuaus(context: MangaLoaderContext):
    MadaraParser(context, MangaParserSource.MANHUAUS, "manhuaus.com") {

    override val postReq = false

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchSupported = true,
        isAuthorSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isYearSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = availableTags(),
    )

    private fun availableTags() = arraySetOf(
        MangaTag("Action", "action", source),
        MangaTag("Adult", "adult", source),
        MangaTag("Adventure", "adventure", source),
        MangaTag("Anime", "anime", source),
        MangaTag("Cartoon", "cartoon", source),
        MangaTag("Comedy", "comedy", source),
        MangaTag("Comic", "comic", source),
        MangaTag("Cooking", "cooking", source),
        MangaTag("Detective", "detective", source),
        MangaTag("Doujinshi", "doujinshi", source),
        MangaTag("Drama", "drama", source),
        MangaTag("Ecchi", "ecchi", source),
        MangaTag("Fantasy", "fantasy", source),
        MangaTag("Gender Bender", "gender-bender", source),
        MangaTag("Harem", "harem", source),
        MangaTag("Historical", "historical", source),
        MangaTag("Horror", "horror", source),
        MangaTag("Josei", "josei", source),
        MangaTag("Live Action", "live-action", source),
        MangaTag("Manga", "manga", source),
        MangaTag("Manhua", "manhua", source),
        MangaTag("Manhwa", "manhwa", source),
        MangaTag("Martial Arts", "martial-arts", source),
        MangaTag("Mature", "mature", source),
        MangaTag("Mecha", "mecha", source),
        MangaTag("Mystery", "mystery", source),
        MangaTag("One Shot", "one-shot", source),
        MangaTag("Psychological", "psychological", source),
        MangaTag("Romance", "romance", source),
        MangaTag("School Life", "school-life", source),
        MangaTag("Sci-fi", "sci-fi", source),
        MangaTag("Seinen", "seinen", source),
        MangaTag("Shoujo", "shoujo", source),
        MangaTag("Shoujo Ai", "shoujo-ai", source),
        MangaTag("Shounen", "shounen", source),
        MangaTag("Shounen Ai", "shounen-ai", source),
        MangaTag("Slice of Life", "slice-of-life", source),
        MangaTag("Smut", "smut", source),
        MangaTag("Soft Yaoi", "soft-yaoi", source),
        MangaTag("Soft Yuri", "soft-yuri", source),
        MangaTag("Sports", "sports", source),
        MangaTag("Supernatural", "supernatural", source),
        MangaTag("Tragedy", "tragedy", source),
        MangaTag("Webtoon", "webtoon", source),
        MangaTag("Yaoi", "yaoi", source),
        MangaTag("Yuri", "yuri", source),
    )
}