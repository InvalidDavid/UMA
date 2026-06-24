package org.koitharu.kotatsu.parsers.site.kotatsu.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.removeSuffix
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import kotlinx.coroutines.awaitAll

@MangaSourceParser("MANGAGG", "MangaGG", "en")
internal class MangaGG(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGAGG, "mangagg.com") {

    override val tagPrefix = "genre/"
    override val datePattern = "MM/dd/yyyy"

    override suspend fun loadChapters(
        mangaUrl: String,
        document: Document,
    ): List<MangaChapter> = coroutineScope {

        val baseUrl = mangaUrl
            .toAbsoluteUrl(domain)
            .removeSuffix('/')

        val chapters = mutableListOf<MangaChapter>()

        var page = 1
        var finished = false

        while (!finished) {

            val jobs = (page until page + 5).map { currentPage ->
                async {
                    fetchChapterPage(
                        baseUrl,
                        currentPage,
                    )
                }
            }

            val results = jobs.awaitAll()

            finished = results.all {
                it.isEmpty()
            }

            results.forEach {
                chapters.addAll(it)
            }

            page += 5
        }

        chapters
            .distinctBy {
                it.id
            }
            .reversed()
            .mapIndexed { index, chapter ->
                chapter.copy(
                    number = index + 1f
                )
            }
    }


    private suspend fun fetchChapterPage(
        mangaUrl: String,
        page: Int,
    ): List<MangaChapter> {

        val url =
            "$mangaUrl/ajax/chapters/?t=$page"

        return runCatching {

            webClient
                .httpPost(
                    url,
                    emptyMap(),
                )
                .parseHtml()
                .let(::parseChapterPage)

        }.getOrDefault(emptyList())
    }


    private fun parseChapterPage(
        doc: Document,
    ): List<MangaChapter> {

        val dateFormat =
            SimpleDateFormat(
                datePattern,
                sourceLocale,
            )

        return doc
            .select(selectChapter)
            .map { li ->
                parseChapterItem(
                    li,
                    dateFormat,
                )
            }
    }


    private fun parseChapterItem(
        li: org.jsoup.nodes.Element,
        dateFormat: SimpleDateFormat,
    ): MangaChapter {

        val a = li.selectFirstOrThrow("a")

        val href = a.attrAsRelativeUrl("href")

        val dateText =
            li.selectFirst("a.c-new-tag")
                ?.attr("title")
                ?: li.selectFirst(selectDate)
                    ?.text()

        val name =
            a.selectFirst("p")
                ?.text()
                ?: a.ownText()

        return MangaChapter(
            id = generateUid(href),
            title = name,
            number = 0f,
            volume = 0,
            url = href + stylePage,
            uploadDate = parseChapterDate(
                dateFormat,
                dateText,
            ),
            source = source,
            scanlator = null,
            branch = null,
        )
    }
}