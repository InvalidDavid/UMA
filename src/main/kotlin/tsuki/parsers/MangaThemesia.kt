package tsuki.parsers

import tsuki.MangaLoaderContext
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.RATING_UNKNOWN
import tsuki.model.ContentRating
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.MangaState
import tsuki.model.SortOrder
import tsuki.model.ContentType
import tsuki.model.WordSet

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.parseHtml
import tsuki.util.urlEncoded
import tsuki.util.mapChapters

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

abstract class MangaThemesia(
    context: MangaLoaderContext,
    source: MangaParserSource,
    domain: String,
    pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize) {

    override val configKeyDomain = ConfigKey.Domain(domain)

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/")
        .build()

    protected open val mangaDirectory = "manga"
    protected open val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
    protected open val withoutAjax = false
    protected open val searchSelector = ".utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx"
    protected open val relatedSelector = ".related-posts .bsx, .bixbox .bsx, .related-manga .related-reading-wrap"
    protected open val chapterListSelector = "div.bxcl li, div.cl li, #chapterlist li, ul li:has(div.chbox):has(div.eph-num)"
    protected open val pageSelector = "div#readerarea img"

    override val availableSortOrders: EnumSet<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.UPDATED,
        SortOrder.ADDED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
    )

    @Volatile
    private var genreCache: Set<MangaTag>? = null
    private val genreMutex = Mutex()

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getOrFetchGenres(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
            MangaState.ABANDONED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
            ContentType.NOVEL,
        ),
    )

    private suspend fun getOrFetchGenres(): Set<MangaTag> {
        genreCache?.let { return it }
        return genreMutex.withLock {
            genreCache ?: fetchGenres().also { genreCache = it }
        }
    }

    protected open suspend fun fetchGenres(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/$mangaDirectory/").parseHtml()
        val genrez = doc.selectFirst("ul.genrez") ?: return emptySet()
        return genrez.select("li").mapNotNull { li ->
            val label = li.selectFirst("label")?.text()?.trim() ?: return@mapNotNull null
            val input = li.selectFirst("input[type=checkbox]") ?: return@mapNotNull null
            val value = input.attr("value").trim()
            if (label.isBlank() || value.isBlank()) null
            else MangaTag(key = value, title = label, source = source)
        }.toSet()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query?.trim().orEmpty()
        val url = buildString {
            append("https://$domain/$mangaDirectory/")
            if (query.isNotEmpty()) {
                append("?s=${query.urlEncoded()}")
            } else {
                append("?page=$page")
            }
            filter.states.firstOrNull()?.let {
                append("&status=")
                append(when (it) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    MangaState.PAUSED -> "hiatus"
                    MangaState.ABANDONED -> "dropped"
                    else -> ""
                })
            }
            filter.types.firstOrNull()?.let {
                append("&type=")
                append(when (it) {
                    ContentType.MANGA -> "manga"
                    ContentType.MANHWA -> "manhwa"
                    ContentType.MANHUA -> "manhua"
                    ContentType.COMICS -> "comic"
                    ContentType.NOVEL -> "novel"
                    else -> ""
                })
            }
            filter.tags.forEach { append("&genre[]=${it.key.urlEncoded()}") }
            filter.tagsExclude.forEach { append("&genre[]=-${it.key.urlEncoded()}") }
            append("&order=")
            append(when (order) {
                SortOrder.UPDATED -> "update"
                SortOrder.POPULARITY -> "popular"
                SortOrder.ADDED -> "latest"
                SortOrder.ALPHABETICAL -> "title"
                SortOrder.ALPHABETICAL_DESC -> "titlereverse"
                else -> "update"
            })
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    protected open fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(searchSelector).mapNotNull { element ->
            parseMangaElement(element)
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val title = doc.selectFirst("h1.entry-title, .ts-breadcrumb li:last-child span, .infomanga h1, .animefull h1")
            ?.text() ?: manga.title

        val description = doc.selectFirst(
            ".desc, .entry-content[itemprop=description], .summary__content, .sinopsis, .entry-content p"
        )?.text()?.trim().orEmpty()

        val coverUrl = doc.selectFirst(
            ".thumb img, .infomanga img, .summary_image img, .cover img, img.wp-post-image"
        )?.imgAttr() ?: manga.coverUrl

        val table = doc.selectFirst("table.infotable")
        val hasTable = table != null

        val authors = if (hasTable) {
            val authorCell = table.selectFirst("tr:has(td:contains(Author)) td:last-child")
            val artistCell = table.selectFirst("tr:has(td:contains(Artist)) td:last-child")
            listOfNotNull(
                authorCell?.text()?.trim()?.takeIf { it.isNotBlank() && it != "n/a" && it != "N/A" },
                artistCell?.text()?.trim()?.takeIf { it.isNotBlank() && it != "n/a" && it != "N/A" }
            ).toSet()
        } else {
            doc.select(".author, .artist, .fmed span, .tsinfo .imptdt:contains(Author) i, .spe span:contains(Author) a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }

        val tags = doc.select(".mgen a, .gnr a, .seriestugenre a, .genres-content a")
            .mapNotNull { a ->
                val text = a.text().trim()
                if (text.isNotBlank()) MangaTag(key = text.lowercase(), title = text, source = source) else null
            }.toSet()

        val statusText = if (hasTable) {
            table.selectFirst("tr:has(td:contains(Status)) td:last-child")?.text()
        } else {
            doc.select(".imptdt:contains(Status) i, .tsinfo .imptdt:contains(Status) a, .fmed b:contains(Status)+span span")
                .text()
                .ifEmpty {
                    doc.selectFirst("div.post-content_item:contains(Status) div.summary-content")?.text()
                }
        }
        val altnames = if (hasTable) {
            table.selectFirst("tr:has(td:contains(Alternative)) td:last-child")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "n/a" && it != "N/A" }
                ?.let { setOf(it) }
        } else emptySet()
        val state = parseStatus(statusText)

        val rating = doc.selectFirst(".num[itemprop=ratingValue]")?.attr("content")?.toFloatOrNull()
            ?: doc.selectFirst(".num")?.text()?.toFloatOrNull()
        val normalizedRating = if (rating != null && rating > 0) rating / 10f else RATING_UNKNOWN

        val chapters = loadChapters(doc, manga.url)

        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            authors = authors,
            tags = tags,
            state = state,
            rating = normalizedRating,
            chapters = chapters,
            altTitles = altnames ?: emptySet()
        )
    }

    protected open fun parseStatus(text: String?): MangaState? {
        val status = text?.lowercase() ?: return null
        return when (status) {
            in ongoing -> MangaState.ONGOING
            in finished -> MangaState.FINISHED
            in paused -> MangaState.PAUSED
            in abandoned -> MangaState.ABANDONED
            else -> null
        }
    }

    protected open suspend fun loadChapters(doc: Document, mangaUrl: String): List<MangaChapter> {
        val chapterElements = doc.select(chapterListSelector)
        if (chapterElements.isEmpty() && !withoutAjax) {
            return loadChaptersAjax(mangaUrl)
        }
        return parseChapters(chapterElements)
    }

    protected open suspend fun loadChaptersAjax(mangaUrl: String): List<MangaChapter> {
        val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/"
        val doc = webClient.httpPost(ajaxUrl, emptyMap()).parseHtml()
        return parseChapters(doc.select(chapterListSelector))
    }

    private fun parseChapters(elements: Elements): List<MangaChapter> {
        return elements.mapChapters(reversed = true) { i, element ->
            val a = element.selectFirst("a") ?: return@mapChapters null
            val href = a.attrAsRelativeUrl("href")
            val name = a.selectFirst(".chapternum")?.text() ?: a.ownText()
            val dateStr = element.selectFirst(".chapterdate")?.text()
            val number = extractChapterNumber(name) ?: (i + 1).toFloat()
            MangaChapter(
                id = generateUid(href),
                url = href,
                title = name,
                number = number,
                volume = 0,
                uploadDate = parseChapterDate(dateStr),
                scanlator = null,
                branch = null,
                source = source,
            )
        }
    }

    private fun extractChapterNumber(title: String): Float? {
        return Regex("""(?i)(?:chapter|ch\.?)\s*([0-9]+(?:\.[0-9]+)?)""")
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
    }

    protected open fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val clean = date.trim()
        return when {
            WordSet(" ago", "atrás", " hace", " назад", " önce", " trước", "مضت", "قبل").endsWith(clean) -> parseRelativeDate(clean)
            WordSet("há ", "منذ", "il y a", "hace", "giờ", "phút").startsWith(clean) -> parseRelativeDate(clean)
            WordSet("yesterday", "يوم واحد").startsWith(clean) -> yesterday()
            WordSet("today").startsWith(clean) -> today()
            WordSet("يومين").startsWith(clean) -> dayBeforeYesterday()
            else -> dateFormat.parseSafe(clean)
        }
    }

    private fun parseRelativeDate(text: String): Long {
        val number = Regex("""(\d+)""").find(text)?.value?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        return when {
            WordSet("detik", "segundo", "second", "วินาที", "giây", "ثوان").anyWordIn(text) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
            WordSet("menit", "dakika", "min", "minute", "minuto", "mins", "นาที", "دقائق", "phút", "минут").anyWordIn(text) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
            WordSet("jam", "saat", "heure", "hora", "hour", "hours", "ชั่วโมง", "giờ", "ore", "ساعة", "小时").anyWordIn(text) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
            WordSet("hari", "gün", "jour", "día", "dia", "day", "days", "días", "วัน", "ngày", "giorni", "أيام", "天", "день").anyWordIn(text) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
            WordSet("week", "semana", "tuần", "أسابيع", "أسبوع").anyWordIn(text) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number * 7) }.timeInMillis
            WordSet("month", "months", "mes", "meses", "tháng", "أشهر", "mois").anyWordIn(text) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
            WordSet("year", "año", "năm").anyWordIn(text) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
            else -> 0L
        }
    }

    private fun yesterday() = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
    private fun today() = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    private fun dayBeforeYesterday() = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -2) }.timeInMillis

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        // json image list
        val jsonRegex = Regex(""""images"\s*:\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
        val jsonMatch = jsonRegex.find(doc.html())
        if (jsonMatch != null) {
            val jsonArray = JSONArray(jsonMatch.groupValues[1])
            return (0 until jsonArray.length()).map { i ->
                val url = jsonArray.getString(i)
                MangaPage(id = generateUid(url), url = url, preview = null, source = source)
            }
        }

        //  direct <img>
        return doc.select(pageSelector).mapNotNull { img ->
            val url = img.imgAttr().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val doc = webClient.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select(relatedSelector).mapNotNull { element ->
            parseMangaElement(element)
        }
    }

    protected open fun parseMangaElement(element: Element): Manga? {
        val a = element.selectFirst("a") ?: return null
        val href = a.attrAsRelativeUrl("href")
        val title = a.attr("title").ifBlank { a.text() }
        val coverUrl = element.selectFirst("img")?.imgAttr()
        return makeManga(href, title, coverUrl)
    }

    private fun makeManga(href: String, title: String, coverUrl: String?): Manga {
        return Manga(
            id = generateUid(href),
            url = href,
            publicUrl = href.toAbsoluteUrl(domain),
            title = title,
            altTitles = emptySet(),
            authors = emptySet(),
            coverUrl = coverUrl,
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            state = null,
            contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            source = source,
        )
    }

    protected fun Element.imgAttr(): String {
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "data-cfsrc", "data-image", "src")) {
            val value = attr(attr).trim().takeIf { it.isNotEmpty() } ?: continue
            return value.toAbsoluteUrl(domain).substringBefore("?") // remove WP resize params
        }
        val srcset = attr("srcset")
        if (srcset.isNotBlank()) {
            return srcset.split(",").last().trim().split(" ").first().toAbsoluteUrl(domain)
        }
        return ""
    }

    private fun SimpleDateFormat.parseSafe(date: String): Long =
        runCatching { parse(date)?.time ?: 0L }.getOrDefault(0L)

    companion object {
        val ongoing = setOf(
            "ongoing", "on going", "publishing", "updating", "en curso", "ativo", "en cours",
            "đang tiến hành", "em lançamento", "devam ediyor", "in corso", "güncel", "berjalan",
            "продолжается", "lançando", "in arrivo", "连载中", "devam etmekte", "مستمرة",
        )
        val finished = setOf(
            "completed", "complete", "finished", "finalizado", "terminé", "tamamlandı",
            "đã hoàn thành", "hoàn thành", "مكتملة", "завершено", "completato", "one-shot",
            "bitti", "tamat", "concluído", "已完结", "bitmiş", "achevé",
        )
        val paused = setOf(
            "hiatus", "on hold", "pausado", "en espera", "en pause", "en attente",
            "durduruldu", "beklemede", "đang chờ", "متوقف", "заморожено",
        )
        val abandoned = setOf(
            "canceled", "cancelled", "cancelado", "cancellato", "dropped", "discontinued",
            "abandonné", "iptal edildi", "đã hủy", "ملغي", "заброшено",
        )
    }
}
