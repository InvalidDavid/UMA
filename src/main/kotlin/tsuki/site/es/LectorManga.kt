package tsuki.site.es

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

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

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseJson

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.util.EnumSet

// Mirror of zerocomics.net?

@MangaSourceParser("LECTORMANGA", "Lector Manga", "es")
internal class LectorManga(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.LECTORMANGA, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("lectormangass.net")
    private val baseUrl = "https://$domain"
    private val apiBase = "https://api.zerocomics.net/api"
    private val siteHeader = "lectormanga"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    private val apiHeaders: Headers
        get() = Headers.Builder()
            .add("Accept", "application/json")
            .add("X-Site", siteHeader)
            .add("User-Agent", config[userAgentKey])
            .build()

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val (sortParam, orderParam) = when (order) {
            SortOrder.UPDATED      -> "" to ""
            SortOrder.RATING       -> "rating"      to "desc"
            SortOrder.ALPHABETICAL -> "name"        to "asc"
            else                   -> "" to ""
        }

        val url = "$apiBase/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", sortParam)
            .addQueryParameter("order", orderParam)

        if (filter.query != null) {
            url.addQueryParameter("q", filter.query)
        }

        val json = webClient.httpGet(url.build(), apiHeaders).parseJson()
        val data = json.optJSONArray("data") ?: return emptyList()

        return (0 until data.length()).map { i ->
            val obj = data.getJSONObject(i)
            val slug  = obj.getString("slug")
            val title = obj.getString("titulo")
            val cover = obj.optString("portada", "")
            Manga(
                id = generateUid(slug),
                url = slug,
                publicUrl = "$baseUrl/comics/$slug",
                title = title,
                coverUrl = cover,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url
        val doc = webClient.httpGet("$baseUrl/comics/$slug").parseHtml()

        val title = doc.selectFirst("h1.info-title")?.text()?.trim() ?: manga.title
        val cover = doc.selectFirst(".cover-image img")?.attr("abs:src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: manga.coverUrl

        val description = doc.selectFirst(".info-desc-text")?.text()?.trim()

        val genres = doc.select(".genre-chips-wrap a.v-chip")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .toSet()

        val author = doc.selectFirst("meta[name=author]")?.attr("content")
        val artist = doc.selectFirst("meta[name=artist]")?.attr("content")

        val statusText = doc.selectFirst(".status-chip span")?.text()?.trim()
        val state = when (statusText?.lowercase()) {
            "en emisión" -> MangaState.ONGOING
            "finalizado" -> MangaState.FINISHED
            "pausado" -> MangaState.PAUSED
            "abandonado" -> MangaState.ABANDONED
            else -> null
        }

        val chapters = parseChapters(doc)

        val tagSet = genres.map { MangaTag(it, it, source) }.toMutableSet()
        val type = doc.selectFirst("meta[name=type]")?.attr("content")
        if (!type.isNullOrBlank()) tagSet += MangaTag(type, type, source)

        return manga.copy(
            title = title,
            coverUrl = cover ?: manga.coverUrl,
            description = description,
            authors = setOfNotNull(author, artist).ifEmpty { emptySet() },
            tags = tagSet,
            state = state,
            chapters = chapters,
            contentRating = ContentRating.SAFE,
        )
    }

    private fun parseChapters(doc: Document): List<MangaChapter> {
        val items = doc.select("div[data-chapter-num]")
        return items.mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href").removePrefix("/")   // e.g., "comics/.../capitulo-20"

            val chapterSlug = href.substringAfterLast("/")   // "capitulo-20"
            val number = chapterSlug.substringAfter("capitulo-", "").toFloatOrNull() ?: 0f

            val titleDiv = link.selectFirst("div.d-flex.justify-space-between > div:first-child")
            val title = titleDiv?.text()?.trim() ?: "Capítulo ${number.toInt()}"

            val dateDiv = link.selectFirst("div.text--disabled div.d-flex > div:first-child")
            val dateText = dateDiv?.text()?.trim()
            val uploadDate = parseRelativeDate(dateText)

            MangaChapter(
                id = generateUid("/$href"),
                url = "/$href",
                title = title,
                number = number,
                volume = 0,
                uploadDate = uploadDate,
                source = source,
                scanlator = null,
                branch = null,
            )
        }.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val readerUrl = "$baseUrl/${chapter.url.removePrefix("/")}"
        val doc = webClient.httpGet(readerUrl).parseHtml()

        val images = doc.select("div.reader-images img, img.reader-page, img[src*='bmcdn.my.id']")
        if (images.isNotEmpty()) {
            return images.map { img ->
                val url = img.attr("abs:src")
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }
        return doc.select("img").map { img ->
            val url = img.attr("abs:src")
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseRelativeDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val clean = text.trim().removePrefix("hace ").trim()
        val parts = clean.split(" ")
        if (parts.size != 2) return 0L
        val amount = parts[0].toIntOrNull() ?: return 0L
        val unit = parts[1].lowercase()
        val now = System.currentTimeMillis()
        val millis = when (unit) {
            "h" -> amount * 60 * 60 * 1000L
            "min", "minuto", "minutos" -> amount * 60 * 1000L
            "día", "días", "dia", "dias" -> amount * 24 * 60 * 60 * 1000L
            "sem", "semanas", "semana" -> amount * 7 * 24 * 60 * 60 * 1000L
            "mes", "meses" -> amount * 30 * 24 * 60 * 60 * 1000L
            "año", "años", "ano", "anos" -> amount * 365 * 24 * 60 * 60 * 1000L
            else -> 0L
        }
        return if (millis > 0) now - millis else 0L
    }
}
