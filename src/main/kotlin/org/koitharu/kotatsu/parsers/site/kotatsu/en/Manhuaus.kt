package org.koitharu.kotatsu.parsers.site.kotatsu.en

import androidx.collection.arraySetOf
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.parsers.MadaraParser
import okhttp3.Interceptor

@MangaSourceParser("MANHUAUS", "Manhuaus", "en")
internal class Manhuaus(context: MangaLoaderContext):
    MadaraParser(context, MangaParserSource.MANHUAUS, "manhuaus.com") {

    override val postReq = true

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = true,
        isSearchSupported = true,
        isAuthorSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isYearSupported = false,
    )

    override fun intercept(chain: Interceptor.Chain) = chain.proceed(
        chain.request().newBuilder()
            .header("Referer", "https://$domain/")
            .build()
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
