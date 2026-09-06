package tsuki.site.fr

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.parseHtml
import tsuki.util.urlEncoded

import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("SUSHISCAN", "Sushi-Scan", "fr")
internal class SushiScan(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.SUSHISCAN, "sushiscan.net") {

    override val mangaDirectory = "catalogue"
    override val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale.FRENCH)

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/$mangaDirectory/")
        .build()

    override fun parseStatus(text: String?): MangaState? {
        val status = text?.lowercase(Locale.FRENCH) ?: return null
        return when {
            status.contains("en cours") -> MangaState.ONGOING
            status.contains("terminé") -> MangaState.FINISHED
            status.contains("abandonné") -> MangaState.ABANDONED
            status.contains("en pause") -> MangaState.PAUSED
            else -> null
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrBlank()) {
            val url = "https://$domain/page/$page?s=${filter.query.urlEncoded()}"
            val doc = webClient.httpGet(url).parseHtml()
            return parseMangaList(doc)
        }
        return super.getListPage(page, order, filter)
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val base = super.getDetails(manga)

        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val table = doc.selectFirst("table.infotable")
        val hasTable = table != null
        val authors = if (hasTable) {
            val authorCell = table.selectFirst("tr:has(td:contains(Auteur)) td:last-child")
            val artistCell = table.selectFirst("tr:has(td:contains(Artiste)) td:last-child")
            listOfNotNull(
                authorCell?.text()?.trim()?.takeIf { it.isNotBlank() && it != "n/a" && it != "N/A" },
                artistCell?.text()?.trim()?.takeIf { it.isNotBlank() && it != "n/a" && it != "N/A" }
            ).toSet()
        } else {
            doc.select(".author, .artist, .fmed span, .tsinfo .imptdt:contains(Auteur) i, .spe span:contains(Auteur) a")
                .mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() && t != "n/a" && t != "N/A" } }
                .toSet()
        }

        val statusText = if (hasTable) {
            table.selectFirst("tr:has(td:contains(Statut)) td:last-child")?.text()
        } else {
            doc.select(".imptdt:contains(Statut) i, .tsinfo .imptdt:contains(Statut) a, .fmed b:contains(Statut)+span span")
                .text()
                .ifEmpty {
                    doc.selectFirst("div.post-content_item:contains(Statut) div.summary-content")?.text()
                }
        }
        val state = parseStatus(statusText)

        base.copy(
            authors = authors,
            state = state,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val scriptContent = doc.selectFirst("script:containsData(ts_reader)")?.data()
        if (scriptContent != null) {
            val jsonString = scriptContent.substringAfter("ts_reader.run(").substringBefore(");")
            val json = JSONObject(jsonString)
            val sources = json.optJSONArray("sources")
            if (sources != null && sources.length() > 0) {
                val firstSource = sources.getJSONObject(0)
                val images = firstSource.optJSONArray("images")
                if (images != null) {
                    return (0 until images.length()).map { i ->
                        val url = images.getString(i).replace("http://", "https://")
                        MangaPage(id = generateUid(url), url = url, preview = null, source = source)
                    }
                }
            }
        }
        return super.getPages(chapter)
    }
}
