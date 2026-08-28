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
import tsuki.util.extractChapterNumber
import tsuki.util.mapNotNullToSet

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    protected open val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("en"))
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
        isTagsExclusionSupported = false,
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
        val url = buildString {
            append("https://$domain/$mangaDirectory/")
            if (filter.query.isNullOrEmpty()) {
                append("?page=$page")
            } else {
                append("?s=${filter.query.urlEncoded()}")
            }
            filter.states.firstOrNull()?.let {
                append("&status=")
                append(it.toQueryParam())
            }
            filter.types.firstOrNull()?.let {
                append("&type=")
                append(it.toQueryParam())
            }
            filter.tags.forEach {
                append("&genre[]=")
                append(it.key.urlEncoded())
            }
            append("&order=")
            append(order.toQueryParam())
        }
        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun MangaState.toQueryParam(): String = when (this) {
        MangaState.ONGOING -> "ongoing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        MangaState.ABANDONED -> "dropped"
        else -> ""
    }

    private fun ContentType.toQueryParam(): String = when (this) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        ContentType.COMICS -> "comic"
        ContentType.NOVEL -> "novel"
        else -> ""
    }

    private fun SortOrder.toQueryParam(): String = when (this) {
        SortOrder.UPDATED -> "update"
        SortOrder.POPULARITY -> "popular"
        SortOrder.ADDED -> "latest"
        SortOrder.ALPHABETICAL -> "title"
        SortOrder.ALPHABETICAL_DESC -> "titlereverse"
        else -> "update"
    }

    protected open fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(searchSelector).mapNotNull { element ->
            parseMangaElement(element)
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val chaptersDeferred = async { loadChapters(doc, manga.url) }

        val title = doc.selectFirst(
            "h1.entry-title, .ts-breadcrumb li:last-child span, .infomanga h1, .animefull h1"
        )?.text() ?: manga.title

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
                authorCell?.text().cleanAuthor(),
                artistCell?.text().cleanAuthor()
            ).toSet()
        } else {
            doc.select(".author, .artist, .fmed span, .tsinfo .imptdt:contains(Author) i, .spe span:contains(Author) a")
                .mapNotNull { it.text().cleanAuthor() }
                .toSet()
        }

        val tags = doc.select(".mgen a, .gnr a, .seriestugenre a, .genres-content a")
            .mapNotNullToSet { a ->
                val text = a.text().trim()
                if (text.isNotBlank()) MangaTag(key = text.lowercase(), title = text, source = source) else null
            }

        val statusText = if (hasTable) {
            table.selectFirst("tr:has(td:contains(Status)) td:last-child")?.text()
        } else {
            doc.select(".imptdt:contains(Status) i, .tsinfo .imptdt:contains(Status) a, .fmed b:contains(Status)+span span")
                .text()
                .ifEmpty {
                    doc.selectFirst("div.post-content_item:contains(Status) div.summary-content")?.text()
                }
        }
        val altTitles = parseAltTitles(doc, table, hasTable)

        val state = parseStatus(statusText)

        val rating = doc.selectFirst(".num[itemprop=ratingValue]")?.attr("content")?.toFloatOrNull()
            ?: doc.selectFirst(".num")?.text()?.toFloatOrNull()
        val normalizedRating = if (rating != null && rating > 0) rating / 10f else RATING_UNKNOWN

        val chapters = chaptersDeferred.await()

        manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            authors = authors,
            tags = tags,
            state = state,
            rating = normalizedRating,
            chapters = chapters,
            altTitles = altTitles
        )
    }

    protected open fun parseAltTitles(doc: Document, table: Element?, hasTable: Boolean): Set<String> {
        return buildSet {
            if (hasTable && table != null) {
                table.select("tr").forEach { row ->
                    val label = row.selectFirst("td")?.text()?.trim().orEmpty()
                    if (
                        label.contains("Alternative", ignoreCase = true) ||
                        label.contains("Alt.", ignoreCase = true) ||
                        label.contains("Alt ", ignoreCase = true)
                    ) {
                        val cell = row.select("td").lastOrNull()
                        val text = cell?.text()
                        if (!text.isNullOrBlank()) {
                            addAll(splitAltTitles(text))
                        }
                    }
                }
            } else {
                val selectors = listOf(
                    ".tsinfo .imptdt:contains(Alt) i",
                    ".tsinfo .imptdt:contains(Alternative) i",
                    ".spe span:contains(Alt) a",
                    ".spe span:contains(Alternative) a",
                    ".alternative",
                    ".alter",
                    ".post-content_item:contains(Alt) .summary-content",
                )
                doc.select(selectors.joinToString(", ")).forEach { element ->
                    val text = element.text()
                    if (text.isNotBlank()) {
                        addAll(splitAltTitles(text))
                    }
                }
            }
        }
    }

    protected fun splitAltTitles(text: String?): Set<String> {
        return text.orEmpty()
            .split(',', ';', '/', '•', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("n/a", true) }
            .toSet()
    }

    protected open fun parseStatus(text: String?): MangaState? {
        val status = text?.lowercase() ?: return null
        return when {
            ongoingWordSet.anyWordIn(status) -> MangaState.ONGOING
            finishedWordSet.anyWordIn(status) -> MangaState.FINISHED
            pausedWordSet.anyWordIn(status) -> MangaState.PAUSED
            abandonedWordSet.anyWordIn(status) -> MangaState.ABANDONED
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
        return elements.mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            if (href.isBlank()) return@mapNotNull null
            val name = a.selectFirst(".chapternum")?.text() ?: a.ownText()
            val dateStr = element.selectFirst(".chapterdate")?.text()
            MangaChapter(
                id = generateUid(href),
                url = href,
                title = name,
                number = name.extractChapterNumber(),
                volume = 0,
                uploadDate = parseChapterDate(dateStr),
                scanlator = null,
                branch = null,
                source = source,
            )
        }.reversed()
    }

    protected open fun parseChapterDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val clean = date.trim()
        return when {
            agoWordSet.endsWith(clean) -> parseRelativeDate(clean)
            fromWordSet.startsWith(clean) -> parseRelativeDate(clean)
            yesterdayWordSet.startsWith(clean) -> yesterday()
            todayWordSet.startsWith(clean) -> today()
            dayBeforeYesterdayWordSet.startsWith(clean) -> dayBeforeYesterday()
            else -> dateFormat.parseSafe(clean)
        }
    }

    private fun parseRelativeDate(text: String): Long {
        val number = Regex("""(\d+)""").find(text)?.value?.toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance()
        return when {
            secondsWordSet.anyWordIn(text) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
            minutesWordSet.anyWordIn(text) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
            hoursWordSet.anyWordIn(text) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
            daysWordSet.anyWordIn(text) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
            weeksWordSet.anyWordIn(text) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number * 7) }.timeInMillis
            monthsWordSet.anyWordIn(text) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
            yearsWordSet.anyWordIn(text) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
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
        if (href.isBlank()) return null
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

    private fun String?.cleanAuthor(): String? =
        this?.trim()?.takeIf { it.isNotBlank() && it !in AUTHOR_PLACEHOLDERS }

    private fun SimpleDateFormat.parseSafe(date: String): Long =
        runCatching { parse(date)?.time ?: 0L }.getOrDefault(0L)

    companion object {
        private val AUTHOR_PLACEHOLDERS = setOf("n/a", "N/A", "Updating")

        private val ongoingWordSet = WordSet(
            "ongoing", "on going", "publishing", "updating", "en curso", "ativo", "en cours",
            "đang tiến hành", "em lançamento", "devam ediyor", "in corso", "güncel", "berjalan",
            "продолжается", "lançando", "in arrivo", "连载中", "devam etmekte", "مستمرة"
        )
        private val finishedWordSet = WordSet(
            "completed", "complete", "finished", "finalizado", "terminé", "tamamlandı",
            "đã hoàn thành", "hoàn thành", "مكتملة", "завершено", "completato", "one-shot",
            "bitti", "tamat", "concluído", "已完结", "bitmiş", "achevé"
        )
        private val pausedWordSet = WordSet(
            "hiatus", "on hold", "pausado", "en espera", "en pause", "en attente",
            "durduruldu", "beklemede", "đang chờ", "متوقف", "заморожено"
        )
        private val abandonedWordSet = WordSet(
            "canceled", "cancelled", "cancelado", "cancellato", "dropped", "discontinued",
            "abandonné", "iptal edildi", "đã hủy", "ملغي", "заброшено"
        )

        private val agoWordSet = WordSet(" ago", "atrás", " hace", " назад", " önce", " trước", "مضت", "قبل")
        private val fromWordSet = WordSet("há ", "منذ", "il y a", "hace", "giờ", "phút")
        private val yesterdayWordSet = WordSet("yesterday", "يوم واحد")
        private val todayWordSet = WordSet("today")
        private val dayBeforeYesterdayWordSet = WordSet("يومين")

        private val secondsWordSet = WordSet("detik", "segundo", "second", "วินาที", "giây", "ثوان")
        private val minutesWordSet = WordSet("menit", "dakika", "min", "minute", "minuto", "mins", "นาที", "دقائق", "phút", "минут")
        private val hoursWordSet = WordSet("jam", "saat", "heure", "hora", "hour", "hours", "ชั่วโมง", "giờ", "ore", "ساعة", "小时")
        private val daysWordSet = WordSet("hari", "gün", "jour", "día", "dia", "day", "days", "días", "วัน", "ngày", "giorni", "أيام", "天", "день")
        private val weeksWordSet = WordSet("week", "semana", "tuần", "أسابيع", "أسبوع")
        private val monthsWordSet = WordSet("month", "months", "mes", "meses", "tháng", "أشهر", "mois")
        private val yearsWordSet = WordSet("year", "año", "năm")
    }
}
