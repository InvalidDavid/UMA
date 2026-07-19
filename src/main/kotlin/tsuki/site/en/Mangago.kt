package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.bitmap.Bitmap
import tsuki.bitmap.Rect

import tsuki.model.*
import tsuki.util.*

import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

@MangaSourceParser("MANGAGO", "Mangago", "en")
internal class MangagoParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAGO, pageSize = 20), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("www.mangago.me")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
        SortOrder.NEWEST
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

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query.orEmpty()
        if (!query.isNullOrEmpty()) {
            val url = buildString {
                append("https://")
                append(domain)
                append("/r/l_search?name=")
                append(query.urlEncoded())
                append("&page=")
                append(page)
            }
            return parseMangaList(webClient.httpGet(url).parseHtml())
        }

        val url = when (order) {
            SortOrder.NEWEST -> {
                buildString {
                    append("https://")
                    append(domain)
                    append("/list/new/")
                    if (filter.tags.isNotEmpty()) {
                        filter.tags.joinTo(this, ",") { it.key }
                    } else {
                        append("all")
                    }
                    append("/")
                    append(page)
                    append("/")
                }
            }
            else -> {
                buildString {
                    append("https://")
                    append(domain)
                    append("/genre/")
                    if (filter.tags.isNotEmpty()) {
                        filter.tags.joinTo(this, ",") { it.key }
                    } else {
                        append("all")
                    }
                    append("/")
                    append(page)
                    append("/?")
                    val states = filter.states
                    val showFinished = states.isEmpty() || states.contains(MangaState.FINISHED)
                    val showOngoing = states.isEmpty() || states.contains(MangaState.ONGOING)
                    append("f=").append(if (showFinished) "1" else "0")
                    append("&o=").append(if (showOngoing) "1" else "0")
                    append("&sortby=")
                    when (order) {
                        SortOrder.POPULARITY -> append("view")
                        SortOrder.UPDATED -> append("update_date")
                        else -> append("update_date")
                    }
                    append("&e=")
                    if (filter.tagsExclude.isNotEmpty()) {
                        filter.tagsExclude.joinTo(this, ",") { it.key }
                    }
                }
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

        val title = doc.selectFirstOrThrow(".w-title h1").text()
        val thumbnail = infoBlock.selectFirstOrThrow("img").absUrl("src")

        var description = infoBlock.selectFirst(".manga_summary")?.let { summary ->
            summary.selectFirst("font")?.remove()
            summary.text()
        }.orEmpty()

        var author: String? = null
        var genres = emptySet<MangaTag>()
        var state: MangaState? = null

        infoBlock.select(".manga_info li, .manga_right tr").forEach { el ->
            when (el.selectFirst("b, label")?.text()?.lowercase()) {
                "alternative:" -> description += "\n\n${el.text()}"
                "status:" -> state = when (el.selectFirstOrThrow("span").text().lowercase()) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                }
                "author(s):", "author:" -> author = el.select("a").joinToString { it.text() }
                "genre(s):" -> genres = el.select("a").mapToSet {
                    MangaTag(
                        key = it.text(),
                        title = it.text(),
                        source = source,
                    )
                }
            }
        }

        val chapters = parseChapterList(doc)

        return manga.copy(
            title = title,
            coverUrl = thumbnail,
            description = description,
            authors = author?.let { setOf(it) } ?: emptySet(),
            tags = genres,
            state = state,
            chapters = chapters,
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
                val name = link.text().trim()

                val dateText = element.select("td:last-child").text().trim()
                val dateUpload = runCatching {
                    dateFormat.parse(dateText)?.time
                }.getOrNull() ?: 0L

                val scanlator = element.selectFirst("td.no a, td.uk-table-shrink a")?.text()?.trim()
                    ?.ifEmpty { null }

                ChapterParseData(name, url, dateUpload, scanlator)
            }
            .reversed()

        return buildChapterList(rawChapters)
    }

    private fun buildChapterList(chapters: List<ChapterParseData>): List<MangaChapter> {
        val scanlatorCounts = mutableMapOf<String, Int>()
        for (chapter in chapters) {
            val scanlator = chapter.scanlator ?: extractTitleSuffix(chapter.name) ?: continue
            scanlatorCounts[scanlator] = (scanlatorCounts[scanlator] ?: 0) + 1
        }
        val preferredScanlator = scanlatorCounts.maxByOrNull { it.value }?.key

        val regularChapters = mutableMapOf<Float, ChapterParseData>()
        val specialChapters = mutableListOf<ChapterParseData>()

        for (chapter in chapters) {
            val chapterNum = extractChapterNumber(chapter.name)
            if (chapterNum != null) {
                val existing = regularChapters[chapterNum]
                if (existing == null) {
                    regularChapters[chapterNum] = chapter
                } else {
                    val existingSource = existing.scanlator ?: extractTitleSuffix(existing.name)
                    val newSource = chapter.scanlator ?: extractTitleSuffix(chapter.name)

                    val existingIsPreferred = existingSource == preferredScanlator
                    val newIsPreferred = newSource == preferredScanlator

                    if (newIsPreferred && !existingIsPreferred) {
                        regularChapters[chapterNum] = chapter
                    } else if (!newIsPreferred && existingIsPreferred) {
                    } else {
                        if (chapter.name.length > existing.name.length) {
                            regularChapters[chapterNum] = chapter
                        }
                    }
                }
            } else {
                specialChapters.add(chapter)
            }
        }

        val result = mutableListOf<MangaChapter>()

        val sortedRegular = regularChapters.entries.sortedBy { it.key }
        for ((chapterNum, chapter) in sortedRegular) {
            result.add(
                MangaChapter(
                    id = generateUid(chapter.url),
                    url = chapter.url,
                    title = chapter.name,
                    number = chapterNum,
                    volume = 0,
                    uploadDate = chapter.dateUpload,
                    scanlator = chapter.scanlator,
                    branch = null,
                    source = source,
                ),
            )
        }

        val baseSpecialNumber = (regularChapters.keys.maxOrNull() ?: 0f) + 10000f
        for ((index, chapter) in specialChapters.withIndex()) {
            result.add(
                MangaChapter(
                    id = generateUid(chapter.url),
                    url = chapter.url,
                    title = chapter.name,
                    number = baseSpecialNumber + index,
                    volume = 0,
                    uploadDate = chapter.dateUpload,
                    scanlator = chapter.scanlator,
                    branch = null,
                    source = source,
                ),
            )
        }

        return result
    }

    private fun extractTitleSuffix(title: String): String? {
        val regex = Regex("""(?:ch\.?|chapter)\s*\d+(?:\.\d+)?\s*[:\-]\s*(.+)""", RegexOption.IGNORE_CASE)
        return regex.find(title)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractChapterNumber(title: String): Float? {
        return try {
            val regex = Regex("""(?:ch\.?|chapter|vol\.?\s*\d+\s+ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            val match = regex.find(title)
            match?.groupValues?.get(1)?.toFloat()
        } catch (e: Exception) {
            null
        }
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
                val batchImages = decryptImageList(batchDoc, batchUrl)

                if (batchImages.isEmpty()) {
                    break
                }

                allImages.addAll(batchImages)
                batchStart += batchSize

                if (batchStart > pagesCount + batchSize) {
                    break
                }
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
            val images = decryptImageList(doc, fullUrl)
            val cols = getColsFromDoc(doc) ?: ""
            val js = getDeobfuscatedJS(doc) ?: throw Exception("Could not get JS")

            return images.mapIndexed { index, imageUrl ->
                val url = if (imageUrl.contains("cspiclink")) {
                    val descramblingKey = getDescramblingKey(js, imageUrl)
                    "$imageUrl#desckey=$descramblingKey&cols=$cols"
                } else {
                    imageUrl
                }

                if (url.isBlank()) {
                    throw Exception("Final image URL at index $index is blank after processing")
                }

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
        if (page.url.isBlank()) {
            throw Exception("Page URL is blank in getPageUrl")
        }
        return page.url
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private suspend fun decryptImageList(doc: Document, sourceUrl: String): List<String> {
        val imgsrcsScript = doc.selectFirst("script:containsData(imgsrcs)")?.html()
            ?: throw Exception("Could not find imgsrcs")

        val imgsrcRaw = IMG_SRCS_REGEX.find(imgsrcsScript)?.groupValues?.get(1)
            ?: throw Exception("Could not extract imgsrcs")

        val imgsrcs = context.decodeBase64(imgsrcRaw)

        val deobfChapterJs = getDeobfuscatedJS(doc) ?: throw Exception("Could not deobfuscate JS")

        val key = findHexEncodedVariable(deobfChapterJs, "key").decodeHex()
        val iv = findHexEncodedVariable(deobfChapterJs, "iv").decodeHex()

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
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
        if (cached != null && now - cached.second < maxCacheTime) {
            return cached.first
        }

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

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val fragment = response.request.url.fragment

        if (fragment == null || !fragment.contains("desckey=")) {
            return response
        }

        return context.redrawImageResponse(response) { bitmap ->
            val key = fragment.substringAfter("desckey=").substringBefore("&")
            val cols = fragment.substringAfter("cols=").toIntOrNull() ?: return@redrawImageResponse bitmap
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

        if (keyLocations.isEmpty()) {
            return imgList
        }

        val unscrambleKey = try {
            keyLocations.map { loc ->
                if (loc >= imgList.length) {
                    throw NumberFormatException("Position $loc is beyond string length")
                }
                imgList[loc].toString().toInt()
            }
        } catch (e: NumberFormatException) {
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

        if (imgkeys.isEmpty()) {
            throw Exception("Failed to extract image key extraction code from chapter.js")
        }

        val js = """
            function replacePos(strObj, pos, replacetext) {
                var str = strObj.substr(0, pos) + replacetext + strObj.substring(pos + 1, strObj.length);
                return str;
            }
            function getDescramblingKey(url) { $imgkeys; return key; }
            getDescramblingKey("$imageUrl");
        """.trimIndent()

        val result = context.evaluateJs("https://$domain/", js)

        if (result.isNullOrEmpty()) {
            throw Exception("Failed to evaluate JavaScript to get descrambling key. The image format or site structure may have changed.")
        }

        return result
    }

    private companion object {
        private val IMG_SRCS_REGEX = Regex("""var imgsrcs\s*=\s*['"]([a-zA-Z0-9+=/]+)['"]""")
        private val COLS_REGEX = Regex("""var\s*widthnum\s*=\s*heightnum\s*=\s*(\d+);""")
        private val KEY_LOCATION_REGEX = Regex("""str\.charAt\(\s*(\d+)\s*\)""")
        private val JS_FILTERS = listOf("jQuery", "document", "getContext", "toDataURL", "getImageData", "width", "height")
    }
}