package tsuki.site.en.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.parsers.GalleryAdultsParser

import tsuki.util.*
import tsuki.model.*

import org.json.JSONArray
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Base64

private const val SERVER_PNG = "png" // old method uses now image_fallback
private const val SERVER_WEBP = "webp" // new decryption method for .webp/.avif format

@MangaSourceParser("HENTAINEXUS", "HentaiNexus", "en", type = ContentType.HENTAI)
internal class HentaiNexus(context: MangaLoaderContext) :
    GalleryAdultsParser(context, MangaParserSource.HENTAINEXUS, "hentainexus.com", 30) {
    override val selectGallery = "div.container div.columns div.column"
    override val selectGalleryLink = "a"
    override val selectGalleryTitle = ".card-header"
    override val selectTitle = ".title"
    override val selectTag = "tr:contains(Tags) td:nth-child(2) span.tag a"
    override val selectAuthor = "tr:contains(Artist) td:nth-child(2) a"
    override val selectLanguageChapter = ""
    override val selectUrlChapter = ""
    override val selectTotalPage = "div.column.is-2-fullhd"

    val selectReadUrl = "a:contains(Read Online)"
    val selectPublisher = "tr:contains(Publisher) td:nth-child(2)"
    val selectPublishedDate = "tr:contains(Published) td:nth-child(2)"
    val selectDescription = "tr:contains(Description) td:nth-child(2)"

    var mangaInternalId: String = ""                    /* use as a flag for reloading data */
    var mangaPages: List<MangaPage> = listOf()

    private val pageCache = object : LinkedHashMap<String, List<String>>(10, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<String>>
        ): Boolean {
            return size > 5
        }
    }

    private val preferredImageModeKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            _root_ide_package_.tsuki.site.en.hentais.SERVER_PNG to "Old method (png)",
            _root_ide_package_.tsuki.site.en.hentais.SERVER_WEBP to "New method (webp/avif)",
        ),
        defaultValue = _root_ide_package_.tsuki.site.en.hentais.SERVER_PNG,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(preferredImageModeKey)
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isMultipleTagsSupported = true,
            isAuthorSearchSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val document = webClient.httpGet("https://$domain/explore/categories/tag").parseHtml()
        val tags = document.select("div.container div.columns div.column").mapToSet {div ->
            val tag = div.selectFirstOrThrow("a").attr("href")
                .substring(8) // href="/?q=tag:value"
                .urlDecode()
                .trim(' ', '\"')
            MangaTag(
                title = tag.replace(Regex("\\b[a-z]")) { it.value.uppercase(sourceLocale) },
                key = tag,
                source = source,
            )
        }

        return MangaListFilterOptions(
            availableTags = tags
        )
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = buildString {
            append("https://$domain/page/$page")
            when {
                !filter.query.isNullOrEmpty() -> {
                    val query = filter.query
                    append("?q=")
                    append(query?.urlEncoded())
                }

                else -> {
                    val queries = mutableListOf<String>()

                    if (!filter.author.isNullOrEmpty()) {
                        queries.add("artist:${filter.author}")
                    }

                    filter.tags.map {
                        val key = it.key
                        when {
                            key.split(" ").count() > 1 -> {
                                "\"${key.replace(Regex("\\s+")) { "+" }}\""
                            }
                            else -> {
                                key
                            }
                        }
                    }.also {
                        if (it.count() > 0) {
                            queries.add("tag:${ it.joinToString("+") }")
                        }
                    }

                    if (queries.count() > 0) {
                        append("?q=${queries.joinToString("+")}")
                    }
                }
            }
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(selectGallery).map { div ->
            val href = div.selectFirstOrThrow(selectGalleryLink).attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                title = div.select(selectGalleryTitle).text().cleanupTitle(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = div.selectFirst(selectGalleryImg)?.src(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val tags = doc.select(selectTag).mapToSet {
            val value = it.attr("href").substring(8).urlDecode().trim('\"')       /* /?q=tag:"blow job" */
            MangaTag(
                title = value.replace(Regex("\\b[a-z]")) { x -> x.value.uppercase(sourceLocale) },
                key = value,
                source = source
            )
        }
        val authors = doc.select(selectAuthor).mapToSet {
            it.attr("href").substring(11).urlDecode()                 /* /?q=artist:Danchino */
        }

        mangaPages = getPagesInternal(manga.url, doc)
        mangaInternalId = manga.url.split("/").last()

        val format = SimpleDateFormat("dd MMMM yyyy")

        return manga.copy(
            title = doc.select(selectTitle).text().cleanupTitle(),
            tags = tags,
            authors = authors,
            chapters = listOf(
                MangaChapter(
                    id = manga.id,
                    title = manga.title,
                    number = 0f,
                    volume = 0,
                    url = manga.url,
                    scanlator = doc.select(selectPublisher).text().replace(Regex(" \\([\\d,]+\\)")) { "" },
                    uploadDate = format.parseSafe(doc.select(selectPublishedDate).text()),
                    branch = "English",
                    source = source,
                ),
            ),
            description = doc.selectFirst(selectDescription)?.text() ?: ""
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        if (mangaPages.isEmpty() || !chapter.url.contains(mangaInternalId)) {
            mangaPages = getPagesInternal(chapter.url)
            mangaInternalId = chapter.url.split("/").last()

            // preload image urls
            val reader = mangaPages.firstOrNull()?.url?.substringBefore("#")
            if (reader != null && !pageCache.containsKey(reader)) {
                pageCache[reader] = getPageUrlInternal(reader)
            }
        }

        return mangaPages
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        val readerUrl = page.url.substringBefore("#")

        val pages = pageCache[readerUrl]
            ?: getPageUrlInternal(readerUrl).also {
                pageCache[readerUrl] = it
            }

        val index = page.url.substringAfter("#").toInt() - 1

        return pages[index]
    }

    private suspend fun getPagesInternal(chapterUrl: String, document: Document? = null): List<MangaPage> {
        val document = document ?: webClient.httpGet(chapterUrl.toAbsoluteUrl(domain)).parseHtml()
        val readUrl = document.selectFirstOrThrow(selectReadUrl).attr("href")

        return document.select(selectTotalPage).mapIndexed { index, element ->
            val url = "$readUrl#${index + 1}"
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = element.select("img").attr("src"),
                source = source,
            )
        }
    }

    private suspend fun getPageUrlInternal(pageUrl: String): List<String> {
        val doc = webClient
            .httpGet(pageUrl.toAbsoluteUrl(domain))
            .parseHtml()
        val script = doc.select("script")
            .firstOrNull { it.data().contains("initReader") }
            ?: throw Exception("Reader script not found")
        val encryptedPagesData = script.data()
            .substringAfter("initReader(\"")
            .substringBefore("\",")
            .replace("\\n", "")
            .replace("\\r", "")
            .trim()
        val decryptedString = decrypt(encryptedPagesData)
            .replace("\\/", "/")
        val jsonArray = JSONArray(decryptedString)
        val mode = config[preferredImageModeKey] ?: _root_ide_package_.tsuki.site.en.hentais.SERVER_WEBP
        return buildList {
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val url = when (mode) {
                    _root_ide_package_.tsuki.site.en.hentais.SERVER_PNG -> {
                        val image = item.optString("image")
                            .takeIf { it.isNotBlank() }
                        val fallback = item.optString("image_fallback")
                            .takeIf { it.isNotBlank() }
                        fallback ?: image
                    }
                    _root_ide_package_.tsuki.site.en.hentais.SERVER_WEBP -> {
                        var found: String? = null
                        val keys = item.keys()
                        while (keys.hasNext()) {
                            val value = item.optString(keys.next())
                            if (
                                value.startsWith("http") &&
                                (
                                        value.contains(".webp") ||
                                                value.contains(".avif") ||
                                                value.contains(".jpg") ||
                                                value.contains(".jpeg") ||
                                                value.contains(".png")
                                        )
                            ) {
                                found = value
                                break
                            }
                        }
                        found
                    }
                    else -> null
                }
                if (!url.isNullOrBlank()) {
                    add(normalizeImageUrl(url))
                }
            }
        }
    }

    private fun normalizeImageUrl(url: String): String {
        return when {
            url.startsWith("http") -> url

            url.startsWith("//") ->
                "https:$url"

            url.startsWith("/") ->
                "https://$domain$url"

            else ->
                "https://$domain/$url"
        }
    }

    private fun decrypt(encodedData: String): String {
        val xorKey = listOf('h','e','n','t','a','i','n','e','x','u','s','.','c','o','m')
        val keyLength = minOf(xorKey.size, 64)

        // Decode base64 string into characters (1 byte per char, unsigned)
        val decodedBytes = Base64.getDecoder().decode(encodedData)
        val decodedChars = decodedBytes.map { (it.toInt() and 0xFF).toChar() }.toMutableList()

        // XOR first 64 characters with the key
        for (i in 0 until keyLength) {
            decodedChars[i] = (decodedChars[i].code xor xorKey[i].code).toChar()
        }

        val decodedString = decodedChars.joinToString("")

        // Prime sieve: first 16 primes
        val sieve = BooleanArray(257)
        val primeIndexes = mutableListOf<Int>()
        var j = 2
        while (primeIndexes.size < 16) {
            if (!sieve[j]) {
                primeIndexes.add(j)
                for (k in j * 2..256 step j) sieve[k] = true
            }
            j++
        }

        // Hash from first 64 chars
        var hash = 0
        for (k in 0 until 64) {
            hash = hash xor decodedString[k].code
            repeat(8) {
                hash = if ((hash and 1) == 1) {
                    (hash ushr 1) xor 12
                } else {
                    hash ushr 1
                }
            }
        }
        hash = hash and 7

        // RC4 key scheduling
        val s = IntArray(256) { it }
        j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + decodedString[i % 64].code) % 256
            s[i] = s[j].also { s[j] = s[i] }
        }

        // Decrypt rest of the chars
        val result = StringBuilder()
        var i = 0
        j = 0
        var keyStream = 0
        var rnd = 0
        val step = primeIndexes[hash]

        for (n in 0 until decodedString.length - 64) {
            i = (i + step) % 256
            j = (keyStream + s[(j + s[i]) % 256]) % 256
            keyStream = (keyStream + i + s[i]) % 256
            s[i] = s[j].also { s[j] = s[i] }

            rnd = s[(j + s[(i + s[(rnd + keyStream) % 256]) % 256]) % 256]
            val decryptedChar = decodedString[n + 64].code xor rnd
            result.append(decryptedChar.toChar())
        }

        return result.toString()
    }
}