package org.koitharu.kotatsu.parsers.site.kotatsu.en.hentais

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.parsers.MadaraParser
import org.koitharu.kotatsu.parsers.site.tachiyomi.parsers.Madara.Genre
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat

@MangaSourceParser("MANGADISTRICT", "MangaDistrict", "en", ContentType.HENTAI)
internal class MangaDistrict(context: MangaLoaderContext):
    MadaraParser(context, MangaParserSource.MANGADISTRICT, "mangadistrict.com") {

    override val withoutAjax: Boolean = true
    override val datePattern: String = "MMMM d, yyyy"
    override val stylePage: String = "?style=list"

    override fun parseMangaList(doc: Document): List<Manga> {
        return super.parseMangaList(doc).map { manga ->
            manga.copy(
                title = manga.title.cleanTitleIfNeeded()
            )
        }
    }

    private fun String.cleanTitleIfNeeded(): String {
        return this
            .replace(Regex("""\s*\(.*?\)\s*"""), "") // remove brackets
            .replace(Regex("""\s*\[.*?]\s*"""), "")
            .trim()
    }

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/series/").parseHtml()
        val elements = doc.select("header ul.second-menu li a, div.genres_wrap ul li a")

        return elements.mapNotNullToSet { a ->
            val href = a.attr("href")
                .removeSuffix("/")
                .substringAfterLast(tagPrefix, "")

            if (href.isBlank()) return@mapNotNullToSet null

            MangaTag(
                key = href,
                title = a.text().toTitleCase(),
                source = source,
            )
        }
    }

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
        val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

        val chapters = doc.body()
            .select("li.wp-manga-chapter")
            .mapNotNull { li ->
                val a = li.selectFirstOrThrow("a")
                val href = a.attrAsRelativeUrl("href")
                val link = href + stylePage

                val name = a.selectFirst("p")?.text()
                    ?: a.ownText()

                val dateText =
                    li.selectFirst("a.c-new-tag")?.attr("title")
                        ?: li.selectFirst("span.chapter-release-date i")?.text()

                MangaChapter(
                    id = generateUid(href),
                    title = name,
                    number = extractChapterNumber(name) ?: 0f,
                    volume = 0,
                    url = link,
                    uploadDate = parseChapterDate(dateFormat, dateText),
                    scanlator = null,
                    branch = null,
                    source = source,
                )
            }
            .sortedWith(
                compareBy<MangaChapter> { it.number }
                    .thenBy { it.title }
            )

        return chapters
    }

    private fun extractChapterNumber(name: String): Float? {
        val match = Regex(
            """(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ).find(name)

        return match
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val base = super.getDetails(manga)

        return base.copy(
            title = doc.selectFirst("h1")?.text() ?: base.title,
            tags = doc.select("div.genres-content a").mapToSet { a ->
                MangaTag(
                    key = a.attr("href")
                        .removeSuffix("/")
                        .substringAfterLast('/'),
                    title = a.text().toTitleCase(),
                    source = source,
                )
            }
        )
    }

    private var genresList = listOf(
        Genre("3D", "3d"),
        Genre("Action", "action"),
        Genre("Adapted to Anime", "adapted-to-anime"),
        Genre("Adventure", "adventure"),
        Genre("Aliens", "aliens"),
        Genre("Animal Characteristics", "animal-characteristics"),
        Genre("Based on Another Work", "based-on-another-work"),
        Genre("BL", "bl"),
        Genre("BL Uncensored", "bl-uncensored"),
        Genre("Borderline H", "borderline-h"),
        Genre("Cohabitation", "cohabitation"),
        Genre("Collection of Stories", "collection-of-stories"),
        Genre("Comedy", "comedy"),
        Genre("Comics", "comics"),
        Genre("Cooking", "cooking"),
        Genre("Coworkers", "coworkers"),
        Genre("Crime", "crime"),
        Genre("Crossdressing", "crossdressing"),
        Genre("Delinquents", "delinquents"),
        Genre("Demons", "demons"),
        Genre("Detectives", "detectives"),
        Genre("Doujinshi", "doujinshi"),
        Genre("Drama", "drama"),
        Genre("Ecchi", "ecchi"),
        Genre("Explicit Sex", "explicit-sex"),
        Genre("Fantasy", "fantasy"),
        Genre("Fetish", "fetish"),
        Genre("Full Color", "full-color"),
        Genre("Gender Bender", "gender-bender"),
        Genre("Ghosts", "ghosts"),
        Genre("GL", "gl"),
        Genre("Gyaru", "gyaru"),
        Genre("Harem", "harem"),
        Genre("Historical", "historical"),
        Genre("Horror", "horror"),
        Genre("Incest", "incest"),
        Genre("Isekai", "isekai"),
        Genre("Japanese Webtoons", "japanese-webtoons"),
        Genre("Josei", "josei"),
        Genre("Light Novels", "light-novels"),
        Genre("Mafia", "mafia"),
        Genre("Magic", "magic"),
        Genre("Magical Girl", "magical-girl"),
        Genre("Manhua", "manhua"),
        Genre("Manhwa", "manhwa"),
        Genre("Martial Arts", "martial-arts"),
        Genre("Mature Romance", "mature-romance"),
        Genre("Mecha", "mecha"),
        Genre("Medical", "medical"),
        Genre("Military", "military"),
        Genre("Monster Girls", "monster-girls"),
        Genre("Monsters", "monsters"),
        Genre("Music", "music"),
        Genre("Mystery", "mystery"),
        Genre("Ninja", "ninja"),
        Genre("Nudity", "nudity"),
        Genre("One Shot", "one-shot"),
        Genre("Person in a Strange World", "person-in-a-strange-world"),
        Genre("Police", "police"),
        Genre("Psychological", "psychological"),
        Genre("Reincarnation", "reincarnation"),
        Genre("Reverse Harem", "reverse-harem"),
        Genre("Romance", "romance"),
        Genre("Salaryman", "salaryman"),
        Genre("Samurai", "samurai"),
        Genre("School Life", "school-life"),
        Genre("Sci Fi", "sci-fi"),
        Genre("Seinen", "seinen"),
        Genre("Sexual Abuse", "sexual-abuse"),
        Genre("Sexual Content", "sexual-content"),
        Genre("Shoujo", "shoujo"),
        Genre("Shoujo-ai", "shoujo-ai"),
        Genre("Shounen", "shounen"),
        Genre("Shounen-ai", "shounen-ai"),
        Genre("Siblings", "siblings"),
        Genre("Slice of Life", "slice-of-life"),
        Genre("Smut", "smut"),
        Genre("Sports", "sports"),
        Genre("Summoned Into Another World", "summoned-into-another-world"),
        Genre("Superheroes", "superheroes"),
        Genre("Supernatural", "supernatural"),
        Genre("Survival", "survival"),
        Genre("Thriller", "thriller"),
        Genre("Time Travel", "time-travel"),
        Genre("Transfer Students", "transfer-students"),
        Genre("Uncensored", "uncensored"),
        Genre("Vampires", "vampires"),
        Genre("Violence", "violence"),
        Genre("Virtual Reality", "virtual-reality"),
        Genre("Web Novels", "web-novels"),
        Genre("Webtoons", "webtoons"),
        Genre("Western", "western"),
        Genre("Work Life", "work-life"),
        Genre("Yaoi", "yaoi"),
        Genre("Yuri", "yuri"),
        Genre("Zombies", "zombies"),
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions(
            availableTags = genresList.mapToSet { genre ->
                MangaTag(
                    key = genre.id,
                    title = genre.name,
                    source = source
                )
            }
        )
}
