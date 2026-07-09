package org.koitharu.kotatsu.parsers.site.kotatsu.en.hentais

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.parsers.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet

@MangaSourceParser("MANGADISTRICT", "MangaDistrict", "en", ContentType.HENTAI)
internal class MangaDistrict(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGADISTRICT, "mangadistrict.com", pageSize = 30) {

    override val tagPrefix = "publication-genre/"
    override val withoutAjax: Boolean = true
    override val datePattern: String = "MMMM d, yyyy"
    override val stylePage: String = "?style=list"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

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
                title = a.text()
                    .replace(Regex("""\s*\(\d+\)"""), "")
                    .replace(Regex("""[^\x20-\x7E]"""), "")
                    .trim()
                    .toTitleCase(),
                source = source,
            )
        }
    }
}
