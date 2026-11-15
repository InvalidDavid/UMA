package tsuki.site.pt.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.attrAsRelativeUrl
import tsuki.util.generateUid
import tsuki.util.mapChapters
import tsuki.util.mapNotNullToSet
import tsuki.util.parseHtml
import tsuki.util.selectFirstOrThrow
import tsuki.util.src
import tsuki.util.toAbsoluteUrl
import tsuki.util.toRelativeUrl

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import tsuki.util.removeSuffix
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("HANAMIHEAVEN", "HanamiHeaven", "pt", ContentType.HENTAI)
internal class HanamiHeaven(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.HANAMIHEAVEN, "hanamiheaven.org", pageSize = 24) {

    override val sourceLocale: Locale = Locale.forLanguageTag("pt-BR")
    override val datePattern = "dd/MM/yyyy"
    override val postReq = false
    override val withoutAjax = true

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isYearSupported = true,
        )

    override val selectDesc = "#mch-work-synopsis"
    override val selectState = ".mch-work__status"

    private val archiveUrl = "https://$domain/todas-as-obras/"

    private fun metadataSelector(label: String, value: String) =
        ".mch-work__metadata div:has(> dt:contains($label)) $value"

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet(archiveUrl).parseHtml()
        return doc.select("input[name=genero[]]").mapNotNullToSet { input ->
            val slug = input.attr("value").takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null
            val name = input.parent()?.text()?.trim() ?: return@mapNotNullToSet null
            MangaTag(key = slug, title = name, source = source)
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = archiveUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("pagina", page.toString())

            val ordem = when (order) {
                SortOrder.UPDATED -> ""
                SortOrder.NEWEST -> "newest"
                SortOrder.ALPHABETICAL -> "alphabet"
                SortOrder.RATING, SortOrder.POPULARITY -> "rating"
                else -> ""
            }
            addQueryParameter("ordem", ordem)

            filter.query?.takeIf { it.isNotBlank() }?.let {
                addQueryParameter("busca", it.trim())
            }

            filter.tags.forEach { addQueryParameter("genero[]", it.key) }
            filter.tagsExclude.forEach { addQueryParameter("sem-genero[]", it.key) }

            filter.states.forEach { state ->
                val status = when (state) {
                    MangaState.ONGOING -> "on-going"
                    MangaState.FINISHED -> "end"
                    MangaState.ABANDONED -> "canceled"
                    MangaState.PAUSED -> "on-hold"
                    MangaState.UPCOMING -> "upcoming"
                    else -> null
                }
                status?.let { addQueryParameter("status", it) }
            }

            if (filter.year != 0) addQueryParameter("ano", filter.year.toString())
        }.build()

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        val elements = doc.select(
            "li.mch-catalogue-card:not(:has(.mch-catalogue-card__meta span:contains(Novel)))",
        )
        if (elements.isEmpty()) return emptyList()
        return elements.map { li ->
            val a = li.selectFirstOrThrow(".mch-catalogue-card__title a")
            val href = a.attrAsRelativeUrl("href")
            val img = li.selectFirst(".mch-catalogue-card__cover img")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = a.text().trim(),
                altTitles = emptySet(),
                authors = emptySet(),
                coverUrl = img?.src(),
                tags = emptySet(),
                rating = RATING_UNKNOWN,
                state = null,
                source = source,
                contentRating = ContentRating.ADULT,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val href = doc.selectFirst("head meta[property='og:url']")
            ?.attr("content")
            ?.toRelativeUrl(domain)
            ?: manga.url

        val title = doc.selectFirst("#mch-work-title")?.text()?.trim().orEmpty()
            .ifBlank { manga.title }
        val description = doc.selectFirst(selectDesc)?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val thumbnail = doc.selectFirst(".mch-work__cover img")?.src()
        val author = doc.selectFirst(metadataSelector("Autor", "a"))?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val artist = doc.selectFirst(metadataSelector("Artista", "a"))?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val altName = doc.selectFirst(metadataSelector("Nome alternativo", "dd"))
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val stateText = doc.selectFirst(selectState)?.text()?.lowercase()?.trim()
        val state = when (stateText) {
            "em andamento" -> MangaState.ONGOING
            "completa" -> MangaState.FINISHED
            "cancelada" -> MangaState.ABANDONED
            "em espera" -> MangaState.PAUSED
            "em breve" -> MangaState.UPCOMING
            else -> null
        }

        val genres = doc.select(metadataSelector("Gêneros", "a")).mapNotNullToSet { a ->
            val slug = a.attr("href").toHttpUrl().queryParameter("genero")
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null
            MangaTag(key = slug, title = a.text().trim(), source = source)
        }
        val tags = doc.select(metadataSelector("Tags", "a")).mapNotNullToSet { a ->
            val slug = a.attr("href").toHttpUrl().queryParameter("tag")
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null
            MangaTag(key = slug, title = a.text().trim(), source = source)
        }

        val chapters = async { loadChapters(href, doc) }.await()

        manga.copy(
            title = title,
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            coverUrl = thumbnail ?: manga.coverUrl,
            altTitles = setOfNotNull(altName),
            authors = setOfNotNull(author, artist),
            tags = (genres + tags).toSet(),
            description = description,
            state = state,
            chapters = chapters,
            contentRating = ContentRating.ADULT,
        )
    }

    override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> =
        parseChapterElements(doc, SimpleDateFormat(datePattern, sourceLocale))

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
        val url = mangaUrl.toAbsoluteUrl(domain).removeSuffix('/') + "/ajax/chapters/"
        val doc = webClient.httpPost(url, emptyMap()).parseHtml()
        return parseChapterElements(doc, SimpleDateFormat(datePattern, sourceLocale))
    }

    private fun parseChapterElements(doc: Document, dateFormat: SimpleDateFormat): List<MangaChapter> =
        doc.body().select(selectChapter).mapChapters(reversed = true) { i, li ->
            val a = li.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val link = href + stylePage
            val timeElement = li.selectFirst("time")
            val dateText = timeElement?.attr("datetime")?.takeIf { it.isNotBlank() }
                ?: li.selectFirst("a.c-new-tag")?.attr("title")
                ?: li.selectFirst(selectDate)?.text()
            val name = a.selectFirst("p")?.text() ?: a.ownText()
            MangaChapter(
                id = generateUid(href),
                title = name,
                number = i + 1f,
                volume = 0,
                url = link,
                uploadDate = parseChapterDateSafe(dateFormat, dateText),
                source = source,
                scanlator = null,
                branch = null,
            )
        }

    private fun parseChapterDateSafe(dateFormat: SimpleDateFormat, date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val trimmed = date.trim()
        for (pattern in listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
        )) {
            runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return sdf.parse(trimmed)?.time ?: 0L
            }
        }
        return parseChapterDate(dateFormat, trimmed)
    }
}
