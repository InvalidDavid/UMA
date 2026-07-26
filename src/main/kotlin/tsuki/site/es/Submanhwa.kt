package tsuki.site.es

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.*

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("SUBMANHWA", "Submanhwa", "es")
internal class Submanhwa(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SUBMANHWA, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("submanhwa.com")

    private val dateFormat = SimpleDateFormat("dd MMM. yyyy", Locale.ENGLISH)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Accept-Language", "es-PE,es;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()

        val url = if (query.isNotEmpty()) {
            "https://$domain/filterList?page=$page&sortBy=views&asc=false&alpha=${query.urlEncoded()}"
        } else when (order) {
            SortOrder.POPULARITY -> "https://$domain/filterList?page=$page&sortBy=views&asc=false"
            SortOrder.UPDATED -> "https://$domain"   // latest updates
            else -> "https://$domain/filterList?page=$page&sortBy=views&asc=false"
        }

        val doc = webClient.httpGet(url).parseHtml()

        val elements = if (order == SortOrder.UPDATED && query.isEmpty()) {
            doc.select("div[class^=manga-item]")
        } else {
            doc.select(".series-card")
        }

        return elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.absUrl("href")
            val title = element.selectFirst("h3[class^=manga-title] a, .series-title")?.text() ?: a.text()
            val coverUrl = element.selectFirst("img")?.absUrl("src")

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href,
                title = title,
                coverUrl = coverUrl,
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
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.selectFirst(".manga-title-centered")?.text() ?: manga.title
        val coverUrl = doc.selectFirst("img")?.absUrl("src") ?: manga.coverUrl
        val description = doc.selectFirst("h5:contains(Resumen) + p")?.text()

        val box = doc.selectFirst(".main-content > .boxed-modern")

        val state = when (box?.selectFirst(".detail-label:contains(Estado) + .detail-value span")?.text()?.lowercase()) {
            "completa" -> MangaState.FINISHED
            "en curso" -> MangaState.ONGOING
            else -> null
        }

        val author = box?.selectFirst(".detail-label:contains(Autor) + .detail-value a")?.text()
        val artist = box?.selectFirst(".detail-label:contains(Artist) + .detail-value a")?.text()
        val genres = box?.select(".detail-label:contains(Categor) + .detail-value a")
            ?.map { MangaTag(it.text().lowercase(), it.text(), source) }
            .orEmpty()
            .toSet()

        val chapters = parseChapters(doc)

        return manga.copy(
            title = title,
            coverUrl = coverUrl,
            description = description,
            authors = setOfNotNull(author, artist).filter { it.isNotBlank() }.toSet(),
            tags = genres,
            state = state,
            chapters = chapters,
        )
    }

    private fun parseChapters(doc: Document): List<MangaChapter> {
        return doc.select(".chapters-grid [class^=chapter-card]").mapNotNull { element ->
            val a = element.selectFirst("a.chapter-link") ?: return@mapNotNull null
            val name = a.text()
            val href = a.absUrl("href")

            val dateText = element.selectFirst("span:has(i.glyphicon-time)")?.text()
                ?: element.selectFirst(".chapter-preview-meta > span")?.text()
            val uploadDate = dateText?.let { parseDate(it) } ?: 0L

            val number = Regex("""\d+(\.\d+)?""").find(name)?.value?.toFloatOrNull() ?: 0f

            MangaChapter(
                id = generateUid(href),
                title = name,
                number = number,
                volume = 0,
                url = href,
                uploadDate = uploadDate,
                scanlator = null,
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }
    }

    private fun parseDate(text: String): Long {
        return try {
            dateFormat.parse(text.trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("#all img").mapIndexed { idx, img ->
            val url = img.imgAttr().takeIf { it.isNotBlank() } ?: ""
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun Element.imgAttr(): String {
        if (hasAttr("data-src")) return absUrl("data-src")
        if (hasAttr("data-lazy-src")) return absUrl("data-lazy-src")
        return absUrl("src")
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
