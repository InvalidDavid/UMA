package tsuki.site.fr

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.SortOrder

import tsuki.util.extractChapterNumber
import tsuki.util.generateUid
import tsuki.util.parseHtml

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.ZoneId
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANGASORIGINESFR", "Mangas-Origines.fr", "fr")
internal class MangasOriginesFr(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MANGASORIGINESFR, "mangas-origines.fr") {

    override val datePattern = "dd/MM/yyyy"
    override val tagPrefix = "manga-genres/"
    override val listUrl = "oeuvre/"

    override val availableSortOrders: Set<SortOrder> = setOf(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
//        availableContentTypes = EnumSet.of(
//            ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA
//        ),
        availableStates = EnumSet.of(
            MangaState.ONGOING, MangaState.FINISHED
        ),
    )

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val slug = manga.publicUrl.toMangaSlug()
        val fullUrl = "https://$domain/oeuvre/$slug/"
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val title = doc.selectFirst("h1.ori-sr-title")?.text() ?: manga.title
        val cover = doc.selectFirst("div.ori-sr-cover img")?.attr("abs:src") ?: manga.coverUrl.orEmpty()
        val description = buildString {
            doc.select("div.ori-sr-syn-texte p").eachText().forEach { appendLine(it) }
        }.trim()

        val infos = doc.select("div.ori-sr-infos dt").associate { dt ->
            dt.text().lowercase(Locale.FRENCH) to dt.nextElementSibling()?.text().orEmpty()
        }

        val state = when (infos["statut"]?.lowercase(Locale.FRENCH)) {
            "en cours" -> MangaState.ONGOING
            "terminé" -> MangaState.FINISHED
            "en pause" -> MangaState.PAUSED
            "abandonné", "annulé" -> MangaState.ABANDONED
            else -> null
        }

        val altTitle = infos["nom alternatif"]?.takeIf { it.isNotBlank() }
        val authors = listOfNotNull(
            infos["auteur"]?.takeIf { it.isNotBlank() },
            infos["scénario"]?.takeIf { it.isNotBlank() },
            infos["artiste"]?.takeIf { it.isNotBlank() },
            infos["dessin"]?.takeIf { it.isNotBlank() }
        ).distinct().toSet()

        val tags = doc.select("div.ori-sr-genres a.ori-sr-genre").map {
            MangaTag(it.text(), it.text(), source)
        }.toSet()

        val chaptersDeferred = async { fetchChapters(slug) }

        manga.copy(
            url = slug,
            title = title,
            altTitles = setOfNotNull(altTitle),
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
            "https://$domain/oeuvre/$slug/ajax/chapters/",
            emptyMap()
        ).parseHtml()
        return doc.select("div.ori-chl-row").map { element ->
            val link = element.selectFirst("a.ori-chl-corps")!!
            val href = link.attr("href")
            val name = element.selectFirst("span.ori-chl-nom")?.text() ?: link.text()
            val dateText = element.selectFirst("span.ori-chl-date")?.text()
            MangaChapter(
                id = generateUid(href.toChapterSlug()),
                title = name,
                number = name.extractChapterNumber(),
                volume = 0,
                url = href.toChapterSlug(),
                uploadDate = parseChapterDate(dateText),
                source = source,
                scanlator = null,
                branch = null,
            )
        }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = "https://$domain/oeuvre/${chapter.url}/"
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

    private fun String.toMangaSlug(): String = trimEnd('/').substringAfterLast("/").substringBefore("?")

    private fun String.toChapterSlug(): String {
        val parts = split("/").filter { it.isNotBlank() }
        return if (parts.size >= 2) parts.takeLast(2).joinToString("/") else this
    }

    private fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val regex = Regex("""(\d{1,2})\s+(\p{L}+)\.?\s+(\d{4})""")
        val match = regex.find(date) ?: return 0L
        val (day, month, year) = match.destructured
        val monthNumber = monthNumber(month) ?: return 0L
        return try {
            LocalDate.of(year.toInt(), monthNumber, day.toInt())
                .atStartOfDay(ZoneId.of("Europe/Paris"))
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) { 0L }
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
}
