package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.exception.ParseException

import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.parseHtml

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.ZoneId
import java.util.EnumSet
import java.util.Locale

abstract class OriginesParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    pageSize: Int = 30,
) : PagedMangaParser(context, source, pageSize) {

    override val configKeyDomain = ConfigKey.Domain(domain)

    /** Path prefix for series, e.g. "manga" or "oeuvre". */
    protected abstract val mangaPath: String

    /** Legacy paths used by older URLs. */
    protected open val legacyMangaPaths: Set<String> = emptySet()

    /** Genres as label to slug. */
    protected abstract val genres: List<Pair<String, String>>

    /** Origins as label to slug (optional). */
    protected open val origins: List<Pair<String, String>> = emptyList()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    private val knownPaths: Set<String> by lazy { legacyMangaPaths + mangaPath }

    private fun String.pathSegments(): List<String> {
        val path = if (startsWith("http")) {
            toHttpUrlOrNull()?.encodedPath ?: this
        } else this
        return path.substringBefore('?')
            .substringBefore('#')
            .split('/')
            .filter { it.isNotBlank() && it !in knownPaths }
    }

    private fun String.toMangaSlug(): String = pathSegments().firstOrNull() ?: this

    private fun String.toChapterSlug(): String = pathSegments().take(2).joinToString("/")

    private suspend fun getCatalogue(
        page: Int,
        query: String = "",
        genres: String = "",
        status: String = "tous",
        rating: String = "0",
        origin: String = "",
        sort: String = "recents",
        chapterMin: String = "0",
        chapterMax: String = "0",
    ): List<Manga> {
        val form = mapOf(
            "action" to "madara_child_catalogue",
            "s" to query,
            "genres" to genres,
            "statut" to status,
            "note" to rating,
            "origine" to origin,
            "tri" to sort,
            "chmin" to chapterMin,
            "chmax" to chapterMax,
            "page" to page.toString(),
            "auteur" to "",
            "artiste" to "",
            "annee" to "",
        )

        val response = webClient.httpPost("https://$domain/wp-admin/admin-ajax.php", form)
        val json = response.parseJson()
        val html = json.getJSONObject("data").optString("html")
        if (html.isEmpty()) return emptyList()

        val doc = Jsoup.parseBodyFragment(html)
        return doc.select("a.ori-card:has(span.ori-card-title)").map { element ->
            val href = element.attr("href").toMangaSlug()
            val title = element.selectFirst("span.ori-card-title")!!.text()
            val cover = element.selectFirst("img")?.absUrl("src")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = "https://$domain/$mangaPath/$href/",
                title = title,
                altTitles = emptySet(),
                authors = emptySet(),
                coverUrl = cover,
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                state = null,
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sort = when (order) {
            SortOrder.POPULARITY -> "populaire"
            SortOrder.RATING -> "notes"
            SortOrder.ALPHABETICAL -> "az"
            else -> "recents"
        }

        val genreTags = filter.tags.filter { !it.key.startsWith(ORIGIN_PREFIX) }
        val originTags = filter.tags.filter { it.key.startsWith(ORIGIN_PREFIX) }
        val genreParam = genreTags.joinToString(",") { it.key }
        val originParam = originTags.joinToString(",") { it.key.removePrefix(ORIGIN_PREFIX) }

        val statusParam = filter.states.firstOrNull()?.let { state ->
            when (state) {
                MangaState.ONGOING -> "en-cours"
                MangaState.FINISHED -> "termine"
                else -> "tous"
            }
        } ?: "tous"

        return getCatalogue(
            page = page,
            query = filter.query.orEmpty(),
            genres = genreParam,
            status = statusParam,
            origin = originParam,
            sort = sort,
        )
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = buildSet {
            genres.forEach { (title, slug) ->
                add(MangaTag(key = slug, title = title, source = source))
            }
        },
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
        ),
    )


    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val slug = manga.url.toMangaSlug()
        val fullUrl = "https://$domain/$mangaPath/$slug/"
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val infos = doc.select("div.ori-sr-infos dt").associate { dt ->
            dt.text().lowercase(Locale.FRENCH) to dt.nextElementSibling()?.text().orEmpty()
        }

        val title = doc.selectFirst("h1.ori-sr-title")?.text() ?: manga.title
        val cover = doc.selectFirst("div.ori-sr-cover img")?.absUrl("src") ?: manga.coverUrl.orEmpty()

        val description = buildString {
            doc.select("div.ori-sr-syn-texte p").eachText().forEach { appendLine(it) }
            infos["nom alternatif"]?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                append("Nom alternatif: ", it)
            }
        }.trim()

        val authors = listOfNotNull(
            infos["auteur"]?.takeIf { it.isNotBlank() },
            infos["scénario"]?.takeIf { it.isNotBlank() },
            infos["artiste"]?.takeIf { it.isNotBlank() },
            infos["dessin"]?.takeIf { it.isNotBlank() }
        ).distinct().toSet()

        val tags = doc.select("div.ori-sr-genres a.ori-sr-genre").asSequence().map { it.text() }
            .plus(infos["type"].orEmpty())
            .filter { it.isNotBlank() }
            .map { MangaTag(it, it, source) }
            .toSet()

        val state = when (infos["statut"]?.lowercase(Locale.FRENCH)) {
            "en cours" -> MangaState.ONGOING
            "terminé" -> MangaState.FINISHED
            "en pause" -> MangaState.PAUSED
            "abandonné", "annulé" -> MangaState.ABANDONED
            else -> null
        }

        val chaptersDeferred = async { fetchChapters(slug) }

        manga.copy(
            url = slug,
            publicUrl = fullUrl,
            title = title,
            altTitles = setOfNotNull(infos["nom alternatif"]?.takeIf { it.isNotBlank() }),
            rating = doc.selectFirst("span.total_votes")?.ownText()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
            coverUrl = cover,
            description = description,
            authors = authors,
            tags = tags,
            state = state,
            chapters = chaptersDeferred.await(),
        )
    }

    private suspend fun fetchChapters(slug: String): List<MangaChapter> {
        val doc = webClient.httpPost(
            "https://$domain/$mangaPath/$slug/ajax/chapters/",
            emptyMap()
        ).parseHtml()
        return doc.select("div.ori-chl-row").map { element ->
            val link = element.selectFirst("a.ori-chl-corps")!!
            val href = link.attr("href").toChapterSlug()
            val name = element.selectFirst("span.ori-chl-nom")?.text() ?: link.text()
            val dateText = element.selectFirst("span.ori-chl-date")?.attr("title")
                ?: element.selectFirst("span.ori-chl-date")?.text()
            MangaChapter(
                id = generateUid(href),
                url = href,
                title = name,
                number = name.extractChapterNumber(),
                volume = 0,
                uploadDate = parseChapterDate(dateText),
                scanlator = null,
                branch = null,
                source = source,
            )
        }.reversed()
    }
    
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = "https://$domain/$mangaPath/${chapter.url}/"
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select("div.reading-content img.wp-manga-chapter-img").mapIndexed { _, img ->
            val url = when {
                img.hasAttr("data-src") -> img.absUrl("data-src").trim()
                else -> img.absUrl("src").trim()
            }
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
    
    private fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val regex = Regex("""(\d{1,2})\s+(\p{L}+)\.?(?:\s+(\d{4}))?""")
        val match = regex.find(date) ?: return 0L
        val (day, month, year) = match.destructured
        val monthNumber = monthNumber(month) ?: return 0L
        val today = LocalDate.now(ZoneId.of("Europe/Paris"))
        return runCatching {
            var chapterDate = LocalDate.of(
                year.toIntOrNull() ?: today.year,
                monthNumber,
                day.toInt()
            )
            if (year.isEmpty() && chapterDate.isAfter(today)) {
                chapterDate = chapterDate.minusYears(1)
            }
            chapterDate.atStartOfDay(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun monthNumber(month: String): Int? {
        val name = month.lowercase(Locale.FRENCH)
        return when {
            name.startsWith("jan") -> 1
            name.startsWith("fev") || name.startsWith("fév") -> 2
            name.startsWith("mar") -> 3
            name.startsWith("avr") -> 4
            name.startsWith("mai") -> 5
            name.startsWith("juin") -> 6
            name.startsWith("juil") -> 7
            name.startsWith("ao") -> 8
            name.startsWith("sep") -> 9
            name.startsWith("oct") -> 10
            name.startsWith("nov") -> 11
            name.startsWith("dec") || name.startsWith("déc") -> 12
            else -> null
        }
    }
    
    private fun okhttp3.Response.parseJson(): JSONObject {
        val body = body?.string() ?: throw ParseException("Empty response body", request.url.toString())
        return JSONObject(body)
    }

    companion object {
        private const val ORIGIN_PREFIX = "origin:"
    }
}
