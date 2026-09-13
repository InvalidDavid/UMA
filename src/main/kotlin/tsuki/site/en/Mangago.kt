package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect
import tsuki.network.OkHttpWebClient

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

import tsuki.util.attrAsAbsoluteUrlOrNull
import tsuki.util.attrAsRelativeUrlOrNull
import tsuki.util.generateUid
import tsuki.util.mapToSet
import tsuki.util.parseHtml
import tsuki.util.parseRaw
import tsuki.util.requireElementById
import tsuki.util.selectFirstOrThrow
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded
import tsuki.util.extractChapterNumber

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

@MangaSourceParser("MANGAGO", "Mangago", "en")
internal class MangagoParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAGO, pageSize = 20),
    Interceptor {

    override val configKeyDomain = ConfigKey.Domain("mangago.me")

    private val baseUrl = "https://$domain"

    override val webClient = OkHttpWebClient(
        context.httpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Cookie", "_m_superu=1")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(this)
            .build(),
        source,
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = getAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private val removeRaws: Boolean = true
    private val removeTitleVersion: Boolean = false

    private fun getAvailableTags(): Set<MangaTag> = setOf(
        MangaTag("Yaoi", "Yaoi", source),
        MangaTag("Doujinshi", "Doujinshi", source),
        MangaTag("Shounen Ai", "Shounen Ai", source),
        MangaTag("Shoujo", "Shoujo", source),
        MangaTag("Yuri", "Yuri", source),
        MangaTag("Romance", "Romance", source),
        MangaTag("Fantasy", "Fantasy", source),
        MangaTag("Comedy", "Comedy", source),
        MangaTag("Smut", "Smut", source),
        MangaTag("Adult", "Adult", source),
        MangaTag("School Life", "School Life", source),
        MangaTag("Mystery", "Mystery", source),
        MangaTag("One Shot", "One Shot", source),
        MangaTag("Ecchi", "Ecchi", source),
        MangaTag("Shounen", "Shounen", source),
        MangaTag("Martial Arts", "Martial Arts", source),
        MangaTag("Shoujo Ai", "Shoujo Ai", source),
        MangaTag("Supernatural", "Supernatural", source),
        MangaTag("Drama", "Drama", source),
        MangaTag("Action", "Action", source),
        MangaTag("Adventure", "Adventure", source),
        MangaTag("Harem", "Harem", source),
        MangaTag("Historical", "Historical", source),
        MangaTag("Horror", "Horror", source),
        MangaTag("Josei", "Josei", source),
        MangaTag("Mature", "Mature", source),
        MangaTag("Mecha", "Mecha", source),
        MangaTag("Psychological", "Psychological", source),
        MangaTag("Sci-fi", "Sci-fi", source),
        MangaTag("Seinen", "Seinen", source),
        MangaTag("Slice Of Life", "Slice Of Life", source),
        MangaTag("Sports", "Sports", source),
        MangaTag("Gender Bender", "Gender Bender", source),
        MangaTag("Tragedy", "Tragedy", source),
        MangaTag("Bara", "Bara", source),
        MangaTag("Shotacon", "Shotacon", source),
        MangaTag("Webtoons", "Webtoons", source),
    )

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        if (!filter.query.isNullOrBlank()) {
            val url = buildString {
                append("$baseUrl/r/l_search?name=")
                append(filter.query.urlEncoded())
                append("&page=")
                append(page)
            }
            return parseMangaList(webClient.httpGet(url).parseHtml())
        }

        val url = buildString {
            append("$baseUrl/genre/")
            if (filter.tags.isNotEmpty()) {
                filter.tags.joinTo(this, ",") { it.key }
            } else {
                append("all")
            }
            append("/$page/?")

            val states = filter.states
            val showFinished = states.isEmpty() || states.contains(MangaState.FINISHED)
            val showOngoing = states.isEmpty() || states.contains(MangaState.ONGOING)
            append("f=").append(if (showFinished) "1" else "0")
            append("&o=").append(if (showOngoing) "1" else "0")

            append("&sortby=")
            when (order) {
                SortOrder.POPULARITY -> append("view")
                SortOrder.UPDATED, SortOrder.NEWEST -> append("update_date")
                else -> append("update_date")
            }

            append("&e=")
            if (filter.tagsExclude.isNotEmpty()) {
                filter.tagsExclude.joinTo(this, ",") { it.key }
            }
        }
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".box, .updatesli, .pic_list > li").mapNotNull { element ->
            val linkElement = element.selectFirst(".thm-effect") ?: return@mapNotNull null
            val href = linkElement.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = linkElement.attr("title").ifEmpty {
                linkElement.selectFirst("h2 a")?.text() ?: return@mapNotNull null
            }
            val thumbnailElem = linkElement.selectFirst("img") ?: return@mapNotNull null
            val thumbnailUrl = thumbnailElem.attr("abs:data-src").ifEmpty {
                thumbnailElem.attr("abs:src")
            }
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = title,
                altTitles = emptySet(),
                coverUrl = thumbnailUrl,
                largeCoverUrl = null,
                description = null,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val infoBlock = doc.requireElementById("information")

        val rawTitle = doc.selectFirstOrThrow(".w-title h1").text()
        val title = if (removeTitleVersion) rawTitle.replace(TITLE_REGEX, "").trim() else rawTitle
        val thumbnail = infoBlock.selectFirstOrThrow("img").absUrl("src")

        val description = infoBlock.selectFirst(".manga_summary")?.let { summary ->
            summary.selectFirst("font")?.remove()
            summary.text()
        }.orEmpty()

        var author: String? = null
        var genres = emptySet<MangaTag>()
        var state: MangaState? = null
        val altNames = mutableListOf<String>()

        infoBlock.select(".manga_info li, .manga_right tr").forEach { el ->
            val label = el.selectFirst("b, label")?.text()?.lowercase() ?: return@forEach
            when (label) {
                "alternative:" -> {
                    val raw = el.text().removePrefix(el.selectFirst("b, label")?.text() ?: "").trim()
                    val split = if ('/' in raw || ';' in raw) raw.split('/', ';') else raw.split(',')
                    altNames += split.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.equals("None", ignoreCase = true) }
                }
                "status:" -> state = when (el.selectFirstOrThrow("span").text().lowercase()) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                }
                "author(s):", "author:" -> author = el.select("a").joinToString { it.text() }
                "genre(s):" -> genres = el.select("a").mapToSet {
                    MangaTag(key = it.text(), title = it.text(), source = source)
                }
            }
        }

        val chapters = parseChapterList(doc)

        return manga.copy(
            title = title,
            altTitles = altNames.toSet(),
            coverUrl = thumbnail,
            description = description.ifEmpty { null },
            authors = author?.let { setOf(it) } ?: emptySet(),
            tags = genres,
            state = state,
            chapters = chapters.sortedBy { it.number },
        )
    }

    private data class ChapterParseData(
        val name: String,
        val url: String,
        val dateUpload: Long,
        val scanlator: String?,
    )

    private fun parseChapterList(doc: Document): List<MangaChapter> {
        val rawChapters = doc.select("table#chapter_table > tbody > tr, table.uk-table > tbody > tr")
            .mapNotNull { element ->
                val link = element.selectFirst("a.chico") ?: return@mapNotNull null
                val url = link.attrAsAbsoluteUrlOrNull("href") ?: return@mapNotNull null
                if (removeRaws && url.contains("/raw/")) return@mapNotNull null
                val name = link.text().trim()
                val dateText = element.select("td:last-child").text().trim()
                val dateUpload = runCatching { dateFormat.parse(dateText)?.time }.getOrNull() ?: 0L
                val scanlator = element.selectFirst("td.no a, td.uk-table-shrink a")?.text()?.trim()
                    ?.ifEmpty { null }
                ChapterParseData(name, url, dateUpload, scanlator)
            }

        return buildChapterList(rawChapters)
    }

    private fun buildChapterList(chapters: List<ChapterParseData>): List<MangaChapter> {
        val result = mutableListOf<MangaChapter>()

        val seen = mutableSetOf<Pair<Float, String>>()
        val regular = mutableListOf<Pair<Float, ChapterParseData>>()
        val special = mutableListOf<ChapterParseData>()

        for (chapter in chapters) {
            val num = extractChapterNumber(chapter.name)
            val scanlator = chapter.scanlator ?: extractTitleSuffix(chapter.name) ?: "Unknown"
            if (num != null) {
                val key = num to scanlator
                if (seen.add(key)) regular.add(num to chapter)
            } else {
                special.add(chapter)
            }
        }

        regular.sortedBy { it.first }.forEach { (num, chapter) ->
            val scanlator = chapter.scanlator ?: extractTitleSuffix(chapter.name) ?: "Unknown"
            result.add(
                MangaChapter(
                    id = stableChapterId(chapter.dateUpload, chapter.name, scanlator),
                    url = chapter.url,
                    title = chapter.name,
                    number = num,
                    volume = 0,
                    uploadDate = chapter.dateUpload,
                    scanlator = scanlator,
                    branch = scanlator,
                    source = source,
                ),
            )
        }

        val baseSpecial = (regular.maxOfOrNull { it.first } ?: 0f) + 10000f
        special.forEachIndexed { index, chapter ->
            val scanlator = chapter.scanlator ?: extractTitleSuffix(chapter.name) ?: "Unknown"
            result.add(
                MangaChapter(
                    id = stableChapterId(chapter.dateUpload, chapter.name, scanlator),
                    url = chapter.url,
                    title = chapter.name,
                    number = baseSpecial + index,
                    volume = 0,
                    uploadDate = chapter.dateUpload,
                    scanlator = scanlator,
                    branch = scanlator,
                    source = source,
                ),
            )
        }

        return result
    }

    private fun stableChapterId(date: Long, name: String, scanlator: String?): Long =
        MessageDigest.getInstance("MD5")
            .digest("$date:$name:${scanlator.orEmpty()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .takeLast(15)
            .toLong(16)

    private fun extractChapterNumber(title: String): Float? {
        val num = title.extractChapterNumber()
        return if (num == 0f) null else num
    }

    private fun extractTitleSuffix(title: String): String? {
        val regex = Regex("""(?:ch\.?|chapter)\s*\d+(?:\.\d+)?\s*[:\-]\s*(.+)""", RegexOption.IGNORE_CASE)
        return regex.find(title)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val doc = webClient.httpGet(seed.url.toAbsoluteUrl(domain)).parseHtml()

        val sameAuthor = doc
            .select("div.also_like:has(h4:contains(Other manga by the same author)) + .pic_list .updatesli")
            .mapNotNull { element ->
                val link = element.selectFirst(".thm-effect") ?: return@mapNotNull null
                val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
                Manga(
                    id = generateUid(href),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    title = link.attr("title").ifEmpty { link.selectFirst("h2 a")?.text() ?: return@mapNotNull null },
                    altTitles = emptySet(),
                    coverUrl = link.selectFirst("img")?.attr("abs:data-src")?.ifEmpty {
                        link.selectFirst("img")?.attr("abs:src") ?: ""
                    } ?: "",
                    largeCoverUrl = null,
                    description = null,
                    tags = emptySet(),
                    authors = emptySet(),
                    state = null,
                    rating = RATING_UNKNOWN,
                    contentRating = ContentRating.SAFE,
                    source = source,
                )
            }

        val alsoLiked = doc.select(".also-like li").mapNotNull { element ->
            val link = element.selectFirst("h4 a[href*=\"/read-manga/\"][title]") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                title = link.attr("title").ifEmpty { link.text() },
                altTitles = emptySet(),
                coverUrl = element.selectFirst("img")?.attr("abs:data-src")?.ifEmpty {
                    element.selectFirst("img")?.attr("abs:src") ?: ""
                } ?: "",
                largeCoverUrl = null,
                description = null,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                source = source,
            )
        }

        return (sameAuthor + alsoLiked).distinctBy { it.url }
    }

    private val jsCache = mutableMapOf<String, Pair<String, Long>>()
    private val maxCacheTime = 1000 * 60 * 5 // 5 minutes

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        val pageDropdown = doc.select("div.controls ul#dropdown-menu-page")
        if (pageDropdown.isNotEmpty()) {
            val pagesCount = pageDropdown.select("li").size

            val cleanUrl = fullUrl.removeSuffix("/")
            val lastSegment = cleanUrl.substringAfterLast("/")

            val isPgFormat = lastSegment.startsWith("pg-")
            val pageNumber = lastSegment.toIntOrNull()
            val isPageNumber = pageNumber != null && pageNumber < 1000

            val baseUrl: String
            val buildBatchUrl: (Int) -> String

            if (isPgFormat) {
                baseUrl = cleanUrl.substringBeforeLast("/")
                buildBatchUrl = { batchNum -> "$baseUrl/pg-$batchNum/" }
            } else if (isPageNumber) {
                baseUrl = cleanUrl.substringBeforeLast("/")
                buildBatchUrl = { batchNum -> "$baseUrl/$batchNum/" }
            } else {
                baseUrl = cleanUrl
                buildBatchUrl = { batchNum -> "$baseUrl/$batchNum/" }
            }

            val batchSize = 5
            val allImages = mutableListOf<String>()
            var batchStart = 1

            val js = getDeobfuscatedJS(doc)
            val cols = getColsFromDoc(doc) ?: ""

            while (allImages.size < pagesCount) {
                val batchUrl = buildBatchUrl(batchStart)
                val batchDoc = if (batchStart == 1) doc else webClient.httpGet(batchUrl).parseHtml()
                val batchImages = decryptImageList(batchDoc)
                if (batchImages.isEmpty()) break

                allImages.addAll(batchImages)
                batchStart += batchSize

                if (batchStart > pagesCount + batchSize) break
            }

            return allImages.take(pagesCount).mapIndexed { index, imageUrl ->
                val url = if (imageUrl.contains("cspiclink") && js != null) {
                    val descramblingKey = getDescramblingKey(js, imageUrl)
                    "$imageUrl#desckey=$descramblingKey&cols=$cols"
                } else {
                    imageUrl
                }
                MangaPage(
                    id = generateUid("$fullUrl-$index"),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }

        val imgsrcsScript = doc.selectFirst("script:containsData(imgsrcs)")?.html()
        if (imgsrcsScript != null) {
            val images = decryptImageList(doc)
            val cols = getColsFromDoc(doc) ?: ""
            val js = getDeobfuscatedJS(doc) ?: throw Exception("Could not get JS")

            return images.mapIndexed { index, imageUrl ->
                val url = if (imageUrl.contains("cspiclink")) {
                    val descramblingKey = getDescramblingKey(js, imageUrl)
                    "$imageUrl#desckey=$descramblingKey&cols=$cols"
                } else {
                    imageUrl
                }
                if (url.isBlank()) throw Exception("Final image URL at index $index is blank after processing")
                MangaPage(
                    id = generateUid("$fullUrl-$index"),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
        }

        throw Exception("Could not find pages")
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        if (page.url.isBlank()) throw Exception("Page URL is blank in getPageUrl")
        return page.url
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val fragment = response.request.url.fragment
        if (fragment == null || !fragment.contains("desckey=")) return response

        return context.redrawImageResponse(response) { bitmap ->
            val key = fragment.substringAfter("desckey=").substringBefore("&")
            val cols = fragment.substringAfter("cols=").toIntOrNull()
                ?: return@redrawImageResponse bitmap
            unscrambleImage(bitmap, key, cols)
        }
    }

    private fun unscrambleImage(bitmap: Bitmap, key: String, cols: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = context.createBitmap(width, height)

        val unitWidth = width / cols
        val unitHeight = height / cols
        val keyArray = key.split("a")

        for (idx in 0 until cols * cols) {
            val keyval = keyArray.getOrNull(idx)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

            val heightY = keyval / cols
            val dy = heightY * unitHeight
            val dx = (keyval - heightY * cols) * unitWidth

            val widthY = idx / cols
            val sy = widthY * unitHeight
            val sx = (idx - widthY * cols) * unitWidth

            val w = min(unitWidth, width - dx)
            val h = min(unitHeight, height - dy)

            val srcRect = Rect(sx, sy, sx + w, sy + h)
            val dstRect = Rect(dx, dy, dx + w, dy + h)

            result.drawBitmap(bitmap, srcRect, dstRect)
        }

        return result
    }

    private suspend fun decryptImageList(doc: Document): List<String> {
        val imgsrcsScript = doc.selectFirst("script:containsData(imgsrcs)")?.html()
            ?: throw Exception("Could not find imgsrcs")

        val imgsrcRaw = IMG_SRCS_REGEX.find(imgsrcsScript)?.groupValues?.get(1)
            ?: throw Exception("Could not extract imgsrcs")

        val imgsrcs = context.decodeBase64(imgsrcRaw)
        val deobfChapterJs = getDeobfuscatedJS(doc) ?: throw Exception("Could not deobfuscate JS")

        val key = findHexEncodedVariable(deobfChapterJs, "key").decodeHex()
        val iv = findHexEncodedVariable(deobfChapterJs, "iv").decodeHex()

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decryptedBytes = cipher.doFinal(imgsrcs)

        var imageList = String(decryptedBytes, Charsets.UTF_8).trimEnd('\u0000')
        imageList = unscrambleImageList(imageList, deobfChapterJs)

        return imageList.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { url ->
                if (url.startsWith("https://") && url.contains("/_") || url.contains("https://iweb_")) {
                    url.replaceFirst("https://", "http://")
                } else {
                    url
                }
            }
    }

    private suspend fun getDeobfuscatedJS(doc: Document): String? {
        val chapterJsUrl = doc.select("script[src*=chapter.js]").firstOrNull()?.absUrl("src") ?: return null
        val now = System.currentTimeMillis()
        val cached = jsCache[chapterJsUrl]
        if (cached != null && now - cached.second < maxCacheTime) return cached.first

        val obfuscatedChapterJs = webClient.httpGet(chapterJsUrl).parseRaw()
        val deobf = deobfuscateSoJsonV4(obfuscatedChapterJs)
        jsCache[chapterJsUrl] = Pair(deobf, now)
        return deobf
    }

    private fun getColsFromDoc(doc: Document): String? {
        val chapterJsUrl = doc.select("script[src*=chapter.js]").firstOrNull()?.absUrl("src")
        val cached = if (chapterJsUrl != null) jsCache[chapterJsUrl] else null
        val js = cached?.first ?: return null
        return COLS_REGEX.find(js)?.groupValues?.get(1)
    }

    private fun deobfuscateSoJsonV4(jsf: String): String {
        if (!jsf.startsWith("['sojson.v4']")) {
            throw Exception("Obfuscated code header mismatch. Expected sojson.v4")
        }
        val splitRegex = Regex("[a-zA-Z]+")
        val args = jsf.substring(240, jsf.length - 59).split(splitRegex)
        return args.map { it.toInt().toChar() }.joinToString("")
    }

    private fun findHexEncodedVariable(input: String, variable: String): String {
        val regex = Regex("""var $variable\s*=\s*CryptoJS\.enc\.Hex\.parse\("([0-9a-zA-Z]+)"\)""")
        return regex.find(input)?.groupValues?.get(1)
            ?: throw Exception("Could not find variable: $variable")
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun unscrambleImageList(imageList: String, js: String): String {
        var imgList = imageList
        val keyLocations = KEY_LOCATION_REGEX.findAll(js)
            .map { it.groupValues[1].toInt() }
            .distinct()
            .sorted()
            .toList()
        if (keyLocations.isEmpty()) return imgList

        val unscrambleKey = try {
            keyLocations.map { loc ->
                if (loc >= imgList.length) throw NumberFormatException("Position $loc is beyond string length")
                imgList[loc].toString().toInt()
            }
        } catch (_: NumberFormatException) {
            return imgList
        }

        keyLocations.forEachIndexed { idx, loc ->
            imgList = imgList.removeRange((loc - idx)..(loc - idx))
        }
        imgList = imgList.unscramble(unscrambleKey)
        return imgList
    }

    private fun String.unscramble(keys: List<Int>): String {
        var s = this
        keys.reversed().forEach { key ->
            for (i in s.length - 1 downTo key) {
                if (i % 2 != 0) {
                    val sourceIdx = i - key
                    if (sourceIdx >= 0) {
                        val temp = s[sourceIdx]
                        s = s.substring(0, sourceIdx) + s[i] + s.substring(sourceIdx + 1)
                        s = s.substring(0, i) + temp + s.substring(i + 1)
                    }
                }
            }
        }
        return s
    }

    private suspend fun getDescramblingKey(deobfChapterJs: String, imageUrl: String): String {
        val imgkeys = deobfChapterJs
            .substringAfter("var renImg = function(img,width,height,id){", "")
            .substringBefore("key = key.split(", "")
            .split("\n")
            .filter { line -> JS_FILTERS.none { filter -> line.contains(filter) } }
            .joinToString("\n")
            .replace("img.src", "url")

        if (imgkeys.isEmpty()) throw Exception("Failed to extract image key extraction code from chapter.js")

        val js = """
            function replacePos(strObj, pos, replacetext) {
                var str = strObj.substr(0, pos) + replacetext + strObj.substring(pos + 1, strObj.length);
                return str;
            }
            function getDescramblingKey(url) { $imgkeys; return key; }
            getDescramblingKey("$imageUrl");
        """.trimIndent()

        val result = context.evaluateJs("https://$domain/", js)
        if (result.isNullOrEmpty()) throw Exception("Failed to evaluate JavaScript to get descrambling key.")
        return result
    }

    private companion object {
        private val IMG_SRCS_REGEX = Regex("""var imgsrcs\s*=\s*['"]([a-zA-Z0-9+=/]+)['"]""")
        private val COLS_REGEX = Regex("""var\s*widthnum\s*=\s*heightnum\s*=\s*(\d+);""")
        private val KEY_LOCATION_REGEX = Regex("""str\.charAt\(\s*(\d+)\s*\)""")
        private val JS_FILTERS = listOf(
            "jQuery", "document", "getContext", "toDataURL", "getImageData", "width", "height",
        )

        private val TITLE_REGEX = Regex(
            """^(?:\s*(?:\([^()]*\)|\{[^{}]*\}|\[(?:(?!]).)*]|«[^»]*»|〘[^〙]*〙|「[^」]*」|『[^』]*』|≪[^≫]*≫|﹛[^﹜]*﹜|〖[^〖〗]*〗|𖤍.+?𖤍|《[^》]*》|⌜.+?⌝|⟨[^⟩]*⟩)\s*)+|(?:\s*(?:\([^()]*\)|\{[^{}]*\}|\[(?:(?!]).)*]|«[^»]*»|〘[^〙]*〙|「[^」]*」|『[^』]*』|≪[^≫]*≫|﹛[^﹜]*﹜|〖[^〖〗]*〗|𖤍.+?𖤍|《[^》]*》|⌜.+?⌝|⟨[^⟩]*⟩|/\s*Official)\s*)+$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
