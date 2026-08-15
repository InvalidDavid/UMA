package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.ContentType
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

import tsuki.util.generateUid
import tsuki.util.toAbsoluteUrl
import tsuki.util.attrAsRelativeUrl
import tsuki.util.parseHtml
import tsuki.util.extractChapterNumber

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("LUMOSKOMIK", "LumosKomik", "id")
internal class LumosKomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.LUMOSKOMIK, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("03.lumosgg.com")

    private val baseUrl = "https://$domain"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = false,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = fetchGenres()
        return MangaListFilterOptions(
            availableTags = genres,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
        )
    }

    private suspend fun fetchGenres(): Set<MangaTag> {
        val doc = webClient.httpGet("$baseUrl/browse").parseHtml()
        return doc.select("label[data-bf-genre-name] input.bf-genre-cb[value]")
            .mapNotNull { input ->
                val value = input.attr("value").trim()
                val name = input.parent()?.selectFirst("span.truncate")?.text()?.trim()
                if (value.isNotEmpty() && !name.isNullOrEmpty()) {
                    MangaTag(name, value, source)
                } else null
            }
            .toSet()
    }

    /**
     * When scraping they have a hidden honeypot in the listing crazy
     */
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sort = when (order) {
            SortOrder.UPDATED -> "latest"
            SortOrder.POPULARITY -> "popular"
            SortOrder.RATING -> "rating"
            SortOrder.ALPHABETICAL -> "az"
            else -> "latest"
        }

        val url = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
            if (!filter.query.isNullOrBlank()) addQueryParameter("q", filter.query)
            if (sort.isNotEmpty()) addQueryParameter("sort", sort)
            if (filter.states.isNotEmpty()) addQueryParameter("status", mapState(filter.states.first()))
            if (filter.types.isNotEmpty()) addQueryParameter("type", mapType(filter.types.first()))
            if (filter.tags.isNotEmpty()) addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
            addQueryParameter("page", page.toString())
        }.build()

        val doc = webClient.httpGet(url).parseHtml()
        val list = parseMangaList(doc)
        return list.filterNot { it.url.contains("honeypot") }
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("a[href^=\"/comic/\"]:has(img:not([src*=\"flagcdn\"]))")
            .mapNotNull { element ->
                val img = element.selectFirst("img") ?: return@mapNotNull null
                val title = img.attr("alt").takeIf { it.isNotBlank() } ?: element.text().trim()
                if (title.isBlank()) return@mapNotNull null

                val href = element.attrAsRelativeUrl("href")
                Manga(
                    id = generateUid(href),
                    title = title,
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    coverUrl = img.attr("abs:src"),
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            }
            .distinctBy { it.url }
    }

    private fun mapState(state: MangaState?): String = when (state) {
        MangaState.ONGOING -> "ongoing"
        MangaState.FINISHED -> "completed"
        MangaState.PAUSED -> "hiatus"
        else -> ""
    }

    private fun mapType(type: ContentType?): String = when (type) {
        ContentType.MANGA -> "manga"
        ContentType.MANHWA -> "manhwa"
        ContentType.MANHUA -> "manhua"
        else -> ""
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
        val cover = doc.selectFirst("img[src*=\"/cover\"]")?.attr("abs:src")
            ?: doc.selectFirst("aside img")?.attr("abs:src")
            ?: manga.coverUrl
        val description = run {
            val encoded = doc.selectFirst("div[data-sr]")?.attr("data-sr")?.takeIf { it.isNotBlank() }
            val decoded = encoded?.let { decodeBase64(it) }?.trim()?.takeIf { it.isNotBlank() }
            decoded ?: doc.selectFirst("p.text-sm")?.text()?.trim()?.takeIf { it.isNotBlank() }
        }

        val genres = doc.select("a[href*=\"genre=\"]")
            .asSequence()
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toSet()
            .map { name ->
                MangaTag(
                    title = name,
                    key = name.lowercase(Locale.ROOT).replace(" ", "-"),
                    source = source,
                )
            }
            .toSet()

        val statusText = doc.selectFirst("main")?.text() ?: doc.text()
        val state = when {
            statusText.contains("completed", ignoreCase = true) ||
                    statusText.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
            statusText.contains("ongoing", ignoreCase = true) ||
                    statusText.contains("berjalan", ignoreCase = true) -> MangaState.ONGOING
            statusText.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
            else -> null
        }

        val chapters = parseChapterList(doc)

        return manga.copy(
            title = title,
            coverUrl = cover,
            description = description,
            tags = genres,
            state = state,
            chapters = chapters,
        )
    }

    private fun parseChapterList(doc: Document): List<MangaChapter> {
        return doc.select("a[href*=\"/read/\"][data-chapter]").mapNotNull { element ->
            val href = element.attrAsRelativeUrl("href")
            val name = element.selectFirst("span.text-sm, span[class*=\"font-semibold\"]")?.text()?.trim()
                ?: element.text().trim()
            val number = element.attr("data-chapter").trim().toFloatOrNull()
                ?: name.extractChapterNumber()
            val dateStr = element.selectFirst("span.tabular-nums, span[class*=\"tabular-nums\"]")?.text()?.trim()
            MangaChapter(
                id = generateUid(href),
                title = name,
                number = number,
                volume = 0,
                url = href,
                uploadDate = parseRelativeDate(dateStr),
                source = source,
                scanlator = null,
                branch = null,
            )
        }.reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("img[src*=\"file/comic\"], img[src*=\"imgsvr\"]")
            .mapNotNull { img ->
                val src = img.attr("abs:src")
                if (src.isBlank() || src.contains("/api/image/p/")) {
                    null
                } else {
                    MangaPage(
                        id = generateUid(src),
                        url = src,
                        preview = null,
                        source = source,
                    )
                }
            }
    }

    private fun decodeBase64(encoded: String): String? = try {
        val padded = encoded.trim().let { s ->
            val rem = s.length % 4
            if (rem != 0) s + "=".repeat(4 - rem) else s
        }
        String(Base64.getDecoder().decode(padded), Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun parseRelativeDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val trimmed = dateStr.lowercase(Locale.ROOT).trim()
        if (trimmed == "baru saja") return System.currentTimeMillis()

        val number = Regex("\\d+").find(trimmed)?.value?.toLongOrNull() ?: return 0L
        val nowInstant = Instant.now()
        val nowZoned = ZonedDateTime.now(ZoneId.systemDefault())

        return when {
            "detik" in trimmed -> nowInstant.minus(number, ChronoUnit.SECONDS).toEpochMilli()
            "menit" in trimmed -> nowInstant.minus(number, ChronoUnit.MINUTES).toEpochMilli()
            "jam" in trimmed -> nowInstant.minus(number, ChronoUnit.HOURS).toEpochMilli()
            "hari" in trimmed -> nowInstant.minus(number, ChronoUnit.DAYS).toEpochMilli()
            "minggu" in trimmed -> nowInstant.minus(number * 7, ChronoUnit.DAYS).toEpochMilli()
            "bulan" in trimmed -> nowZoned.minusMonths(number).toInstant().toEpochMilli()
            "tahun" in trimmed -> nowZoned.minusYears(number).toInstant().toEpochMilli()
            else -> 0L
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
