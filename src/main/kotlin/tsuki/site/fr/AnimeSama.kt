package tsuki.site.fr

import kotlinx.coroutines.Dispatchers
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.*

import tsuki.network.OkHttpWebClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl
import java.util.EnumSet
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.URLEncoder

@MangaSourceParser("ANIMESAMA", "AnimeSama", "fr")
internal class AnimeSama(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ANIMESAMA, pageSize = 48) {

    override val configKeyDomain = ConfigKey.Domain("anime-sama.to")
    private val baseUrl = "https://$domain"

    override val webClient by lazy {
        OkHttpWebClient(context.httpClient.newBuilder().build(), source)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept-Language", "fr-FR")
        .build()

    private var cachedGenres: Set<MangaTag>? = null

    private suspend fun fetchGenres(): Set<MangaTag> {
        if (cachedGenres != null) return cachedGenres!!
        val doc = webClient.httpGet("$baseUrl/catalogue", getRequestHeaders()).parseHtml()
        val tags = doc.select("#list_genres #genreList label").mapNotNull { label ->
            val input = label.selectFirst("input[name=genre[]]") ?: return@mapNotNull null
            val value = input.attr("value")
            val name = label.selectFirst("span")?.text() ?: return@mapNotNull null
            MangaTag(name, value, source)
        }.toSet()
        cachedGenres = tags
        return tags
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,   // catalogue listing
        SortOrder.UPDATED       // latest additions on homepage
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
        availableTags = fetchGenres(), 
        availableStates = emptySet()
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()

        if (query.startsWith("http")) {
            val url = query.toHttpUrlOrNull()
            if (url != null && url.host == domain && url.pathSegments.contains("catalogue")) {
                return fetchMangaFromUrl(url)
            }
        }

        if (query.isNotEmpty()) {
            return fetchSearch(query, filter.tags.map { it.key }, page)
        }
        return when (order) {
            SortOrder.UPDATED   -> fetchLatest()
            else                -> fetchSearch("", filter.tags.map { it.key }, page)  // POPULARITY or any other
        }
    }

    private suspend fun fetchMangaFromUrl(url: HttpUrl): List<Manga> {
        val path = url.encodedPath.removeSuffix("/").substringBefore("/scan")
        val mangaUrl = url.newBuilder().encodedPath("$path/").build()
        val doc = webClient.httpGet(mangaUrl.toString(), getRequestHeaders()).parseHtml()
        val manga = parseMangaDetails(doc, mangaUrl.toString())
        return listOfNotNull(manga)
    }

    private suspend fun fetchLatest(): List<Manga> {
        val doc = webClient.httpGet(baseUrl, getRequestHeaders()).parseHtml()
        return doc.select("div#containerAjoutsScans > div").mapNotNull { el ->
            parseCardElement(el, removeSuffix = "scan/vf/")
        }
    }

    private suspend fun fetchSearch(query: String, genreIds: List<String>, page: Int): List<Manga> {
        val urlBuilder = "$baseUrl/catalogue".toHttpUrl().newBuilder()
            .addQueryParameter("type[]", "Scans")
            .addQueryParameter("page", page.toString())
        if (query.isNotEmpty()) urlBuilder.addQueryParameter("search", query)
        genreIds.forEach { urlBuilder.addQueryParameter("genre[]", it) }
        val doc = webClient.httpGet(urlBuilder.build().toString(), getRequestHeaders()).parseHtml()
        return doc.select("div#list_catalog > div").mapNotNull { el -> parseCardElement(el) }
    }

    private fun proxyImage(originalUrl: String?, width: Int = 480): String? {
        if (originalUrl.isNullOrBlank()) return null
        val encoded = URLEncoder.encode(originalUrl, "UTF-8")
        return "https://wsrv.nl/?url=$encoded&w=$width"
    }

    private fun parseCardElement(el: Element, removeSuffix: String = ""): Manga? {
        val a = el.selectFirst("a") ?: return null
        val rawUrl = a.absUrl("href")
        val url = if (removeSuffix.isNotEmpty()) rawUrl.removeSuffix(removeSuffix) else rawUrl
        val title = el.selectFirst("h2.card-title")?.text() ?: return null
        val cover = proxyImage(el.selectFirst("img")?.absUrl("src"))
        return Manga(
            id = generateUid(url), url = url, publicUrl = url,
            title = title, coverUrl = cover, altTitles = emptySet(),
            rating = RATING_UNKNOWN, contentRating = null, tags = emptySet(),
            state = null, authors = emptySet(), source = source
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url, getRequestHeaders()).parseHtml()
        val detailedManga = parseMangaDetails(doc, manga.url) ?: manga
        val chapters = fetchChapters(doc, manga.url)
        return detailedManga.copy(chapters = chapters)
    }

    private fun parseMangaDetails(doc: Document, url: String): Manga? {
        val title = doc.selectFirst("div.my-2 h1")?.text() ?: return null
        val cover = proxyImage(doc.selectFirst("img#coverOeuvre")?.absUrl("src"))
        val author = doc.selectFirst("span.info-lbl:contains(Créateur) + span.info-val")?.text()
        val statusText = doc.selectFirst("span.info-lbl:contains(État) + span.info-val")?.text()
        val state = when (statusText?.lowercase()) {
            "en cours" -> MangaState.ONGOING
            "terminé" -> MangaState.FINISHED
            else -> null
        }
        val description = doc.selectFirst("p#synopsisText")?.text()
        val genres = doc.select("span.genre-pill").map { it.text() }.toSet()
        val tags = genres.map { MangaTag(it.lowercase(), it, source) }.toSet()

        return Manga(
            id = generateUid(url), url = url, publicUrl = url,
            title = title, coverUrl = cover, altTitles = emptySet(),
            rating = RATING_UNKNOWN, contentRating = null, tags = tags,
            state = state, authors = setOfNotNull(author), description = description,
            source = source
        )
    }

    private suspend fun fetchChapters(mangaDoc: Document, mangaUrl: String): List<MangaChapter> {
        val scriptContent = mangaDoc.select("script:containsData(panneauScan(\"nom\", \"url\"))").toString()
        val lines = scriptContent.split(";").drop(1)
        val scanPanels = lines.mapNotNull { line ->
            SCAN_PANEL_REGEX.find(line)?.destructured?.let { (name, url) ->
                if (!url.contains("va")) name to url else null
            }
        }
        if (scanPanels.isEmpty()) return emptyList()

        val chapterLists = coroutineScope {
            scanPanels.map { (scanTitle, scanUrl) ->
                async { fetchScanGroupChapters(mangaUrl, scanTitle, scanUrl) }
            }.awaitAll()
        }

        val seen = mutableSetOf<String>()
        val allChapters = mutableListOf<MangaChapter>()
        for (list in chapterLists) {
            for (ch in list) {
                if (seen.add(ch.title.toString())) allChapters.add(ch)
            }
        }

        return allChapters
            .sortedBy { ch -> ch.url.toHttpUrlOrNull()?.queryParameter("id")?.toIntOrNull() ?: 0 }
            .sortedBy { it.number }
    }

    private suspend fun fetchScanGroupChapters(mangaUrl: String, scanTitle: String, scanUrl: String): List<MangaChapter> {
        val subUrl = mangaUrl.trimEnd('/') + "/" + scanUrl.trim('/')
        val subDoc = webClient.httpGet(subUrl, getRequestHeaders()).parseHtml()

        var title: String? = null
        val mainPageLink = subDoc.selectFirst("a:has(#imgOeuvre.grayscale)")?.absUrl("href")
        if (!mainPageLink.isNullOrEmpty()) {
            val mainDoc = webClient.httpGet(mainPageLink, getRequestHeaders()).parseHtml()
            title = mainDoc.getWorkTitle()
        }
        if (title.isNullOrBlank()) title = subDoc.getWorkTitle()
        if (title.isBlank()) return emptyList()

        val scanlator = scanTitle.replace(SCANS_REGEX, "").trim()
        val chapterApiUrl = "$baseUrl/s2/scans/get_nb_chap_et_img.php".toHttpUrl().newBuilder()
            .addQueryParameter("oeuvre", title).build()
        val apiResponse = webClient.httpGet(chapterApiUrl.toString(), getRequestHeaders()).parseJson()
        val imageCounts = mutableMapOf<String, Int>()
        for (key in apiResponse.keys()) {
            imageCounts[key] = apiResponse.optInt(key, 0)
        }

        val html = subDoc.html()
        val chapterList = mutableListOf<MangaChapter>()
        var chapterDelay = 0

        if (html.contains("resetListe()")) {
            val commands = html.split(";")
            for (command in commands) {
                when {
                    CREATE_LIST_REGEX.find(command) != null -> {
                        val data = CREATE_LIST_REGEX.find(command)!!.groupValues[1].split(",")
                        val start = data[0].trim().toInt()
                        val end = data[1].trim().toInt()
                        for (i in start..end) {
                            chapterList.add(buildChapter(i.toString(), chapterList.size + 1, title, scanlator, chapterApiUrl))
                        }
                    }
                    NEW_SP_REGEX.find(command) != null -> {
                        val chapterName = NEW_SP_REGEX.find(command)!!.groupValues[1]
                        chapterList.add(buildChapter(chapterName, chapterList.size + 1, title, scanlator, chapterApiUrl))
                        chapterDelay++
                    }
                }
            }
        }

        val totalChapters = imageCounts.size
        for (i in chapterList.size until totalChapters) {
            val id = (chapterList.size + 1 - chapterDelay).toString()
            chapterList.add(buildChapter(id, chapterList.size + 1, title, scanlator, chapterApiUrl))
        }
        return chapterList
    }

    private fun buildChapter(chapterName: String, id: Int, title: String, scanlator: String, baseChapterUrl: HttpUrl): MangaChapter {
        val chapterUrl = baseChapterUrl.newBuilder()
            .addQueryParameter("id", id.toString())
            .addQueryParameter("title", title)
            .build()
        return MangaChapter(
            id = generateUid(chapterUrl.toString()),
            title = "Chapitre $chapterName", number = id.toFloat(), volume = 0,
            url = chapterUrl.toString(), uploadDate = 0, scanlator = scanlator,
            branch = null, source = source
        )
    }

    private fun Document.getWorkTitle(): String = selectFirst("#titreOeuvre")?.textNodes()?.firstOrNull()?.wholeText ?: ""

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val url = chapter.url.toHttpUrlOrNull() ?: return emptyList()
        val chapterId = url.queryParameter("id") ?: return emptyList()
        val rawTitle = url.queryParameter("title") ?: return emptyList()
        val encodedTitle = withContext(Dispatchers.IO) {
            URLEncoder.encode(rawTitle, "UTF-8")
        }
        val apiUrl = "$baseUrl/s2/scans/get_nb_chap_et_img.php".toHttpUrl().newBuilder()
            .addQueryParameter("oeuvre", rawTitle)
            .build()
        val json = webClient.httpGet(apiUrl.toString(), getRequestHeaders()).parseJson()
        val imageCount = json.optInt(chapterId, 0)

        return (1..imageCount).map { index ->
            MangaPage(
                id = generateUid("$rawTitle/$chapterId/$index"),
                url = "$baseUrl/s2/scans/$encodedTitle/$chapterId/$index.jpg",
                preview = null,
                source = source
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    companion object {
        private val SCAN_PANEL_REGEX = """panneauScan\("(.+?)", "(.+?)"\)""".toRegex()
        private val CREATE_LIST_REGEX = """creerListe\((\d+,\s*\d+)\)""".toRegex()
        private val NEW_SP_REGEX = """newSP\((\d+(\.\d+)?|"(.*?)")\)""".toRegex()
        private val SCANS_REGEX = """(Scans|\(|\))""".toRegex()
    }
}
