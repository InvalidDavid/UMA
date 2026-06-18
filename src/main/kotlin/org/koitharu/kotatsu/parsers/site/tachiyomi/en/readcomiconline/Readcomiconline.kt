package org.koitharu.kotatsu.parsers.site.tachiyomi.en.readcomiconline

import android.content.SharedPreferences
import android.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import rx.Observable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.util.Base64
import org.koitharu.kotatsu.parsers.TachiyomiSource
import java.net.URLDecoder

@TachiyomiSource("READCOMICONLINE", "Readcomiconline", "en")
class Readcomiconline :
    HttpSource(),
    ConfigurableSource {

    override val name = "ReadComicOnline"

    override val baseUrl: String
        get() {
            val index = preferences.getString(MIRROR_PREF, "1")!!
                .toIntOrNull() ?: 1

            return MIRROR_URLS[index.coerceIn(MIRROR_URLS.indices)]
        }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor(::captchaInterceptor).build()

    private fun captchaInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val location = response.header("Location")
        if (location?.startsWith("/Special/AreYouHuman") == true) {
            captchaUrl = "$baseUrl/Special/AreYouHuman"
            throw Exception("Solve captcha in WebView")
        }

        return response
    }

    private var captchaUrl: String? = null

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/ComicList/MostPopular?page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/ComicList/LatestUpdate?page=$page", headers)

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.text()
        thumbnail_url = element.selectFirst("img")!!.attr("abs:src")
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(".list-comic > .item > a:first-child").map { mangaFromElement(it) }
        val hasNextPage = document.selectFirst("ul.pager > li > a:contains(Next)") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("http")) {
            val url = query.toHttpUrlOrNull()
            val mirrorHosts = MIRROR_URLS.map { it.toHttpUrl().host }
            if (url != null && url.host in mirrorHosts &&
                url.pathSegments.size >= 2 && url.pathSegments[0] == "Comic"
            ) {
                val manga = SManga.create().apply {
                    this.url = "/Comic/${url.pathSegments[1]}"
                }
                return fetchMangaDetails(manga).map { details ->
                    details.url = manga.url
                    details.initialized = true
                    MangasPage(listOf(details), false)
                }
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val activeFilters = if (filters.isEmpty()) getFilterList() else filters
        val genreList = activeFilters.firstInstance<GenreList>()
        val sortOption = activeFilters.firstInstance<SortFilter>().selected
        val yearOption = activeFilters.firstInstance<YearFilter>().selected

        return if (query.isEmpty() && genreList.included.size == 1 && genreList.excluded.isEmpty() && yearOption == null) {
            // Single included genre — use /Genre/{name}/{sort} URL
            val genreName = genreList.state.first { it.isIncluded() }.name.replace(" ", "-")
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("Genre")
                addPathSegment(genreName)
                if (sortOption != null) addPathSegment(sortOption)
                addQueryParameter("page", page.toString())
            }.build()
            GET(url, headers)
        } else if (query.isEmpty() && genreList.included.isEmpty() && genreList.excluded.isEmpty() && yearOption == null) {
            // No query, no genres — publisher/writer/artist + sort
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                var pathSegmentAdded = false

                for (filter in activeFilters) {
                    when (filter) {
                        is PublisherFilter -> {
                            if (filter.state.isNotEmpty()) {
                                addPathSegments("Publisher/${filter.state.replace(" ", "-")}")
                                pathSegmentAdded = true
                            }
                        }

                        is WriterFilter -> {
                            if (filter.state.isNotEmpty()) {
                                addPathSegments("Writer/${filter.state.replace(" ", "-")}")
                                pathSegmentAdded = true
                            }
                        }

                        is ArtistFilter -> {
                            if (filter.state.isNotEmpty()) {
                                addPathSegments("Artist/${filter.state.replace(" ", "-")}")
                                pathSegmentAdded = true
                            }
                        }

                        else -> {}
                    }

                    if (pathSegmentAdded) {
                        break
                    }
                }
                if (!pathSegmentAdded) {
                    addPathSegment("ComicList")
                    if (sortOption != null) addPathSegment(sortOption)
                } else if (sortOption != null) {
                    addPathSegment(sortOption)
                }
                addQueryParameter("page", page.toString())
            }.build()
            GET(url, headers)
        } else {
            // Has query or multiple/excluded genres — AdvanceSearch
            val url = "$baseUrl/AdvanceSearch".toHttpUrl().newBuilder().apply {
                addQueryParameter("comicName", query.trim())
                addQueryParameter("page", page.toString())
                for (filter in activeFilters) {
                    when (filter) {
                        is Status -> addQueryParameter("status", filter.selected.orEmpty())

                        is GenreList -> {
                            addQueryParameter("ig", filter.included.joinToString(","))
                            addQueryParameter("eg", filter.excluded.joinToString(","))
                        }

                        is YearFilter -> {
                            if (filter.selected != null) addQueryParameter("pubDate", filter.selected!!)
                        }

                        else -> {}
                    }
                }
            }.build()
            GET(url, headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private val viewsRegex = Regex("Views:\\s*([\\d,]+)")

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val infoElement = document.selectFirst("div.barContent")!!

        val manga = SManga.create()
        manga.title = infoElement.selectFirst("a.bigChar")!!.text()
        manga.artist = infoElement.select("p:has(span:contains(Artist:)) > a").eachText().summarize()
        manga.author = infoElement.select("p:has(span:contains(Writer:)) > a").eachText().summarize()
        manga.genre = infoElement.select("p:has(span:contains(Genres:)) > a").eachText().joinToString()
        manga.description = listOfNotNull(
            infoElement.select("p:has(span:contains(Summary:)) ~ p")
                .joinToString("\n\n") { it.textPreserveLineBreaks() }
                .trim()
                .takeIf { it.isNotEmpty() },
            infoElement.select("p:has(span:contains(Publisher:))").text().takeIf { it.isNotEmpty() }?.let { "\n$it" },
            infoElement.select("p:has(span:contains(Publication date:))").text().takeIf { it.isNotEmpty() },
            viewsRegex.find(infoElement.select("p:has(span:contains(Views:))").text())
                ?.let { "Views: ${it.groupValues[1]}" },
        ).joinToString("\n")
        manga.status = infoElement.selectFirst("p:has(span:contains(Status:))")?.text().orEmpty()
            .let { parseStatus(it) }
        manga.thumbnail_url = document.selectFirst(".rightBox:eq(0) img")?.absUrl("src")
        return manga
    }

    private fun List<String>.summarize(): String = if (size > 2) "${first()} & others" else joinToString()

    private fun Element.textPreserveLineBreaks(): String {
        // Replace <br> with newline text nodes so wholeText() preserves them as line breaks.
        // .text() would collapse them into spaces.
        select("br").forEach { it.replaceWith(TextNode("\n")) }
        return wholeText()
    }

    override fun getMangaUrl(manga: SManga): String {
        val url = captchaUrl?.also { captchaUrl = null }
            ?: "$baseUrl${manga.url}"

        return url
    }

    private fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListParse(response: Response): List<SChapter> =
        response.asJsoup().select("table.listing tr:gt(1)").map { element ->
            SChapter.create().apply {
                val urlElement = element.selectFirst("a")!!
                setUrlWithoutDomain(urlElement.attr("href"))
                name = urlElement.text()
                date_upload = dateFormat.tryParse(element.selectFirst("td:eq(1)")?.text())
            }
        }

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())

    override fun pageListRequest(chapter: SChapter): Request {
        val qualitySuffix = "&s=${serverPref()}&quality=${qualityPref()}&readType=1"
        return GET(baseUrl + chapter.url + qualitySuffix, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val useSecondServer = serverPref() == "s2"

        if (remoteConfigItem == null) {
            throw IOException("Failed to retrieve configuration")
        }

        // Combine all js scripts (so baeu() URL detection and link extraction see the same content)
        // baeu() is function call in the source code that sometimes contain a custom URL for the base URL for images
        // in that case we bypass choosing server1 or server2 base URLs and use the custom URL instead
        val combinedScripts = document.select("script").joinToString("\n") { it.data().trimIndent() }

        val encryptedLinks = decryptImageLinks(
            encryptedString = combinedScripts,
            useSecondServer = useSecondServer,
        )

        return encryptedLinks.mapIndexedNotNull { idx, url ->
            if (!remoteConfigItem!!.shouldVerifyLinks) {
                Page(idx, imageUrl = url)
            } else {
                val request = Request.Builder().url(url).head().build()

                client.newCall(request).execute().use {
                    if (it.isSuccessful) {
                        Page(idx, imageUrl = url)
                    } else {
                        null // Remove from list
                    }
                }
            }
        }
    }

    private fun decryptImageLinks(
        encryptedString: String,
        useSecondServer: Boolean,
    ): MutableList<String> {
        val pageLinks = mutableListOf<String>()
        val replaceMatch = replacePatternRegex.find(encryptedString)
        val obfuscationPattern = replaceMatch?.groupValues?.get(1)?.let { Regex(it) } ?: defaultObfuscationRegex
        val replacementChar = replaceMatch?.groupValues?.get(2) ?: "e"
        val detectedBaseUrl = baseUrlRegex.find(encryptedString)?.groupValues?.get(1)

        val arrayVars = arrayVarRegex.findAll(encryptedString)
            .map { it.groupValues[1] }
            .toList()

        for (arrayVar in arrayVars) {
            val callRegex = Regex(
                """\w+\s*\([^)]*\b${Regex.escape(arrayVar)}\b[^)]*,\s*["']([^"']{20,})["'][,\s]*\)""",
            )
            val matches = callRegex.findAll(encryptedString).toList()

            if (matches.isEmpty()) {
                continue
            }

            val rawLinks = matches.map { it.groupValues[1] }
            val prefixOffset = findPrefixOffset(rawLinks)

            for (match in matches) {
                val rawLink = match.groupValues[1]
                if (rawLink.isNotEmpty()) {
                    pageLinks += decryptLink(
                        rawLink = rawLink,
                        prefixOffset = prefixOffset,
                        obfuscationPattern = obfuscationPattern,
                        replacementChar = replacementChar,
                        detectedBaseUrl = detectedBaseUrl,
                        useSecondServer = useSecondServer,
                    )
                }
            }
        }

        return getCleanedLinks(pageLinks).toMutableList()
    }

    private fun findPrefixOffset(values: List<String>): Int {
        if (values.isEmpty()) {
            return 0
        }

        val first = values.first()
        var samePrefixCount = 0

        for (index in first.indices) {
            val char = first[index]
            if (values.all { it.length > index && it[index] == char }) {
                samePrefixCount++
                if (samePrefixCount >= 5 && first.substring(samePrefixCount - 5, samePrefixCount) == "https") {
                    return samePrefixCount - 5
                }
            } else {
                break
            }
        }

        return 0
    }

    private fun decryptLink(
        rawLink: String,
        prefixOffset: Int = 0,
        obfuscationPattern: Regex,
        replacementChar: String,
        detectedBaseUrl: String?,
        useSecondServer: Boolean,
    ): String {
        var link = rawLink
            .replace(obfuscationPattern, replacementChar)
            .replace("pw_.g28x", "b")
            .replace("d2pr.x_27", "h")

        if (prefixOffset != 0) {
            link = link.substring(prefixOffset)
        }

        if (link.endsWith("=s0") || link.endsWith("=s1600")) {
            link = link.replace("https://2.bp.blogspot.com/", "") + "?"
        }

        if (!link.startsWith("https")) {
            val queryIndex = link.indexOf("?")
            val query = link.substring(queryIndex)
            val isS0 = link.contains("=s0?")
            val sizeIndex = if (isS0) {
                link.indexOf("=s0?")
            } else {
                link.indexOf("=s1600?")
            }

            var encodedPath = link.substring(0, sizeIndex)
            encodedPath = encodedPath.substring(15, 33) + encodedPath.substring(50)

            val encodedPathLength = encodedPath.length
            encodedPath = encodedPath.substring(0, encodedPathLength - 11) +
                    encodedPath[encodedPathLength - 2] +
                    encodedPath[encodedPathLength - 1]

            val decodedBytes = Base64.decode(encodedPath, Base64.DEFAULT)
            var decodedPath = URLDecoder.decode(String(decodedBytes), "UTF-8")

            decodedPath = decodedPath.substring(0, 13) + decodedPath.substring(17)
            decodedPath = decodedPath.substring(0, decodedPath.length - 2) + if (isS0) "=s0" else "=s1600"

            val baseUrl = detectedBaseUrl ?: if (useSecondServer) {
                "https://ano1.rconet.biz/pic"
            } else {
                "https://2.bp.blogspot.com"
            }

            link = "$baseUrl/$decodedPath$query${if (useSecondServer) "&t=10" else ""}"
        }

        return link
    }

    private fun getCleanedLinks(pageLinks: List<String>): List<String> {
        val seen = mutableSetOf<String>()

        return pageLinks.filter { link ->
            if (link.isEmpty()) {
                return@filter false
            }

            val cleanLink = link.substringBefore("?").substringBefore("=")
            val isUnique = seen.add(cleanLink)
            val isNotBlocked = cleanLink !in blocklist
            val isValidUrl = urlRegex.matches(cleanLink)

            isUnique && isNotBlocked && isValidUrl
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    private class Status :
        SelectFilter(
            "Status",
            arrayOf(
                Pair("Any", ""),
                Pair("Completed", "Completed"),
                Pair("Ongoing", "Ongoing"),
            ),
        )

    private class Genre(name: String, val gid: String) : Filter.TriState(name)
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres) {
        val included: List<String>
            get() = state.filter { it.isIncluded() }.map { it.gid }

        val excluded: List<String>
            get() = state.filter { it.isExcluded() }.map { it.gid }
    }

    open class SelectFilter(displayName: String, private val options: Array<Pair<String, String>>) :
        Filter.Select<String>(
            displayName,
            options.map { it.first }.toTypedArray(),
        ) {
        open val selected get() = options[state].second.takeUnless { it.isEmpty() }
    }

    private class YearFilter :
        SelectFilter(
            "Publish Year",
            arrayOf(Pair("Any", "")) + (2026 downTo 1920).map { Pair(it.toString(), it.toString()) }.toTypedArray(),
        )

    private class PublisherFilter : Filter.Text("Publisher")
    private class WriterFilter : Filter.Text("Writer")
    private class ArtistFilter : Filter.Text("Artist")
    private class SortFilter :
        SelectFilter(
            "Sort By",
            arrayOf(
                Pair("Alphabet", ""),
                Pair("Popularity", "MostPopular"),
                Pair("Latest Update", "LatestUpdate"),
                Pair("New Comic", "Newest"),
            ),
        )

    override fun getFilterList() = FilterList(
        GenreList(getGenreList()),
        Status(),
        YearFilter(),
        Filter.Separator(),
        Filter.Header("Filters below are ignored when any of the above filters or the search is filled. (Although you can sort for a single genre)"),
        SortFilter(),
        PublisherFilter(),
        WriterFilter(),
        ArtistFilter(),
    )

    // $("select[name=\"genres\"]").map((i,el) => `Genre("${$(el).next().text().trim()}", ${i})`).get().join(',\n')
    // on https://readcomiconline.li/AdvanceSearch
    private fun getGenreList() = listOf(
        Genre("Action", "1"),
        Genre("Adventure", "2"),
        Genre("Anthology", "38"),
        Genre("Anthropomorphic", "46"),
        Genre("Biography", "41"),
        Genre("Children", "49"),
        Genre("Comedy", "3"),
        Genre("Crime", "17"),
        Genre("Drama", "19"),
        Genre("Family", "25"),
        Genre("Fantasy", "20"),
        Genre("Fighting", "31"),
        Genre("Graphic Novels", "5"),
        Genre("Historical", "28"),
        Genre("Horror", "15"),
        Genre("Leading Ladies", "35"),
        Genre("LGBTQ", "51"),
        Genre("Literature", "44"),
        Genre("Manga", "40"),
        Genre("Martial Arts", "4"),
        Genre("Mature", "8"),
        Genre("Military", "33"),
        Genre("Mini-Series", "56"),
        Genre("Movies & TV", "47"),
        Genre("Music", "55"),
        Genre("Mystery", "23"),
        Genre("Mythology", "21"),
        Genre("Personal", "48"),
        Genre("Political", "42"),
        Genre("Post-Apocalyptic", "43"),
        Genre("Psychological", "27"),
        Genre("Pulp", "39"),
        Genre("Religious", "53"),
        Genre("Robots", "9"),
        Genre("Romance", "32"),
        Genre("School Life", "52"),
        Genre("Sci-Fi", "16"),
        Genre("Slice of Life", "50"),
        Genre("Sport", "54"),
        Genre("Spy", "30"),
        Genre("Superhero", "22"),
        Genre("Supernatural", "24"),
        Genre("Suspense", "29"),
        Genre("Thriller", "18"),
        Genre("Vampires", "34"),
        Genre("Video Games", "37"),
        Genre("War", "26"),
        Genre("Western", "45"),
        Genre("Zombies", "36"),
    )
    // Preferences Code

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = MIRROR_PREF
            title = MIRROR_PREF_TITLE
            entries = MIRROR_NAMES
            entryValues = Array(MIRROR_URLS.size) { it.toString() }
            setDefaultValue("1")
            summary = "%s"
        }

        ListPreference(screen.context).apply {
            key = QUALITY_PREF
            title = QUALITY_PREF_TITLE
            entries = arrayOf("High Quality", "Low Quality")
            entryValues = arrayOf("hq", "lq")
            summary = "%s"
        }

        ListPreference(screen.context).apply {
            key = SERVER_PREF
            title = SERVER_PREF_TITLE
            entries = arrayOf("Server 1", "Server 2")
            entryValues = arrayOf("", "s2")
            summary = "%s"
        }
    }

    private fun qualityPref() = preferences.getString(QUALITY_PREF, "hq")

    private fun serverPref() = preferences.getString(SERVER_PREF, "")

    private var remoteConfigItem: RemoteConfigDTO? = null
        get() {
            if (field != null) {
                return field
            }

            val configLink = IMAGE_REMOTE_CONFIG_DEFAULT.addBustQuery()

            try {
                val configResponse = client.newCall(GET(configLink)).execute()

                field = configResponse.parseAs<RemoteConfigDTO>()
                configResponse.close()
                return field
            } catch (_: IOException) {
                return null
            }
        }

    private fun String.addBustQuery(): String = "$this?bust=${Calendar.getInstance().time.time}"

    @Serializable
    private class RemoteConfigDTO(
        val imageDecryptEval: String,
        val postDecryptEval: String?,
        val shouldVerifyLinks: Boolean,
    )

    companion object {
        private const val QUALITY_PREF_TITLE = "Image Quality Selector"
        private const val QUALITY_PREF = "qualitypref"
        private const val SERVER_PREF_TITLE = "Server Preference"
        private const val SERVER_PREF = "serverpref"
        private const val MIRROR_PREF_TITLE = "Mirror Preference"
        private const val MIRROR_PREF = "mirrorpref"
        private val MIRROR_NAMES = arrayOf("readcomiconline.li", "rcostation.xyz")
        private val MIRROR_URLS = arrayOf("https://readcomiconline.li", "https://rcostation.xyz")
        private const val IMAGE_REMOTE_CONFIG_DEFAULT =
            "https://raw.githubusercontent.com/keiyoushi/extensions-source/refs/heads/main/src/en/readcomiconline/config.json"

        private val urlRegex = Regex(
            """^https?://(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)+\b(?:[/a-z0-9\-._~:?#@!$&'()*+,;=%]*)$""",
            RegexOption.IGNORE_CASE,
        )
        private val replacePatternRegex = Regex("""\.replace\(\s*/(\w+__\w+_)/g\s*,\s*['"](\w)['"]\s*\)""")
        private val defaultObfuscationRegex = Regex("""\w{2}__\w{6}_""")
        private val arrayVarRegex = Regex("""var\s+(\w+)\s*=\s*new\s+Array\(\)\s*;""")
        private val baseUrlRegex = Regex("""baeu\(\w+,\s*["'](https?://[^"']+)["']\)""")

        private val blocklist = setOf(
            "https://2.bp.blogspot.com/pw/AP1GczP6zCVVfdmN6OoVnm7CLvEfmHMUawyEwJWouX9C6SHwsiuYfLkUr9FsM6Zo34qNzPKeQeahBx9ckBZJQckiJmX1UwKD7uh900yz5rKyG4zT2rfIrqFviEJIev1Pg_pGRuSG57rIH6BDwGCTmiE4MjA",
            "https://2.bp.blogspot.com/pw/AP1GczP48thKMga7cud0tjtHtYqsvZzhYY0HyAxVzM3O1D6tkLbi0fT9NDZFFFH69hNnoGsnqJSEIh4mmpEoU1BJSfNXIz1f5aLXl41RM9os7ePn7ipbrYbIuqiQxAV0hhJZrNLl7FmauwLQ01paCrP6KAE",
            "https://2.bp.blogspot.com/pw/AP1GczNXprTMfAP2AHFFWvCbKq6qReXrqSohz87KeBjV0nh6XoLsE1NpzL7Rp9llxoY208IPARiIDON_TO6dZB0ZMNeB8J7xzUzbS9h6To7aGpOZshFofw-wFQ0KJ3y3wolSwzLrduZZ_0w8_6gGuTEB-98",
            "https://2.bp.blogspot.com/pw/AP1GczMVY_zWeag2n981CRX7jaZ73Sr0NtidtJhnvJ3-Rmh2fIo-PoQRI0ZksQEbpTjDHgBeNYbQ2hQodsY-Dv0FXUhiU_mus5z5L5lMVAH82kXYqOd2IEw",
            "https://2.bp.blogspot.com/pw/AP1GczOKY-6EDGVvlQGB2wj0xxB5JgcyiujFJC3CHgwqBOLIidwmoP6DLiMpX__Fw6MMPvLezN6soeV0A8pKSHUrC4rxZyO5vov40g1g4ipZdkFlzUouAFA",
            "https://2.bp.blogspot.com/pw/AP1GczO8AETT3k19nhJwxHm0sHCSy0tXyhSOYxnq3EUrmlvgY5yPqDaxcd1XZ7reQKH-lKgpGK4o3sW_9Yu6feqii79riXN3Ghi8Xs1S5Z4wi-aeHrq5PzOX",
        )
    }
}