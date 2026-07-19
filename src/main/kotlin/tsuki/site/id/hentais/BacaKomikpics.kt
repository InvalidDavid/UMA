package tsuki.site.id.hentais

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.*

import okhttp3.Headers
import org.json.JSONArray
import org.jsoup.nodes.Document
import java.util.EnumSet

@MangaSourceParser("BACAKOMIKPICS", "BacaKomik.pics", "id", ContentType.HENTAI)
internal class BacaKomikpics(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.BACAKOMIKPICS, 24) {   // increased page size slightly for performance

    override val configKeyDomain = ConfigKey.Domain("bacakomik.pics")

    override val userAgentKey = ConfigKey.UserAgent("Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = false, // weird handling of website
        isMultipleTagsSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = when (order) {
            SortOrder.POPULARITY -> "https://$domain/komik-populer/"
            SortOrder.UPDATED -> {
                if (page == 0) "https://$domain/?s=&post_type=komik_series"
                else "https://$domain/page/${page + 1}/?s&post_type=komik_series"
            }
            else -> "https://$domain/komik-populer/"
        }
        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("a.card").mapNotNull { card ->
            val href = card.attrAsAbsoluteUrl("href")
            val title = card.selectFirst(".card-title")?.text()?.trim() ?: return@mapNotNull null
            val cover = card.selectFirst("img")?.attr("src")?.nullIfEmpty()
            val type = card.classNames().find { it.startsWith("card-") }?.removePrefix("card-")?.uppercase()
            val status = card.selectFirst(".status-label")?.text()?.trim()
            Manga(
                id = generateUid(href),
                url = href.toRelativeUrl(domain),
                publicUrl = href,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = cover ?: "",
                tags = setOfNotNull(type?.let { MangaTag(it, it, source) }),
                state = when (status) {
                    "Ongoing" -> MangaState.ONGOING
                    "Completed" -> MangaState.FINISHED
                    "Hiatus" -> MangaState.PAUSED
                    else -> null
                },
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val title = doc.selectFirst(".post-title h1")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst(".sinopsis")?.text()?.trim()
            ?: doc.selectFirst(".entry-content")?.text()?.trim() ?: ""

        val cover = doc.selectFirst(".series-thumb img")?.attr("src")
            ?: doc.selectFirst("img.wp-post-image")?.attr("src") ?: manga.coverUrl

        val tags = doc.select("a.genre-link").mapNotNull { a ->
            val genre = a.text().trim()
            val key = a.attr("href").substringAfter("/genre/").substringBefore("/")
            if (genre.isNotEmpty() && key.isNotEmpty()) MangaTag(genre, key, source) else null
        }.toSet()

        val statusText = doc.select(".sinopsis strong:contains(Status)").first()?.parent()?.text()
            ?.substringAfter("Status :")?.trim()
        val state = when {
            statusText != null && "ongoing" in statusText.lowercase() -> MangaState.ONGOING
            statusText != null && "completed" in statusText.lowercase() -> MangaState.FINISHED
            statusText != null && "hiatus" in statusText.lowercase() -> MangaState.PAUSED
            else -> null
        }

        val chapters = extractChapters(doc)

        return manga.copy(
            title = title,
            description = description,
            coverUrl = cover,
            tags = tags,
            state = state,
            authors = emptySet(),
            chapters = chapters,
        )
    }

    private fun extractChapters(doc: Document): List<MangaChapter> {
        val script = doc.select("script").find { it.data().contains("chapterData") }
            ?: return emptyList()
        val data = script.data()

        val prefixIndex = data.indexOf("chapterData")
        if (prefixIndex == -1) return emptyList()

        val windowStart = (prefixIndex - 500).coerceAtLeast(0)
        val windowEnd = (prefixIndex + 50_000).coerceAtMost(data.length)
        val window = data.substring(windowStart, windowEnd)

        val arrayRegex = Regex("""chapterData\s*=\s*(\[[^\]]*\])\s*;""", RegexOption.DOT_MATCHES_ALL)
        val match = arrayRegex.find(window) ?: return emptyList()
        val jsonStr = match.groupValues[1].replace("\\/", "/")

        return try {
            val jsonArray = JSONArray(jsonStr)
            (0 until jsonArray.length()).mapNotNull { i ->
                val obj = jsonArray.optJSONObject(i) ?: return@mapNotNull null
                val chTitle = obj.optString("title", "")
                val chUrl = obj.optString("url", "")
                val number = Regex("""CHAPTER\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(chTitle)?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1f)
                MangaChapter(
                    id = generateUid(chUrl),
                    title = chTitle,
                    number = number,
                    volume = 0,
                    url = chUrl.toRelativeUrl(domain),
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select(".viewer-komik img, .img-wrapper img").mapNotNull { img ->
            var src = img.attr("src")
            if (src.isBlank()) src = img.attr("data-src")
            if (src.isBlank()) return@mapNotNull null
            MangaPage(
                id = generateUid(src),
                url = src,
                preview = null,
                source = source,
            )
        }
    }

    private fun String.nullIfEmpty() = ifBlank { null }
}