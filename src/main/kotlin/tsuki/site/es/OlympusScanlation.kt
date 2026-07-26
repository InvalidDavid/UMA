package tsuki.site.es

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser

import tsuki.model.*
import tsuki.util.*

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tsuki.network.OkHttpWebClient
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@MangaSourceParser("OLYMPUSSCANLATION", "Olympus Scanlation", "es")
internal class OlympusScanlation(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.OLYMPUSSCANLATION, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("olympusxyz.com")
    private val baseUrl = "https://$domain"
    private val apiBaseUrl = "https://panel.olympusxyz.com"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val webClient by lazy {
        OkHttpWebClient(context.httpClient.newBuilder().build(), source)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .set("Referer", "$baseUrl/")
        .set("Accept", "application/json")
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    @Volatile
    private var seriesList: List<MangaDto> = emptyList()
    @Volatile
    private var lastFetchTime: Long = 0L
    private val seriesMutex = Mutex()
    private val slugMap = ConcurrentHashMap<String, String>()

    private suspend fun fetchSeriesListIfNeeded() {
        val now = System.currentTimeMillis()
        if (seriesList.isNotEmpty() && (now - lastFetchTime) < TimeUnit.HOURS.toMillis(1)) return
        seriesMutex.withLock {
            if (seriesList.isNotEmpty() && (now - lastFetchTime) < TimeUnit.HOURS.toMillis(1)) return
            val response = webClient.httpGet("$baseUrl/api/series/list", getRequestHeaders())
            val json = response.parseJson()
            val dataArray = json.optJSONArray("data") ?: return
            val comics = mutableListOf<MangaDto>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.optJSONObject(i) ?: continue
                if (obj.optString("type") != "comic") continue
                val id = obj.getInt("id")
                val name = obj.optString("name", "")
                val slug = obj.optString("slug", "")
                val cover = obj.optString("cover", "")
                val summary = obj.optString("summary", "")
                val statusId = obj.optJSONObject("status")?.optInt("id") ?: -1
                val genres = mutableListOf<String>()
                obj.optJSONArray("genres")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        arr.optJSONObject(j)?.optString("name", "")?.takeIf { it.isNotEmpty() }?.let(genres::add)
                    }
                }
                comics.add(MangaDto(id, name, slug, cover, summary, statusId, genres))
                slugMap[id.toString()] = slug
            }
            seriesList = comics
            lastFetchTime = now
        }
    }

    private data class MangaDto(
        val id: Int, val name: String, val slug: String, val cover: String,
        val summary: String, val statusId: Int, val genres: List<String>
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,
        SortOrder.UPDATED,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(
            MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        fetchSeriesListIfNeeded()

        val query = filter.query?.trim().orEmpty()

        if (query.isNotEmpty()) {
            val filtered = seriesList.filter { it.name.contains(query, ignoreCase = true) }
            val start = (page - 1) * pageSize
            if (start >= filtered.size) return emptyList()
            val end = (start + pageSize).coerceAtMost(filtered.size)
            return filtered.subList(start, end).map { it.toManga() }
        }

        val apiUrl = when (order) {
            SortOrder.POPULARITY -> "$baseUrl/api/rankings?page=$page&period=total_ranking"
            SortOrder.UPDATED    -> "$baseUrl/api/new-chapters?page=$page"
            else                 -> "$baseUrl/api/rankings?page=$page&period=total_ranking"
        }

        val json = webClient.httpGet(apiUrl, getRequestHeaders()).parseJson()
        val dataArray = json.optJSONArray("data") ?: return emptyList()
        val mangas = mutableListOf<Manga>()
        for (i in 0 until dataArray.length()) {
            val obj = dataArray.optJSONObject(i) ?: continue
            if (obj.optString("type") != "comic") continue
            val id = obj.getInt("id")
            val name = obj.optString("name", "")
            val slug = obj.optString("slug", "")
            val cover = obj.optString("cover", "")
            slugMap[id.toString()] = slug
            mangas.add(MangaDto(id, name, slug, cover, "", -1, emptyList()).toManga())
        }
        return mangas
    }

    private fun MangaDto.toManga() = Manga(
        id = generateUid(id.toString()),
        url = id.toString(),
        publicUrl = "$baseUrl/series/comic-$slug",
        title = name,
        coverUrl = cover.takeIf { it.isNotEmpty() }?.let {
            if (it.startsWith("http")) it else "$baseUrl/$it"
        } ?: "",
        altTitles = emptySet(),
        rating = RATING_UNKNOWN,
        contentRating = null,
        tags = emptySet(),
        state = null,
        authors = emptySet(),
        source = source,
    )

    override suspend fun getDetails(manga: Manga): Manga {
        fetchSeriesListIfNeeded()
        val mangaId = manga.url
        val slug = slugMap[mangaId] ?: return manga

        val url = "$baseUrl/api/series/$slug?type=comic"
        val json = webClient.httpGet(url, getRequestHeaders()).parseJson()
        val data = json.optJSONObject("data") ?: return manga

        val title = data.optString("name", manga.title)
        val cover = data.optString("cover", manga.coverUrl)
        val summary = data.optString("summary", "")
        val statusId = data.optJSONObject("status")?.optInt("id") ?: -1
        val state = when (statusId) {
            1 -> MangaState.ONGOING
            3 -> MangaState.PAUSED
            4 -> MangaState.FINISHED
            5 -> MangaState.ABANDONED
            else -> null
        }
        val genres = mutableSetOf<MangaTag>()
        data.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name", "")?.takeIf { it.isNotEmpty() }?.let {
                    genres.add(MangaTag(it.lowercase(), it, source))
                }
            }
        }
        val chapters = fetchChapters(mangaId, slug)

        return manga.copy(
            title = title,
            coverUrl = cover?.takeIf { it.isNotEmpty() }?.let {
                if (it.startsWith("http")) it else "$baseUrl/$it"
            } ?: manga.coverUrl,
            description = summary,
            tags = genres,
            state = state,
            chapters = chapters,
        )
    }

    private suspend fun fetchChapters(mangaId: String, slug: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        var total = Int.MAX_VALUE
        while (chapters.size < total) {
            val url = "$apiBaseUrl/api/series/$slug/chapters?page=$page&direction=desc&type=comic"
            val json = webClient.httpGet(url, getRequestHeaders()).parseJson()
            val dataArray = json.optJSONArray("data") ?: break
            total = json.optJSONObject("meta")?.optInt("total", dataArray.length()) ?: dataArray.length()
            for (i in 0 until dataArray.length()) {
                val ch = dataArray.optJSONObject(i) ?: continue
                val id = ch.getInt("id")
                val name = ch.optString("name", "")
                val publishedAt = ch.optString("published_at", "")
                val uploadDate = try { dateFormat.parse(publishedAt)?.time ?: 0L } catch (_: Exception) { 0L }
                chapters.add(
                    MangaChapter(
                        id = generateUid("$mangaId/$id"),
                        title = "Capitulo $name",
                        number = name.toFloatOrNull() ?: 0f,
                        volume = 0,
                        url = "$mangaId/$id",
                        uploadDate = uploadDate,
                        scanlator = null,
                        branch = null,
                        source = source,
                    )
                )
            }
            page++
        }
        return chapters.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("/")
        if (parts.size != 2) return emptyList()
        val mangaId = parts[0]
        val chapterId = parts[1]
        val slug = slugMap[mangaId] ?: return emptyList()

        val url = "$baseUrl/api/capitulo/comic-$slug/$chapterId"
        val json = webClient.httpGet(url, getRequestHeaders()).parseJson()
        val pagesArray = json.optJSONObject("chapter")?.optJSONArray("pages") ?: return emptyList()
        return (0 until pagesArray.length()).map { i ->
            val imgUrl = pagesArray.getString(i)
            MangaPage(id = generateUid(imgUrl), url = imgUrl, preview = null, source = source)
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()
}
