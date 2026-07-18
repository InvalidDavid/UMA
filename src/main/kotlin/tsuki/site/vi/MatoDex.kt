package tsuki.site.vi

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.SinglePageMangaParser
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder
import tsuki.network.UserAgents
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseSafe
import org.json.JSONArray
import org.json.JSONObject
import tsuki.model.Favicon
import tsuki.model.Favicons
import tsuki.util.json.asTypedList
import tsuki.util.toAbsoluteUrl
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
        keys.add(userAgentKey)
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
                title = it.text(),
                key = it.text(),
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
                rating = section.selectFirst("svg[data-icon=lucide:star] + span")
                    ?.text()?.toFloatOrNull()
                    ?.div(10f) ?: RATING_UNKNOWN,
                tags = genres,
                authors = authors,
                state = null,
                source = source,
                contentRating = null,
                description = section.select("div.prose p, div[class*=prose] p")
                    .joinToString(separator = "\n\n") { it.text() }
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
        val imgs = doc.select("astro-island[component-url*=MangaReader] img").takeIf { it.isNotEmpty() }
            ?: doc.select("img")
        return imgs.mapIndexed { i, img ->
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
                uploadDate = dateFormat.parseSafe(a.selectFirst("span[class*=text-muted-foreground]")?.text()),
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