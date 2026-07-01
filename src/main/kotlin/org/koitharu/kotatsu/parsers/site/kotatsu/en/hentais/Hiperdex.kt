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
import org.koitharu.kotatsu.parsers.network.UserAgents

@MangaSourceParser("HIPERDEX", "Hiperdex", "en", ContentType.HENTAI)
internal class Hiperdex(context: MangaLoaderContext) : PagedMangaParser(
    context,
    MangaParserSource.HIPERDEX,
    20
) {

    override val configKeyDomain = ConfigKey.Domain("hiperdex.com")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.KOTATSU)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun intercept(
        chain: Interceptor.Chain
    ): Response {
        val request =
            chain.request()
                .newBuilder()
                .header(
                    "Referer",
                    "https://hiperdex.com/"
                )
                .header(
                    "Origin",
                    "https://hiperdex.com"
                )
                .build()

        return chain.proceed(request)
    }

    override val availableSortOrders =
        setOf(
            SortOrder.UPDATED,
            SortOrder.NEWEST,
            SortOrder.POPULARITY,
            SortOrder.RATING
        )


    override val filterCapabilities =
        MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
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
                            append("&m_orderby=trending")
                        SortOrder.UPDATED ->
                            append("&m_orderby=latest")
                        SortOrder.NEWEST ->
                            append("&m_orderby=new-manga")
                        SortOrder.RATING ->
                            append("&m_orderby=rating")
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
                .sortedBy {
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

            tags = document
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
        val tags = setOf(
            MangaTag("Action", "action", source),
            MangaTag("Adult", "adult", source),
            MangaTag("Adventure", "adventure", source),
            MangaTag("Age Gap", "age-gap", source),
            MangaTag("Aliens", "aliens", source),
            MangaTag("Anthology", "anthology", source),
            MangaTag("Campus", "campus", source),
            MangaTag("Childhood Friends", "childhood-friends", source),
            MangaTag("Comedy", "comedy", source),
            MangaTag("Crime", "crime", source),
            MangaTag("Crossdressing", "crossdressing", source),
            MangaTag("Dance", "dance", source),
            MangaTag("Delinquents", "delinquents", source),
            MangaTag("Demons", "demons", source),
            MangaTag("Doujinshi", "doujinshi", source),
            MangaTag("Drama", "drama", source),
            MangaTag("Ecchi", "ecchi", source),
            MangaTag("Fantasy", "fantasy", source),
            MangaTag("Fetish", "fetish", source),
            MangaTag("Furry", "furry", source),
            MangaTag("Gender Bender", "gender-bender", source),
            MangaTag("Ghosts", "ghosts", source),
            MangaTag("Gore", "gore", source),
            MangaTag("Gyaru", "gyaru", source),
            MangaTag("Harem", "harem", source),
            MangaTag("Hentai", "hentai", source),
            MangaTag("Heroes", "heroes", source),
            MangaTag("Historical", "historical", source),
            MangaTag("Horror", "horror", source),
            MangaTag("Isekai", "isekai", source),
            MangaTag("Josei", "josei", source),
            MangaTag("Mafia", "mafia", source),
            MangaTag("Magic", "magic", source),
            MangaTag("Manga", "manga", source),
            MangaTag("Manhwa", "manhwa", source),
            MangaTag("Martial Arts", "martial-arts", source),
            MangaTag("Mature", "mature", source),
            MangaTag("Mecha", "mecha", source),
            MangaTag("Medical", "medical", source),
            MangaTag("Military", "military", source),
            MangaTag("Monster Girls", "monster-girls", source),
            MangaTag("Murim", "murim", source),
            MangaTag("Music", "music", source),
            MangaTag("Mystery", "mystery", source),
            MangaTag("Ninja", "ninja", source),
            MangaTag("Office Workers", "office-workers", source),
            MangaTag("Oneshot", "oneshot", source),
            MangaTag("Police", "police", source),
            MangaTag("Post-Apocalyptic", "post-apocalyptic", source),
            MangaTag("Psychological", "psychological", source),
            MangaTag("Regression", "regression", source),
            MangaTag("Reincarnation", "reincarnation", source),
            MangaTag("Revenge", "revenge", source),
            MangaTag("Romance", "romance", source),
            MangaTag("School Life", "school-life", source),
            MangaTag("Sci-fi", "sci-fi", source),
            MangaTag("Secret Relationship", "secret-relationship", source),
            MangaTag("Seinen", "seinen", source),
            MangaTag("Shoujo", "shoujo", source),
            MangaTag("Shoujo Ai", "shoujo-ai", source),
            MangaTag("Shounen", "shounen", source),
            MangaTag("Slice of Life", "slice-of-life", source),
            MangaTag("Smut", "smut", source),
            MangaTag("Sports", "sports", source),
            MangaTag("Supernatural", "supernatural", source),
            MangaTag("Survival", "survival", source),
            MangaTag("Suspense", "suspense", source),
            MangaTag("Thriller", "thriller", source),
            MangaTag("Time Travel", "time-travel", source),
            MangaTag("Tower", "tower", source),
            MangaTag("Tragedy", "tragedy", source),
            MangaTag("Uncensored", "uncensored", source),
            MangaTag("Video Games", "video-games", source),
            MangaTag("Villainess", "villainess", source),
            MangaTag("Violence", "violence", source),
            MangaTag("Virtual Reality", "virtual-reality", source),
            MangaTag("Wuxia", "wuxia", source),
            MangaTag("Yaoi", "yaoi", source),
            MangaTag("Yuri", "yuri", source),
        )

        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = setOf(
                MangaState.ONGOING,
                MangaState.FINISHED,
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
