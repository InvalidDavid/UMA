package org.koitharu.kotatsu.parsers.site.kotatsu.vi

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.Favicon
import org.koitharu.kotatsu.parsers.model.Favicons
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MATODEX", "MatoDex", "vi")
internal class MatoDex(context: MangaLoaderContext) :
    SinglePageMangaParser(context, MangaParserSource.MATODEX) {

    override val configKeyDomain = ConfigKey.Domain("mato.suicaodex.com")
    override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

    override suspend fun getFavicons(): Favicons {
        return Favicons(
            listOf(
                Favicon(
                    "https://suicaodex.com/_next/image?url=/_next/static/media/gehenna.01tto1cht53c..webp&w=48&q=100",
                    100, null
                ),
            ), domain
        )
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.remove(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities()

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
        val request = webClient.httpGet("https://$domain/").parseHtml()
        val section = request.selectFirst("section.flex.flex-1.flex-col.gap-4") ?: request
        val genres = section.select("div.flex.flex-wrap.gap-1.md\\:hidden span").map {
            MangaTag(
                title = it.text().trim(),
                key = it.text().trim(),
                source = source,
            )
        }.toSet()

        val authors = section.select("div.font-title p.line-clamp-1.text-base.break-all")
            .firstOrNull()?.text()
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()

        val title = section.selectFirst("p.text-3xl.font-black.drop-shadow-md")?.text() ?: ""
        val altTitle = section.selectFirst("h2.line-clamp-2.text-lg.leading-5.drop-shadow-md")?.text() ?: ""

        return listOf(
            Manga(
                id = generateUid(domain),
                url = "https://$domain/",
                publicUrl = "https://$domain/",
                coverUrl = section.selectFirst("div.relative.shrink-0 img")?.attr("src")?.toAbsoluteUrl(domain),
                title = title,
                altTitles = if (altTitle.isNotEmpty()) setOf(altTitle) else emptySet(),
                rating = section.selectFirst("span[data-icon=lucide:star]")
                    ?.nextElementSibling()?.text()?.toFloatOrNull()
                    ?.div(10f) ?: RATING_UNKNOWN,
                tags = genres,
                authors = authors,
                state = null,
                source = source,
                contentRating = null,
                description = section.select("div.prose p, div[class*=prose] p")
                    .joinToString(separator = "\n\n") { it.text().trim() }
                    .takeIf { it.isNotEmpty() },
            ),
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val firstDoc = webClient.httpGet("${manga.url}read").parseHtml()
        val totalPages = getTotalPages(firstDoc)
        val chapters = coroutineScope {
            (1..totalPages).map { page ->
                async { fetchChaptersPage(manga, page) }
            }.awaitAll().flatten()
        }

        return manga.copy(
            chapters = chapters.sortedBy { it.number },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()
        val props = doc.selectFirst("astro-island[component-url*=MangaReader]")?.attr("props")
        if (!props.isNullOrEmpty()) {
            val urls = runCatching {
                val json = JSONObject(props)
                json.optJSONArray("images")?.optJSONArray(1)?.asTypedList<Any>()
                    ?.mapNotNull { item -> (item as? JSONArray)?.optString(1) ?: (item as? String) }
            }.getOrNull()

            if (!urls.isNullOrEmpty()) {
                return urls.mapIndexed { i, url ->
                    MangaPage(
                        id = generateUid("${chapter.url}#$i"),
                        url = url.toAbsoluteUrl(domain),
                        preview = null,
                        source = source,
                    )
                }
            }
        }

        // fallback
        val imgElements = doc.select("astro-island[component-url*=MangaReader] img").takeIf { it.isNotEmpty() }
            ?: doc.select("img")
        return imgElements.mapIndexed { i, img ->
            MangaPage(
                id = generateUid("${chapter.url}#$i"),
                url = img.absUrl("src"),
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchChaptersPage(manga: Manga, page: Int): List<MangaChapter> {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        val doc = if (page == 1) {
            webClient.httpGet("${manga.url}read").parseHtml()
        } else {
            webClient.httpGet("${manga.url}read/$page").parseHtml()
        }

        return doc.select("section ul > li > a").map { a ->
            val href = a.attr("href").toAbsoluteUrl(domain)
            val title = a.selectFirst("h3")?.text().orEmpty()
            MangaChapter(
                id = generateUid(href),
                title = title,
                number = Regex("""\d+(?:\.\d+)?""").find(title)?.value?.toFloatOrNull() ?: 0f,
                url = href,
                uploadDate = dateFormat.parseSafe(a.selectFirst("span.text-muted-foreground")?.text()),
                scanlator = a.select("span.font-medium").joinToString(", ") { it.text() }.ifBlank { null },
                branch = null,
                source = source,
                volume = 0,
            )
        }
    }

    private fun getTotalPages(doc: Document): Int {
        return doc.select("nav[aria-label=pagination] a[href]").mapNotNull {
            it.attr("href").substringAfterLast("/").toIntOrNull()
        }.maxOrNull() ?: 1
    }
}