package tsuki.site.es

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
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

import tsuki.util.attrAsRelativeUrl
import tsuki.util.generateUid
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded

import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MANGAONI", "MangaOni", "es")
internal class MangaOni(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAONI, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("manga-oni.com")

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val hideNsfwKey = ConfigKey.ShowSuspiciousContent(false)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(hideNsfwKey)
    }

    override fun getRequestHeaders(): okhttp3.Headers {
        val builder = super.getRequestHeaders().newBuilder()
            .set("Referer", "https://$domain/")
            .set("Origin", "https://$domain")
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .set("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .set("User-agent", config[userAgentKey])
        return builder.build()
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,   // visitas
        SortOrder.UPDATED,      // recientes
        SortOrder.ALPHABETICAL, // nombre
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = GENRES.map { (_, name, id) ->
            MangaTag(key = id, title = name, source = source)
        }.toSet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,   // "En desarrollo" -> 1
            MangaState.FINISHED,  // "Completo" -> 0
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,   // 0
            ContentType.MANHWA,  // 1
            ContentType.OTHER,   // "One Shot" 2
            ContentType.MANHUA,  // 3
            ContentType.NOVEL,   // 4
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()

        if (query.isNotEmpty()) {
            return searchManga(query, page)
        }

        val url = when (order) {
            SortOrder.UPDATED -> "https://$domain/recientes?p=$page"
            else -> buildDirectoryUrl(page, order, filter)
        }

        val doc = webClient.httpGet(url).parseHtml()
        return when (order) {
            SortOrder.UPDATED -> parseLatestListing(doc)
            else -> parseDirectoryListing(doc)
        }
    }

    private fun buildDirectoryUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        return StringBuilder("https://$domain/directorio?").apply {
            append("genero=")
            append(filter.tags.firstOrNull()?.key ?: "false")

            append("&estado=")
            append(
                filter.states.firstOrNull()?.let {
                    when (it) {
                        MangaState.ONGOING -> "1"
                        MangaState.FINISHED -> "0"
                        else -> null
                    }
                } ?: "false"
            )

            append("&tipo=")
            append(
                filter.types.firstOrNull()?.let {
                    when (it) {
                        ContentType.MANGA -> "0"
                        ContentType.MANHWA -> "1"
                        ContentType.OTHER -> "2"
                        ContentType.MANHUA -> "3"
                        ContentType.NOVEL -> "4"
                        else -> null
                    }
                } ?: "false"
            )

            append("&adulto=")
            if (config[hideNsfwKey]) {
                append("0")
            } else {
                when (filter.contentRating.oneOrThrowIfMany()) {
                    ContentRating.ADULT -> append("1")
                    ContentRating.SAFE -> append("0")
                    else -> append("false")
                }
            }

            append("&filtro=")
            append(
                when (order) {
                    SortOrder.POPULARITY -> "visitas"
                    SortOrder.ALPHABETICAL -> "nombre"
                    else -> "visitas"
                }
            )
            append("&orden=")
            append(if (order == SortOrder.ALPHABETICAL) "asc" else "desc")

            append("&p=$page")
        }.toString()
    }

    private suspend fun searchManga(query: String, page: Int): List<Manga> {
        val url = "https://$domain/buscar?q=${query.urlEncoded()}&p=$page"
        val doc = webClient.httpGet(url).parseHtml()
        return parseSearchResult(doc)
    }

    private fun parseLatestListing(doc: org.jsoup.nodes.Document): List<Manga> {
        return doc.select("div._1bJU3").map { element ->
            val a = element.selectFirst("a._2dU-m") ?: return@map null
            val href = a.attrAsRelativeUrl("href")
            val cover = a.selectFirst("img")?.attr("data-src") ?: a.selectFirst("img")?.attr("src")
            val title = element.selectFirst("a[data-test=latest-update-name]")?.text() ?: return@map null

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = cover?.ifEmpty { null },
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }.filterNotNull()
    }

    private fun parseDirectoryListing(doc: org.jsoup.nodes.Document): List<Manga> {
        return doc.select("#article-div a").map { element ->
            val href = element.attrAsRelativeUrl("href")
            val cover = element.select("img").attr("abs:data-src").ifEmpty {
                element.select("img").attr("abs:src")
            }
            val title = element.select("div:eq(1)").text()
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = cover.ifEmpty { null },
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    private fun parseSearchResult(doc: org.jsoup.nodes.Document): List<Manga> {
        return doc.select("#article-div > div").map { element ->
            val a = element.selectFirst("a") ?: return@map null
            val href = a.attrAsRelativeUrl("href")
            val title = a.text().ifEmpty { element.select("a").lastOrNull()?.text() } ?: return@map null
            val cover = element.select("img").attr("abs:src")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = cover.ifEmpty { null },
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }.filterNotNull()
    }

    // ───────────────────────── DETAILS ────────────────────────────
    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()

        val title = doc.selectFirst("h1")?.text() ?: manga.title
        val cover = doc.select("img[src*=cover]").attr("abs:src")
        val description = doc.select("div#sinopsis").lastOrNull()?.ownText()
        val author = doc.select("div#info-i").text().let { info ->
            if (info.contains("Autor", true)) {
                info.substringAfter("Autor:").substringBefore("Fecha:").trim()
            } else null
        } ?: "N/A"

        val genres = doc.select("div#categ a").eachText()
        val tags = genres.map { g -> MangaTag(key = g.lowercase(), title = g, source = source) }.toSet()

        val status = when (doc.selectFirst("strong:contains(Estado) + span")?.text()) {
            "En desarrollo" -> MangaState.ONGOING
            "Finalizado" -> MangaState.FINISHED
            else -> null
        }

        val chapters = doc.select("div#c_list a").mapIndexed { _, element ->
            val chHref = element.attrAsRelativeUrl("href")
            val name = element.text()
            val chNum = element.select("span").attr("data-num").toFloatOrNull() ?: -1f
            val dateStr = element.select("span").attr("datetime")
            val uploadDate = dateStr?.let { dateFormat.parseSafe(it) } ?: 0L
            MangaChapter(
                id = generateUid(chHref),
                url = chHref,
                title = name,
                number = chNum,
                volume = 0,
                uploadDate = uploadDate,
                source = source,
                scanlator = null,
                branch = null,
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = title,
            coverUrl = cover.ifEmpty { manga.coverUrl },
            description = description,
            authors = setOf(author),
            tags = tags,
            state = status,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val script = doc.selectFirst("script:containsData(unicap)")
            ?.data() ?: throw Exception("No se encontró unicap")
        val encoded = script.substringAfter("'").substringBefore("'")
        val decoded = Base64.getDecoder().decode(encoded)
            .toString(Charset.defaultCharset())
        val path = decoded.substringBefore("||")
        val filesStr = decoded.substringAfter("[").substringBefore("]")
        val files = filesStr.split(",").map { it.trim().removeSurrounding("\"") }
        return files.mapIndexed { _, file ->
            val imageUrl = path + file
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private fun SimpleDateFormat.parseSafe(date: String?): Long {
        return date?.let { runCatching { parse(it)?.time }.getOrDefault(0L) } ?: 0L
    }

    companion object {
        val GENRES = listOf(
            Triple("Todos", "Todos", "false"),
            Triple("Comedia", "Comedia", "1"),
            Triple("Drama", "Drama", "2"),
            Triple("Acción", "Acción", "3"),
            Triple("Escolar", "Escolar", "4"),
            Triple("Romance", "Romance", "5"),
            Triple("Ecchi", "Ecchi", "6"),
            Triple("Aventura", "Aventura", "7"),
            Triple("Shōnen", "Shōnen", "8"),
            Triple("Shōjo", "Shōjo", "9"),
            Triple("Deportes", "Deportes", "10"),
            Triple("Psicológico", "Psicológico", "11"),
            Triple("Fantasía", "Fantasía", "12"),
            Triple("Mecha", "Mecha", "13"),
            Triple("Gore", "Gore", "14"),
            Triple("Yaoi", "Yaoi", "15"),
            Triple("Yuri", "Yuri", "16"),
            Triple("Misterio", "Misterio", "17"),
            Triple("Sobrenatural", "Sobrenatural", "18"),
            Triple("Seinen", "Seinen", "19"),
            Triple("Ficción", "Ficción", "20"),
            Triple("Harem", "Harem", "21"),
            Triple("Webtoon", "Webtoon", "25"),
            Triple("Histórico", "Histórico", "27"),
            Triple("Músical", "Músical", "30"),
            Triple("Ciencia ficción", "Ciencia ficción", "31"),
            Triple("Shōjo-ai", "Shōjo-ai", "32"),
            Triple("Josei", "Josei", "33"),
            Triple("Magia", "Magia", "34"),
            Triple("Artes Marciales", "Artes Marciales", "35"),
            Triple("Horror", "Horror", "36"),
            Triple("Demonios", "Demonios", "37"),
            Triple("Supervivencia", "Supervivencia", "38"),
            Triple("Recuentos de la vida", "Recuentos de la vida", "39"),
            Triple("Shōnen ai", "Shōnen ai", "40"),
            Triple("Militar", "Militar", "41"),
            Triple("Eroge", "Eroge", "42"),
            Triple("Isekai", "Isekai", "43"),
        )
    }
}
