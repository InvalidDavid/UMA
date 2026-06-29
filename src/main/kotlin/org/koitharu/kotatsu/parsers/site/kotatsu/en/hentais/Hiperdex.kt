package org.koitharu.kotatsu.parsers.site.kotatsu.en.hentais

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("HIPERDEXTEST", "Hiperdex (Test)", "en", ContentType.HENTAI)
internal class Hiperdex(context: MangaLoaderContext) : PagedMangaParser(
    context,
    MangaParserSource.HIPERDEXTEST,
    20
) {

    override val configKeyDomain =
        ConfigKey.Domain("hiperdex.com")

    private val userAgents = arrayOf(
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 Chrome/137 Mobile",
        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 Chrome/137 Mobile",
        "Mozilla/5.0 (Linux; Android 14; Xiaomi 14) AppleWebKit/537.36 Chrome/137 Mobile",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1"
    )

    private val sessionUserAgent by lazy {
        userAgents.random()
    }

    override fun intercept(
        chain: Interceptor.Chain
    ): Response {

        val request =
            chain.request()
                .newBuilder()
                .header("User-Agent", sessionUserAgent)
                .header("Referer", "https://hiperdex.com/")
                .header("Origin", "https://hiperdex.com")
                .build()

        return chain.proceed(request)
    }


    override val availableSortOrders =
        setOf(
            SortOrder.UPDATED,
            SortOrder.NEWEST,
            SortOrder.POPULARITY
        )


    override val filterCapabilities =
        MangaListFilterCapabilities(
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isYearSupported = false,
            isAuthorSearchSupported = false
        )

    private fun parseStatus(document: Document): MangaState? {

        val status =
            document
                .selectFirst(
                    "div.summary-heading:contains(Status) + div.summary-content"
                )
                ?.text()
                ?.lowercase()
                ?: return null


        return when {

            "completed" in status ||
                    "complete" in status ||
                    "finished" in status ->
                MangaState.FINISHED


            "ongoing" in status ||
                    "publishing" in status ->
                MangaState.ONGOING


            "hiatus" in status ||
                    "on hold" in status ||
                    "paused" in status ->
                MangaState.PAUSED


            "cancelled" in status ||
                    "canceled" in status ||
                    "dropped" in status ->
                MangaState.ABANDONED


            else ->
                null
        }
    }


    private fun parseDate(value: String): Long {

        return runCatching {

            SimpleDateFormat(
                "dd/MM/yyyy",
                Locale.US
            )
                .parse(value)
                ?.time
                ?: 0L

        }.getOrDefault(0L)
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter
    ): List<Manga> {

        val tags =
            filter.tags
                .map { it.key }


        val statuses =
            filter.states
                .mapNotNull {
                    when (it) {
                        MangaState.ONGOING -> "on-going"
                        MangaState.FINISHED -> "end"
                        MangaState.PAUSED -> "on-hold"
                        MangaState.ABANDONED -> "canceled"
                        else -> null
                    }
                }


        val hasFilter =
            tags.isNotEmpty() ||
                    statuses.isNotEmpty()


        val url =
            buildString {

                append("https://hiperdex.com/?")

                append("s=")
                append(filter.query ?: "")

                append("&post_type=wp-manga")

                append("&op=")
                append("&author=")
                append("&artist=")
                append("&release=")
                append("&adult=")


                // Multiple genre filters
                tags.forEach { tag ->

                    append("&genre[]=")
                    append(tag)
                }


                // Multiple state filters
                statuses.forEach { status ->

                    append("&status%5B%5D=")
                    append(status)
                }


                // Sorting only when no filters are selected
                if (!hasFilter) {

                    when (order) {

                        SortOrder.POPULARITY ->
                            append("&status=popular")


                        SortOrder.NEWEST ->
                            append("&sort=newest")


                        SortOrder.UPDATED ->
                            append("&sort=latest")


                        else -> Unit
                    }
                }


                if (page > 0) {

                    append("&paged=")
                    append(page + 1)
                }
            }


        val response =
            webClient.httpGet(url)


        val document =
            Jsoup.parse(
                response.body.string()
            )


        return document
            .select("#loop-content div.page-listing-item")
            .mapNotNull { item ->

                val link =
                    item.selectFirst(".post-title a")
                        ?: return@mapNotNull null


                val mangaUrl =
                    link.attr("href")
                        .trim()


                if (mangaUrl.isBlank()) {
                    return@mapNotNull null
                }


                val cover =
                    item.selectFirst("img")
                        ?.let {
                            it.attr("data-src")
                                .ifBlank {
                                    it.attr("src")
                                }
                        }


                Manga(
                    id = generateUid(mangaUrl),
                    title = link.text()
                        .cleanTitleIfNeeded(),
                    url = mangaUrl,
                    publicUrl = mangaUrl,
                    coverUrl = cover,
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    tags = emptySet(),
                    authors = emptySet(),
                    state = null,
                    contentRating = null,
                    source = source
                )
            }
    }

    override suspend fun getDetails(
        manga: Manga
    ): Manga {

        val response =
            webClient.httpGet(manga.url)


        val document =
            Jsoup.parse(
                response.body.string()
            )


        val chapters =
            document
                .select("li.wp-manga-chapter")
                .mapNotNull { item ->

                    val link =
                        item.selectFirst("a")
                            ?: return@mapNotNull null


                    val url =
                        link.attr("href")
                            .trim()


                    val name =
                        link.text()
                            .trim()


                    val number =
                        extractChapterNumber(name)
                            ?: return@mapNotNull null


                    MangaChapter(
                        id = generateUid(url),
                        title = name,
                        number = number.toFloat(),
                        volume = 0,
                        url = url,
                        scanlator = null,
                        uploadDate =
                            item.selectFirst(".chapter-release-date")
                                ?.text()
                                ?.let(::parseDate)
                                ?: 0L,
                        branch = null,
                        source = source
                    )
                }
                .sortedByDescending {
                    it.number
                }


        return manga.copy(
            title =
                document
                    .selectFirst(".post-title h1")
                    ?.text()
                    ?.cleanTitleIfNeeded()
                    ?: manga.title,

            description =
                document
                    .selectFirst(".summary__content")
                    ?.text()
                    ?: "",

            tags =
                document
                    .select(".genres-content a[href*='/genre/']")
                    .mapNotNull {

                        val title =
                            it.text()
                                .trim()

                        val key =
                            it.attr("href")
                                .substringAfter("/genre/")
                                .substringBefore("/")
                                .trim()


                        if (
                            title.isBlank() ||
                            key.isBlank()
                        ) {
                            null
                        } else {
                            MangaTag(
                                title = title,
                                key = key,
                                source = source
                            )
                        }
                    }
                    .toSet(),

            chapters = chapters,

            state = parseStatus(document)
        )
    }
    override suspend fun getPages(
        chapter: MangaChapter
    ): List<MangaPage> {

        val response =
            webClient.httpGet(chapter.url)


        val document =
            Jsoup.parse(
                response.body.string()
            )


        return document
            .select(
                "div.page-break:not([style*=display:none]) img"
            )
            .mapNotNull { image ->

                val url =
                    image.attr("src")
                        .ifBlank {
                            image.attr("data-src")
                        }
                        .trim()


                if (url.isBlank()) {
                    return@mapNotNull null
                }


                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source
                )
            }
    }
    override suspend fun getFilterOptions(): MangaListFilterOptions {

        return MangaListFilterOptions(
            availableTags = emptySet(),
            availableStates = setOf(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED
            ),
            availableContentRating = emptySet()
        )
    }

    private fun extractChapterNumber(
        name: String
    ): Double? {

        return Regex(
            """(?:chapter|chap|ch)\s*\.?\s*(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )
            .find(name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }


    private fun String.cleanTitleIfNeeded(): String {

        return replace(
            Regex(
                """^(?:\s*(?:\([^()]*\)|\{[^{}]*\}|\[[^]]*])\s*)+""",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
            .trim()
    }
}
