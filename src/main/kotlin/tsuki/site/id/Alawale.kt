package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentRating
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.RATING_UNKNOWN
import tsuki.model.SortOrder

import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.parseJson
import tsuki.util.parseRaw
import tsuki.util.urlEncoded

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.EnumSet

@MangaSourceParser("ALAWALE", "Alawale", "id")
internal class Alawale(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ALAWALE, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("alawale.net")
    private val baseUrl = "https://$domain"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val doc = webClient.httpGet("$baseUrl/daftar-komik").parseHtml()

        val genreTags = doc.select("select[aria-label=Genre] option")
            .mapNotNull { opt ->
                val value = opt.`val`().trim()
                if (value.isEmpty()) null
                else MangaTag(key = "genre:$value", title = opt.text().trim(), source = source)
            }.toSet()

        val typeTags = doc.select("select[aria-label=Tipe] option")
            .mapNotNull { opt ->
                val value = opt.`val`().trim()
                if (value.isEmpty()) null
                else MangaTag(key = "type:$value", title = opt.text().trim(), source = source)
            }.toSet()

        return MangaListFilterOptions(
            availableTags = genreTags + typeTags,
            availableContentTypes = EnumSet.of(
                ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.COMICS
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sort = when (order) {
            SortOrder.UPDATED -> "update"
            SortOrder.POPULARITY -> "chapter"
            SortOrder.ALPHABETICAL -> "title"
            else -> "update"
        }

        val url = "$baseUrl/api/komik".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())

        if (filter.query != null) {
            url.addQueryParameter("q", filter.query.urlEncoded())
        }

        filter.tags.forEach { tag ->
            when {
                tag.key.startsWith("genre:") -> url.addQueryParameter("genre", tag.key.removePrefix("genre:"))
                tag.key.startsWith("type:")  -> url.addQueryParameter("type", tag.key.removePrefix("type:"))
            }
        }
        filter.types.forEach { ct ->
            val typeParam = when (ct) {
                ContentType.MANGA   -> "Manga"
                ContentType.MANHWA  -> "Manhwa"
                ContentType.MANHUA  -> "Manhua"
                ContentType.COMICS  -> "Comic"
                else -> null
            }
            if (typeParam != null) url.addQueryParameter("type", typeParam)
        }

        val json = webClient.httpGet(url.build()).parseJson()
        val items = json.optJSONArray("items") ?: return emptyList()

        return (0 until items.length()).map { i ->
            val obj = items.getJSONObject(i)
            val slug = obj.getString("slug")
            val title = obj.getString("title")
            val type = obj.optString("type", "")
            val coverPath = obj.optString("cover_remote", "")
            val coverUrl = if (coverPath.startsWith("/")) "$baseUrl$coverPath" else coverPath

            Manga(
                id = generateUid("/$slug"),
                url = "/$slug",
                publicUrl = "$baseUrl/$slug",
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = coverUrl.ifBlank { null },
                tags = setOfNotNull(type.takeIf { it.isNotBlank() }?.let { MangaTag(it, it, source) }),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removePrefix("/")
        val doc = webClient.httpGet("$baseUrl/$slug").parseHtml()

        val apiJson = webClient.httpGet("$baseUrl/api/komik?slugs=$slug").parseJson()
        val apiItem = apiJson?.optJSONArray("items")?.optJSONObject(0)
        val title = apiItem?.optString("title") ?: manga.title
        val coverPath = apiItem?.optString("cover_remote") ?: ""
        val coverUrl = if (coverPath.startsWith("/")) "$baseUrl$coverPath" else coverPath

        val bookScript = doc.selectFirst("script[type=\"application/ld+json\"]:containsData(Book)")
        var description: String? = null
        var author: String? = null
        val genres = mutableListOf<String>()

        if (bookScript != null) {
            val json = JSONObject(bookScript.data())
            description = json.optString("description", null)
            author = json.optJSONObject("author")?.optString("name")
            val genreArray = json.optJSONArray("genre")
            if (genreArray != null) {
                for (i in 0 until genreArray.length()) {
                    genres.add(genreArray.getString(i))
                }
            }
        }

        if (author.isNullOrBlank()) {
            author = doc.select(".detail-info .kv span:contains(Author) b")?.text()?.trim()
        }

        val synopsisBody = doc.selectFirst(".syn-body")?.text()?.trim()
        if (!synopsisBody.isNullOrBlank() && description.isNullOrBlank()) {
            description = synopsisBody
        }

        if (genres.isEmpty()) {
            genres.addAll(doc.select(".detail-info .genres a.chip").map { it.text() })
        }

        val tagSet = genres.map { MangaTag(it, it, source) }.toMutableSet()
        val type = apiItem?.optString("type", "")
        if (!type.isNullOrBlank()) {
            tagSet += MangaTag(type, type, source)
        }

        val chapters = parseChapters(doc, slug)

        return manga.copy(
            title = title,
            coverUrl = coverUrl.ifBlank { manga.coverUrl },
            description = description,
            authors = setOfNotNull(author),
            tags = tagSet,
            chapters = chapters,
            contentRating = ContentRating.SAFE,
        )
    }

    private fun parseChapters(doc: Document, slug: String): List<MangaChapter> {
        val chapterLinks = doc.select("div.chap-list a.chap-item")
            .distinctBy { it.attr("abs:href") }

        return chapterLinks.map { link ->
            val href = link.attr("href").removePrefix("/")
            val chapterSlug = href.substringAfter("$slug/ch/")
            val name = link.selectFirst("span")?.text()?.trim() ?: chapterSlug
            val number = parseChapterNumber(chapterSlug)

            MangaChapter(
                id = generateUid("/$href"),
                url = "/$href",
                title = name,
                number = number,
                volume = 0,
                uploadDate = 0L,
                source = source,
                scanlator = null,
                branch = null,
            )
        }.sortedBy { it.number }
    }

    private fun parseChapterNumber(chapterSlug: String): Float {
        val clean = chapterSlug.substringBefore("-bahasa-indonesia")
        val parts = clean.split("-")
        return if (parts.size == 2) {
            val main = parts[0].toFloatOrNull() ?: 0f
            val sub = parts[1].toFloatOrNull() ?: 0f
            main + sub / 10f
        } else {
            clean.toFloatOrNull() ?: 0f
        }
    }

    // RSC payloard
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = "$baseUrl/${chapter.url.removePrefix("/")}"
        val rawHtml = webClient.httpGet(fullUrl).parseRaw()
        val unescapedHtml = rawHtml.replace("\\/", "/")

        val imageRegex = Regex("""https://bmcdn\.my\.id/[^\s"\\]+\.jpg""")
        val allUrls = imageRegex.findAll(unescapedHtml)
            .map { it.value }
            .distinct()
            .toList()

        if (allUrls.isNotEmpty()) {
            val pages = allUrls.mapNotNull { url ->
                val filename = url.substringAfterLast("/")
                val dashIndex = filename.indexOf('-')
                if (dashIndex < 0) return@mapNotNull null
                val pageNumStr = filename.substring(0, dashIndex)
                val pageNum = pageNumStr.toIntOrNull() ?: return@mapNotNull null
                pageNum to url
            }.sortedBy { it.first }
                .map { (_, url) ->
                    MangaPage(
                        id = generateUid(url),
                        url = url,
                        preview = null,
                        source = source,
                    )
                }
            if (pages.isNotEmpty()) return pages
        }

        val doc = Jsoup.parse(unescapedHtml)
        val pageDivs = doc.select("div.reader-page")
        if (pageDivs.isEmpty()) return emptyList()

        val totalPages = pageDivs.size
        val firstImageUrl = doc.head()
            .select("link[rel=preload][as=image]")
            .map { it.attr("abs:href") }
            .firstOrNull { it.isNotBlank() }
            ?: return emptyList()

        val lastSegment = firstImageUrl.substringAfterLast("/")
        val dashIndex = lastSegment.indexOf('-')
        if (dashIndex < 0) return emptyList()

        val hashPart = lastSegment.substring(dashIndex + 1)
        val basePath = firstImageUrl.substringBeforeLast("/") + "/"

        return (0 until totalPages).map { index ->
            val url = "${basePath}${index}-${hashPart}"
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val slug = seed.url.removePrefix("/")
        val doc = webClient.httpGet("$baseUrl/$slug").parseHtml()
        return doc.select("section.related .card").mapNotNull { card ->
            val link = card.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href").removePrefix("/")
            val title = link.selectFirst(".t")?.text() ?: return@mapNotNull null
            val coverImg = link.selectFirst("img")
            val coverUrl = coverImg?.attr("abs:src")
            Manga(
                id = generateUid("/$href"),
                url = "/$href",
                publicUrl = "$baseUrl/$href",
                title = title,
                coverUrl = coverUrl,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }
}
