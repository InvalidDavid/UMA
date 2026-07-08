package org.koitharu.kotatsu.parsers.site.kotatsu.all.hentais

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

private const val ONE_IMG_SERVER = "1"
private const val TWO_IMG_SERVER = "2"
private const val THREE_IMG_SERVER = "3"
private const val FOUR_IMG_SERVER = "4"

@MangaSourceParser("NHENTAI", "NHentai.net", type = ContentType.HENTAI)
internal class NHentaiParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.NHENTAI, 25),
    MangaParserAuthProvider {

    override val configKeyDomain = ConfigKey.Domain("nhentai.net")

    private var imageServer: String = ""
    private var thumbServer: String = ""

    private var displayFullTitle: Boolean = true

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
    )

    private val preferredServerKey = ConfigKey.PreferredImageServer(
        presetValues = mapOf(
            ONE_IMG_SERVER to "First server",
            TWO_IMG_SERVER to "Second server",
            THREE_IMG_SERVER to "Third server",
            FOUR_IMG_SERVER to "Fourth server",
        ),
        defaultValue = ONE_IMG_SERVER,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(preferredServerKey)
    }

    private val selectedServer: String
        get() = config[preferredServerKey].toString()

    companion object {
        val LANG_MAP = mapOf(
            "en" to "english",
            "ja" to "japanese",
            "zh" to "chinese",
        )
    }


    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableLocales = setOf(
            Locale.ENGLISH,
            Locale.JAPANESE,
            Locale.CHINESE,
        ),
    )


    private suspend fun ensureNhConfig() {
        if (imageServer.isBlank() || thumbServer.isBlank()) {
            try {
                val json = webClient.httpGet("https://$domain/api/v2/config").parseJson()
                val imgServers = json.getJSONArray("image_servers")
                    .let { (0 until it.length()).map { i -> it.getString(i) } }
                val thbServers = json.getJSONArray("thumb_servers")
                    .let { (0 until it.length()).map { i -> it.getString(i) } }
                imageServer = imgServers[selectedServer.toInt() - 1]
                thumbServer = thbServers[selectedServer.toInt() - 1]
            } catch (_: Exception) {
                imageServer = "https://i$selectedServer.nhentai.net"
                thumbServer = "https://t$selectedServer.nhentai.net"
            }
        }
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        ensureNhConfig()

        val langQuery = filter.locale?.let { loc ->
            val langKey = loc.language.lowercase()
            LANG_MAP[langKey]?.let { "language:$it" }
        }

        // Direct ID search
        val directId = extractGalleryId(filter.query)
        if (directId != null) {
            return try {
                listOf(fetchMangaById(directId))
            } catch (_: Exception) {
                emptyList()
            }
        }

        val isLatest = order == SortOrder.UPDATED && filter.query.isNullOrBlank() && langQuery == null && filter.tags.isEmpty()

        val url = buildString {
            append("https://$domain/api/v2/")
            if (isLatest) {
                append("galleries?page=$page")
            } else {
                val queryParts = mutableListOf<String>()
                if (!filter.query.isNullOrBlank()) queryParts.add(filter.query!!)
                if (langQuery != null) queryParts.add(langQuery)

                val finalQuery = queryParts.joinToString(" ").ifBlank { "\"\"" }
                val sort = when (order) {
                    SortOrder.UPDATED -> "date"
                    SortOrder.POPULARITY -> "popular"
                    else -> "date"
                }
                append("search?query=${finalQuery.urlEncoded()}&sort=$sort&page=$page")
            }
        }

        val json = webClient.httpGet(url).parseJson()
        val items = json.getJSONArray("result")
        return (0 until items.length()).map { i -> items.getJSONObject(i).toManga() }
    }

    private fun extractGalleryId(query: String?): Int? {
        if (query.isNullOrBlank()) return null
        val trimmed = query.trim()
        return when {
            trimmed.startsWith("id:") -> trimmed.removePrefix("id:").toIntOrNull()
            trimmed.all(Char::isDigit) -> trimmed.toIntOrNull()
            else -> null
        }
    }

    private suspend fun fetchMangaById(id: Int): Manga {
        ensureNhConfig()
        val json = webClient.httpGet("https://$domain/api/v2/galleries/$id").parseJson()
        return json.toMangaDetails()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        ensureNhConfig()
        val id = manga.url.removeSurrounding("/g/", "/").toInt()
        val json = webClient.httpGet("https://$domain/api/v2/galleries/$id").parseJson()
        return json.toMangaDetails().copy(
            coverUrl = manga.coverUrl?.ifBlank {
                "${thumbServer}/${json.getJSONObject("cover").optString("path")}"
            },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        ensureNhConfig()
        val id = chapter.url.removeSurrounding("/g/", "/").toInt()
        val json = webClient.httpGet("https://$domain/api/v2/galleries/$id").parseJson()
        val pagesArray = json.getJSONArray("pages")
        return (0 until pagesArray.length()).map { i ->
            val pageObj = pagesArray.getJSONObject(i)
            val path = pageObj.getString("path")
            MangaPage(
                id = generateUid(path),
                url = "$imageServer/$path",
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url
    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    private fun JSONObject.toManga(): Manga {
        val id = getInt("id")
        val titleObj = optJSONObject("title")
        val rawTitle = titleObj?.optString("english")
            ?: titleObj?.optString("pretty")
            ?: optString("english_title")
            ?: optString("japanese_title")
            ?: "Gallery $id"

        val title = if (displayFullTitle) rawTitle else rawTitle.shortenTitle()
        val thumbPath = optJSONObject("thumbnail")?.optString("path")
            ?: optString("thumbnail").takeIf { !it.isNullOrBlank() }
            ?: "galleries/$id/thumb.webp"

        return Manga(
            id = generateUid("/g/$id/"),
            title = title,
            altTitles = emptySet(),
            url = "/g/$id/",
            publicUrl = "https://$domain/g/$id/",
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = "$thumbServer/$thumbPath",
            tags = extractTags(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    private fun JSONObject.toMangaDetails(): Manga {
        val id = getInt("id")
        val titleObj = optJSONObject("title")
        val rawTitle = titleObj?.optString("english")
            ?: titleObj?.optString("japanese")
            ?: titleObj?.optString("pretty")
            ?: "Gallery $id"
        val title = if (displayFullTitle) rawTitle else rawTitle.shortenTitle()

        val thumbPath = getJSONObject("thumbnail").getString("path")
        val coverPath = getJSONObject("cover").optString("path", thumbPath)
        val numPages = optInt("num_pages")
        val uploadDate = optLong("upload_date") * 1000

        val tags = extractTags()
        val artists = getTagsOfType("artist")
        val groups = getTagsOfType("group")
        val author = artists.joinToString(", ").ifBlank { groups.joinToString(", ") }

        val description = buildString {
            append("Pages: $numPages\n")
        }

        return Manga(
            id = generateUid("/g/$id/"),
            title = title,
            altTitles = listOfNotNull(titleObj?.optString("japanese")).toSet(),
            url = "/g/$id/",
            publicUrl = "https://$domain/g/$id/",
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.ADULT,
            coverUrl = "$thumbServer/$thumbPath",
            largeCoverUrl = "$imageServer/$coverPath",
            tags = tags,
            state = null,
            authors = setOfNotNull(author.ifBlank { null }),
            description = description,
            chapters = listOf(
                MangaChapter(
                    id = generateUid("/g/$id/"),
                    title = title,
                    number = 1f,
                    volume = 0,
                    url = "/g/$id/",
                    scanlator = groups.joinToString(", ").ifBlank { null },
                    uploadDate = uploadDate,
                    branch = null,
                    source = source,
                )
            ),
            source = source,
        )
    }

    private fun JSONObject.extractTags(): Set<MangaTag> {
        val tagsArray = optJSONArray("tags") ?: return emptySet()
        return (0 until tagsArray.length()).map { i ->
            val tag = tagsArray.getJSONObject(i)
            val name = tag.getString("name")
            MangaTag(
                title = name.replace("-", " ").replaceFirstChar { it.uppercase() },
                key = "${tag.getString("type")}:$name",
                source = source,
            )
        }.toSet()
    }

    private fun JSONObject.getTagsOfType(type: String): List<String> {
        val tagsArray = optJSONArray("tags") ?: return emptyList()
        return (0 until tagsArray.length()).mapNotNull { i ->
            val tag = tagsArray.getJSONObject(i)
            if (tag.getString("type") == type) tag.getString("name") else null
        }
    }

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = this.replace(shortenTitleRegex, "").trim()

    override val authUrl: String get() = "https://$domain"
    override suspend fun isAuthorized(): Boolean = true
    override suspend fun getUsername(): String = ""
}
