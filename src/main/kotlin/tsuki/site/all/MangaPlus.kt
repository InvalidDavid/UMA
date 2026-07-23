package tsuki.site.all

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.SinglePageMangaParser
import tsuki.model.*
import tsuki.util.*
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.IOException
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.Semaphore

// created very small protobuf reader via Okio
@Throws(IOException::class)
private fun Buffer.readProtobufVarint(): Long {
    var result = 0L
    var shift = 0
    while (true) {
        val b = readByte().toInt() and 0xFF
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) break
        shift += 7
        if (shift > 63) throw IOException("Malformed varint")
    }
    return result
}

@Throws(IOException::class)
private fun Buffer.readProtobufString(): String = readByteArray(readProtobufVarint()).toString(Charsets.UTF_8)

@Throws(IOException::class)
private fun Buffer.readProtobufBytes(): ByteArray = readByteArray(readProtobufVarint())

data class MPResponse(val success: SuccessResult?, val error: String?)

data class SuccessResult(
    val titleRankingView: TitleRankingView?,
    val webHomeView: WebHomeView?,
    val allTitlesView: AllTitlesView?,
    val titleDetailView: TitleDetailView?,
    val mangaViewer: MangaViewer?
)

data class TitleRankingView(val rankedTitles: List<RankedTitle>)
data class RankedTitle(val titles: List<Title>)
data class WebHomeView(val groups: List<UpdatedTitleGroup>)
data class UpdatedTitleGroup(val titles: List<UpdatedTitle>)
data class UpdatedTitle(val title: Title?)   // can be null

data class AllTitlesView(val allTitlesGroup: List<AllTitlesGroup>)
data class AllTitlesGroup(val titles: List<Title>)

data class Title(
    val titleId: Int,
    val name: String,
    val author: String?,
    val portraitImageUrl: String,
    val language: Int
)

data class TitleDetailView(
    val title: Title,
    val overview: String,
    val viewingPeriodDescription: String,
    val nonAppearanceInfo: String,
    val chapterListGroup: List<ChapterListGroup>
)

data class ChapterListGroup(
    val firstChapterList: List<Chapter>,
    val lastChapterList: List<Chapter>
)

data class Chapter(
    val chapterId: Int,
    val name: String,
    val subTitle: String?,
    val startTimeStamp: Int
)

data class MangaViewer(
    val pages: List<MangaPlusPage>,
    val titleId: Int?,
    val viewToken: String?
)

data class MangaPlusPage(val mangaPage: MPImagePage?)
data class MPImagePage(val imageUrl: String, val encryptionKey: String?)

// field numbers
private const val FIELD_SUCCESS = 1
private const val FIELD_ERROR   = 2

// SuccessResult sub‑fields
private const val FIELD_TITLE_DETAIL_VIEW = 8
private const val FIELD_MANGA_VIEWER      = 10
private const val FIELD_ALL_TITLES_VIEW   = 25
private const val FIELD_TITLE_RANKING_VIEW= 37
private const val FIELD_WEB_HOME_VIEW     = 38

// TitleDetailView fields
private const val FIELD_TITLE        = 1
private const val FIELD_OVERVIEW     = 3
private const val FIELD_VIEWING_PERIOD = 7
private const val FIELD_NON_APPEARANCE = 8
private const val FIELD_CHAPTER_LIST_GROUP = 28

// Title fields
private const val FIELD_TITLE_ID    = 1
private const val FIELD_TITLE_NAME  = 2
private const val FIELD_AUTHOR      = 3
private const val FIELD_PORTRAIT_IMAGE = 4
private const val FIELD_LANGUAGE     = 7

// ChapterListGroup fields
private const val FIELD_FIRST_CHAPTER_LIST = 2
private const val FIELD_LAST_CHAPTER_LIST  = 4

// Chapter fields
private const val FIELD_CHAPTER_ID      = 2
private const val FIELD_CHAPTER_NAME    = 3
private const val FIELD_SUBTITLE        = 4
private const val FIELD_START_TIMESTAMP = 6

// MangaViewer fields
private const val FIELD_PAGES     = 1
private const val FIELD_TITLE_ID_MV = 9
private const val FIELD_VIEW_TOKEN = 19

// MangaPlusPage fields
private const val FIELD_MANGA_PAGE = 1

// MangaPage fields
private const val FIELD_IMAGE_URL       = 1
private const val FIELD_ENCRYPTION_KEY  = 5

// TitleRankingView fields
private const val FIELD_RANKED_TITLES = 3
// RankedTitle fields
private const val FIELD_RANKED_TITLE_TITLES = 2

// WebHomeView fields
private const val FIELD_GROUPS = 2
// UpdatedTitleGroup fields
private const val FIELD_GROUP_TITLES = 2
// UpdatedTitle fields
private const val FIELD_LATEST_CHAPTER = 3
// LatestChapter fields
private const val FIELD_LATEST_CHAPTER_TITLE = 1

// AllTitlesView fields
private const val FIELD_ALL_TITLES_GROUP = 1
// AllTitlesGroup fields
private const val FIELD_GROUP_TITLES_ALL = 2


private const val one = "low"
private const val two = "high"
private const val three = "super_high"

@Throws(IOException::class)
private fun Buffer.parseMPResponse(): MPResponse {
    var success: SuccessResult? = null
    var error: String? = null
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fieldNumber = (tag shr 3).toInt()
        val wireType = (tag and 0x07).toInt()
        when (fieldNumber) {
            FIELD_SUCCESS -> {
                if (wireType == 2) {
                    val bytes = readProtobufBytes()
                    success = Buffer().apply { write(bytes) }.parseSuccessResult()
                } else skipField(wireType)
            }
            FIELD_ERROR -> {
                if (wireType == 2) {
                    val bytes = readProtobufBytes()
                    // parse simple error message (first string body)
                    val buf = Buffer().apply { write(bytes) }
                    while (!buf.exhausted()) {
                        val t = buf.readProtobufVarint()
                        val fn = (t shr 3).toInt()
                        val wt = (t and 0x07).toInt()
                        if (fn == 2 && wt == 2) {
                            val nested = buf.readProtobufBytes()
                            val nestedBuf = Buffer().apply { write(nested) }
                            // Try to extract body field
                            while (!nestedBuf.exhausted()) {
                                val nt = nestedBuf.readProtobufVarint()
                                val nfn = (nt shr 3).toInt()
                                val nwt = (nt and 0x07).toInt()
                                if (nfn == 2 && nwt == 2) {
                                    error = nestedBuf.readProtobufString()
                                    break
                                } else nestedBuf.skipField(nwt)
                            }
                        } else buf.skipField(wt)
                    }
                } else skipField(wireType)
            }
            else -> skipField(wireType)
        }
    }
    return MPResponse(success, error)
}

@Throws(IOException::class)
private fun Buffer.skipField(wireType: Int) {
    when (wireType) {
        0 -> readProtobufVarint()
        1 -> skip(8)
        2 -> skip(readProtobufVarint())
        5 -> skip(4)
        else -> throw IOException("Unknown wire type $wireType")
    }
}

@Throws(IOException::class)
private fun Buffer.parseSuccessResult(): SuccessResult {
    var titleDetailView: TitleDetailView? = null
    var mangaViewer: MangaViewer? = null
    var allTitlesView: AllTitlesView? = null
    var titleRankingView: TitleRankingView? = null
    var webHomeView: WebHomeView? = null
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fieldNumber = (tag shr 3).toInt()
        val wireType = (tag and 0x07).toInt()
        when (fieldNumber) {
            FIELD_TITLE_DETAIL_VIEW -> {
                if (wireType == 2) {
                    val bytes = readProtobufBytes()
                    titleDetailView = Buffer().apply { write(bytes) }.parseTitleDetailView()
                } else skipField(wireType)
            }
            FIELD_MANGA_VIEWER -> {
                if (wireType == 2) {
                    val bytes = readProtobufBytes()
                    mangaViewer = Buffer().apply { write(bytes) }.parseMangaViewer()
                } else skipField(wireType)
            }
            FIELD_ALL_TITLES_VIEW -> {
                if (wireType == 2) {
                    val bytes = readProtobufBytes()
                    allTitlesView = Buffer().apply { write(bytes) }.parseAllTitlesView()
                } else skipField(wireType)
            }
            FIELD_TITLE_RANKING_VIEW -> {
                if (wireType == 2) {
                    val bytes = readProtobufBytes()
                    titleRankingView = Buffer().apply { write(bytes) }.parseTitleRankingView()
                } else skipField(wireType)
            }
            FIELD_WEB_HOME_VIEW -> {
                if (wireType == 2) {
                    val bytes = readProtobufBytes()
                    webHomeView = Buffer().apply { write(bytes) }.parseWebHomeView()
                } else skipField(wireType)
            }
            else -> skipField(wireType)
        }
    }
    return SuccessResult(titleRankingView, webHomeView, allTitlesView, titleDetailView, mangaViewer)
}

@Throws(IOException::class)
private fun Buffer.parseTitle(): Title {
    var titleId = 0
    var name = ""
    var author: String? = null
    var portraitImageUrl = ""
    var language = 0
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        when (fn) {
            FIELD_TITLE_ID -> if (wt == 0) titleId = readProtobufVarint().toInt()
            FIELD_TITLE_NAME -> if (wt == 2) name = readProtobufString()
            FIELD_AUTHOR -> if (wt == 2) author = readProtobufString()
            FIELD_PORTRAIT_IMAGE -> if (wt == 2) portraitImageUrl = readProtobufString()
            FIELD_LANGUAGE -> if (wt == 0) language = readProtobufVarint().toInt()
            else -> skipField(wt)
        }
    }
    return Title(titleId, name, author, portraitImageUrl, language)
}

@Throws(IOException::class)
private fun Buffer.parseTitleDetailView(): TitleDetailView {
    var title: Title? = null
    var overview = ""
    var viewingPeriodDescription = ""
    var nonAppearanceInfo = ""
    val chapterListGroup = mutableListOf<ChapterListGroup>()
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        when (fn) {
            FIELD_TITLE -> if (wt == 2) {
                val bytes = readProtobufBytes()
                title = Buffer().apply { write(bytes) }.parseTitle()
            }
            FIELD_OVERVIEW -> if (wt == 2) overview = readProtobufString()
            FIELD_VIEWING_PERIOD -> if (wt == 2) viewingPeriodDescription = readProtobufString()
            FIELD_NON_APPEARANCE -> if (wt == 2) nonAppearanceInfo = readProtobufString()
            FIELD_CHAPTER_LIST_GROUP -> if (wt == 2) {
                val bytes = readProtobufBytes()
                chapterListGroup += Buffer().apply { write(bytes) }.parseChapterListGroup()
            }
            else -> skipField(wt)
        }
    }
    return TitleDetailView(title!!, overview, viewingPeriodDescription, nonAppearanceInfo, chapterListGroup)
}

@Throws(IOException::class)
private fun Buffer.parseChapterListGroup(): ChapterListGroup {
    val first = mutableListOf<Chapter>()
    val last = mutableListOf<Chapter>()
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        when (fn) {
            FIELD_FIRST_CHAPTER_LIST -> if (wt == 2) {
                val bytes = readProtobufBytes()
                first += Buffer().apply { write(bytes) }.parseChapter()
            }
            FIELD_LAST_CHAPTER_LIST -> if (wt == 2) {
                val bytes = readProtobufBytes()
                last += Buffer().apply { write(bytes) }.parseChapter()
            }
            else -> skipField(wt)
        }
    }
    return ChapterListGroup(first, last)
}

@Throws(IOException::class)
private fun Buffer.parseChapter(): Chapter {
    var chapterId = 0
    var name = ""
    var subTitle: String? = null
    var startTimeStamp = 0
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        when (fn) {
            FIELD_CHAPTER_ID -> if (wt == 0) chapterId = readProtobufVarint().toInt()
            FIELD_CHAPTER_NAME -> if (wt == 2) name = readProtobufString()
            FIELD_SUBTITLE -> if (wt == 2) subTitle = readProtobufString()
            FIELD_START_TIMESTAMP -> if (wt == 0) startTimeStamp = readProtobufVarint().toInt()
            else -> skipField(wt)
        }
    }
    return Chapter(chapterId, name, subTitle, startTimeStamp)
}

@Throws(IOException::class)
private fun Buffer.parseMangaViewer(): MangaViewer {
    val pages = mutableListOf<MangaPlusPage>()
    var titleId: Int? = null
    var viewToken: String? = null
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        when (fn) {
            FIELD_PAGES -> if (wt == 2) {
                val bytes = readProtobufBytes()
                pages += Buffer().apply { write(bytes) }.parseMangaPlusPage()
            }
            FIELD_TITLE_ID_MV -> if (wt == 0) titleId = readProtobufVarint().toInt()
            FIELD_VIEW_TOKEN -> if (wt == 2) viewToken = readProtobufString()
            else -> skipField(wt)
        }
    }
    return MangaViewer(pages, titleId, viewToken)
}

@Throws(IOException::class)
private fun Buffer.parseMangaPlusPage(): MangaPlusPage {
    var mangaPage: MPImagePage? = null
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        if (fn == FIELD_MANGA_PAGE && wt == 2) {
            val bytes = readProtobufBytes()
            mangaPage = Buffer().apply { write(bytes) }.parseMPImagePage()
        } else skipField(wt)
    }
    return MangaPlusPage(mangaPage)
}

@Throws(IOException::class)
private fun Buffer.parseMPImagePage(): MPImagePage {
    var imageUrl = ""
    var encryptionKey: String? = null
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        when (fn) {
            FIELD_IMAGE_URL -> if (wt == 2) imageUrl = readProtobufString()
            FIELD_ENCRYPTION_KEY -> if (wt == 2) encryptionKey = readProtobufString()
            else -> skipField(wt)
        }
    }
    return MPImagePage(imageUrl, encryptionKey)
}

@Throws(IOException::class)
private fun Buffer.parseTitleRankingView(): TitleRankingView {
    val ranked = mutableListOf<RankedTitle>()
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        if (fn == FIELD_RANKED_TITLES && wt == 2) {
            val bytes = readProtobufBytes()
            ranked += Buffer().apply { write(bytes) }.parseRankedTitle()
        } else skipField(wt)
    }
    return TitleRankingView(ranked)
}

@Throws(IOException::class)
private fun Buffer.parseRankedTitle(): RankedTitle {
    val titles = mutableListOf<Title>()
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        if (fn == FIELD_RANKED_TITLE_TITLES && wt == 2) {
            val bytes = readProtobufBytes()
            titles += Buffer().apply { write(bytes) }.parseTitle()
        } else skipField(wt)
    }
    return RankedTitle(titles)
}

@Throws(IOException::class)
private fun Buffer.parseWebHomeView(): WebHomeView {
    val groups = mutableListOf<UpdatedTitleGroup>()
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        if (fn == FIELD_GROUPS && wt == 2) {
            val bytes = readProtobufBytes()
            groups += Buffer().apply { write(bytes) }.parseUpdatedTitleGroup()
        } else skipField(wt)
    }
    return WebHomeView(groups)
}

@Throws(IOException::class)
private fun Buffer.parseUpdatedTitleGroup(): UpdatedTitleGroup {
    val titles = mutableListOf<UpdatedTitle>()
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        if (fn == FIELD_GROUP_TITLES && wt == 2) {
            val bytes = readProtobufBytes()
            titles += Buffer().apply { write(bytes) }.parseUpdatedTitle()
        } else skipField(wt)
    }
    return UpdatedTitleGroup(titles)
}

@Throws(IOException::class)
private fun Buffer.parseUpdatedTitle(): UpdatedTitle {
    var title: Title? = null
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        if (fn == FIELD_LATEST_CHAPTER && wt == 2) {
            val bytes = readProtobufBytes()
            val nested = Buffer().apply { write(bytes) }
            while (!nested.exhausted()) {
                val ntag = nested.readProtobufVarint()
                val nfn = (ntag shr 3).toInt()
                val nwt = (ntag and 0x07).toInt()
                if (nfn == FIELD_LATEST_CHAPTER_TITLE && nwt == 2) {
                    val titleBytes = nested.readProtobufBytes()
                    title = Buffer().apply { write(titleBytes) }.parseTitle()
                } else nested.skipField(nwt)
            }
        } else skipField(wt)
    }
    return UpdatedTitle(title)
}

@Throws(IOException::class)
private fun Buffer.parseAllTitlesView(): AllTitlesView {
    val groups = mutableListOf<AllTitlesGroup>()
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        if (fn == FIELD_ALL_TITLES_GROUP && wt == 2) {
            val bytes = readProtobufBytes()
            groups += Buffer().apply { write(bytes) }.parseAllTitlesGroup()
        } else skipField(wt)
    }
    return AllTitlesView(groups)
}

@Throws(IOException::class)
private fun Buffer.parseAllTitlesGroup(): AllTitlesGroup {
    val titles = mutableListOf<Title>()
    while (!exhausted()) {
        val tag = readProtobufVarint()
        val fn = (tag shr 3).toInt()
        val wt = (tag and 0x07).toInt()
        if (fn == FIELD_GROUP_TITLES_ALL && wt == 2) {
            val bytes = readProtobufBytes()
            titles += Buffer().apply { write(bytes) }.parseTitle()
        } else skipField(wt)
    }
    return AllTitlesGroup(titles)
}

internal abstract class MangaPlusParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    private val sourceLang: String,
    private val internalLangName: String,
    private val internalLangCode: Int
) : SinglePageMangaParser(context, source), Interceptor {

    override val configKeyDomain = ConfigKey.Domain("mangaplus.shueisha.co.jp")

    private val apiUrl = "https://jumpg-webapi.tokyo-cdn.com/api"
    private val session = UUID.randomUUID().toString()
    private val imageSemaphore = Semaphore(1)
    private val detailsCacheLock = Any()

    private val detailsCache = object : LinkedHashMap<String, Manga>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Manga>?): Boolean = size > 10
    }

    private val preferredServerKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            one to "Low quality",
            two to "High quality",
            three to "Super high quality",
        ),
        defaultValue = two,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(preferredServerKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.ALPHABETICAL
    )

    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)
    override suspend fun getFilterOptions() = MangaListFilterOptions()

    private suspend fun apiCall(url: String): MPResponse {
        val fullUrl = "$apiUrl$url".toHttpUrl().newBuilder().build()
        val headers = Headers.headersOf("SESSION-TOKEN", session)
        val response = webClient.httpGet(fullUrl, headers)
        val body = response.body
        return Buffer().apply { write(body.bytes()) }.parseMPResponse()
    }

    override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query
        if (!query.isNullOrEmpty()) {
            val resp = apiCall("/title_list/allV2")
            val titles = resp.success?.allTitlesView?.allTitlesGroup?.flatMap { it.titles } ?: emptyList()
            return titles.filterByLang().filter {
                it.name.contains(query, true) || it.author?.contains(query, true) ?: false
            }.map { it.toManga() }
        }

        return when (order) {
            SortOrder.POPULARITY -> {
                val resp = apiCall("/title_list/rankingV2?lang=$internalLangName&type=hottest&clang=$internalLangName")
                resp.success?.titleRankingView?.rankedTitles?.flatMap { it.titles }
                    .orEmpty().filterByLang().map { it.toManga() }
            }
            SortOrder.UPDATED -> {
                val resp = apiCall("/web/web_homeV4?lang=$internalLangName&clang=$internalLangName")
                resp.success?.webHomeView?.groups?.flatMap { it.titles }?.mapNotNull { it.title }
                    .orEmpty().filterByLang().map { it.toManga() }
            }
            SortOrder.ALPHABETICAL -> {
                val resp = apiCall("/title_list/allV2")
                resp.success?.allTitlesView?.allTitlesGroup?.flatMap { it.titles }
                    .orEmpty().filterByLang().sortedBy { it.name.lowercase() }.map { it.toManga() }
            }
            else -> emptyList()
        }
    }

    private fun Title.toManga() = Manga(
        id = generateUid(titleId.toString()),
        url = titleId.toString(),
        publicUrl = "/titles/$titleId".toAbsoluteUrl(domain),
        title = name,
        coverUrl = portraitImageUrl,
        altTitles = emptySet(),
        authors = author?.split("/")?.joinToString(", ") { it.trim() }?.let { setOf(it) } ?: emptySet(),
        contentRating = null,
        rating = RATING_UNKNOWN,
        state = null,
        source = source,
        tags = emptySet(),
    )

    private fun List<Title>.filterByLang() = filter { it.titleId != 0 && it.language == internalLangCode }.distinctBy { it.titleId }

    override suspend fun getDetails(manga: Manga): Manga {
        synchronized(detailsCacheLock) {
            detailsCache[manga.url]?.let { return it }
        }

        val resp = apiCall("/title_detailV3?title_id=${manga.url}&clang=$internalLangName")
        val detail = resp.success?.titleDetailView ?: throw IOException("No detail")
        val completed = detail.nonAppearanceInfo.contains(Regex("completado|complete|completo", RegexOption.IGNORE_CASE))
        val hiatus = detail.nonAppearanceInfo.contains(Regex("on a hiatus", RegexOption.IGNORE_CASE))
        val result = manga.copy(
            title = detail.title.name,
            coverUrl = detail.title.portraitImageUrl,
            authors = detail.title.author?.split("/")?.joinToString(", ") { it.trim() }?.let { setOf(it) } ?: emptySet(),
            description = listOfNotNull(detail.overview.takeIf { it.isNotEmpty() }, detail.viewingPeriodDescription.takeIf { it.isNotEmpty() }).joinToString("\n\n"),
            state = when {
                completed -> MangaState.FINISHED
                hiatus -> MangaState.PAUSED
                else -> MangaState.ONGOING
            },
            chapters = detail.chapterListGroup
                .flatMap { it.firstChapterList + it.lastChapterList }
                .filter { it.subTitle != null }
                .map { chapter ->
                    MangaChapter(
                        id = generateUid(chapter.chapterId.toString()),
                        url = chapter.chapterId.toString(),
                        title = chapter.subTitle ?: chapter.name,
                        number = chapter.name.substringAfter("#").toFloatOrNull() ?: -1f,
                        volume = 0,
                        uploadDate = chapter.startTimeStamp * 1000L,
                        branch = when (sourceLang) {
                            "PORTUGUESE_BR" -> "Portuguese (Brazil)"
                            else -> sourceLang.lowercase().toTitleCase()
                        },
                        scanlator = null,
                        source = source,
                    )
                }
        )

        synchronized(detailsCacheLock) {
            detailsCache[manga.url] = result
        }
        return result
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val quality = config[preferredServerKey]
        val resp = apiCall("/manga_viewer_v3?chapter_id=${chapter.url}&split=yes&img_quality=$quality&clang=$internalLangName")
        val viewer = resp.success?.mangaViewer ?: return emptyList()
        val viewToken = viewer.viewToken ?: ""
        return viewer.pages.mapNotNull { it.mangaPage }.map { mpImage ->
            val baseUrl = mpImage.imageUrl
            val encryptionKey = mpImage.encryptionKey
            val separator = if (baseUrl.contains("?")) "&" else "?"
            val fullUrl = buildString {
                append(baseUrl)
                append(separator).append("vtoken=").append(viewToken)
                if (encryptionKey != null) append("#").append(encryptionKey)
            }

            MangaPage(
                id = generateUid(fullUrl),
                url = fullUrl,
                preview = null,
                source = source,
            )
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val host = url.host

        return if (host.startsWith("jumpg-assets")) {
            imageSemaphore.acquire()
            try {
                processImageRequest(chain, request, url)
            } finally {
                imageSemaphore.release()
            }
        } else {
            processImageRequest(chain, request, url)
        }
    }

    private fun processImageRequest(chain: Interceptor.Chain, request: okhttp3.Request, url: okhttp3.HttpUrl): Response {
        val encryptionKey = url.fragment
        val vtoken = url.queryParameter("vtoken")

        val newUrl = url.newBuilder()
            .removeAllQueryParameters("vtoken")
            .build()

        val newRequest = request.newBuilder()
            .url(newUrl)
            .apply {
                if (vtoken != null) {
                    header("Plus-Vw-Token", vtoken)
                }
            }
            .build()

        val response = chain.proceed(newRequest)

        if (encryptionKey.isNullOrEmpty()) return response

        val keyStream = encryptionKey.chunked(2).map { it.toInt(16) }
        val contentType = response.header("Content-Type") ?: "image/jpeg"
        val bytes = response.body.bytes()
        val decrypted = bytes.mapIndexed { i, byte ->
            (byte.toInt() xor keyStream[i % keyStream.size]).toByte()
        }.toByteArray()

        return response.newBuilder()
            .body(decrypted.toResponseBody(contentType.toMediaTypeOrNull()))
            .build()
    }

    @MangaSourceParser("MANGAPLUSPARSER_EN", "MANGA Plus (English)", "en")
    class English(context: MangaLoaderContext) : MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_EN, "ENGLISH", "eng", 0)

    @MangaSourceParser("MANGAPLUSPARSER_ES", "MANGA Plus (Spanish)", "es")
    class Spanish(context: MangaLoaderContext) : MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_ES, "SPANISH", "esp", 1)

    @MangaSourceParser("MANGAPLUSPARSER_FR", "MANGA Plus (French)", "fr")
    class French(context: MangaLoaderContext) : MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_FR, "FRENCH", "fra", 2)

    @MangaSourceParser("MANGAPLUSPARSER_ID", "MANGA Plus (Indonesian)", "id")
    class Indonesian(context: MangaLoaderContext) : MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_ID, "INDONESIAN", "ind", 3)

    @MangaSourceParser("MANGAPLUSPARSER_PTBR", "MANGA Plus Portuguese (Brazil)", "pt")
    class Portuguese(context: MangaLoaderContext) : MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_PTBR, "PORTUGUESE_BR", "ptb", 4)

    @MangaSourceParser("MANGAPLUSPARSER_RU", "MANGA Plus (Russian)", "ru")
    class Russian(context: MangaLoaderContext) : MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_RU, "RUSSIAN", "rus", 5)

    @MangaSourceParser("MANGAPLUSPARSER_TH", "MANGA Plus (Thai)", "th")
    class Thai(context: MangaLoaderContext) : MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_TH, "THAI", "tha", 6)

    @MangaSourceParser("MANGAPLUSPARSER_VI", "MANGA Plus (Vietnamese)", "vi")
    class Vietnamese(context: MangaLoaderContext) : MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_VI, "VIETNAMESE", "vie", 9)

    @MangaSourceParser("MANGAPLUSPARSER_DE", "MANGA Plus (German)", "de")
    class German(context: MangaLoaderContext) : MangaPlusParser(context, MangaParserSource.MANGAPLUSPARSER_DE, "GERMAN", "deu", 7)
}
