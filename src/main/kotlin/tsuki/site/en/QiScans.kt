package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.EZManhwaParser

import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterOptions
import tsuki.model.MangaParserSource
import tsuki.model.MangaState
import tsuki.model.MangaTag
import tsuki.model.SortOrder

import tsuki.util.parseJson
import tsuki.util.json.mapJSON

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.EnumSet

@MangaSourceParser("QISCANS", "QiScans", "en")
internal class QiScans(context: MangaLoaderContext) :
    EZManhwaParser(context, MangaParserSource.QISCANS, "qimanga.com", "https://api.qimanga.com/api/v1") {

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override fun getRequestHeaders(): Headers {
        return super.getRequestHeaders().newBuilder()
            .set("Origin", "https://$domain")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Site", "same-site")
            .build()
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val base = super.getFilterOptions()
        return base.copy(
            availableTags = GENRES
                .filter { it.first.isNotEmpty() }
                .map { (key, name) -> MangaTag(key = key, title = name, source = source) }
                .toSet()
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val endpoint = if (filter.query.isNullOrBlank()) "$apiUrl/series" else "$apiUrl/series/search"
        val url = endpoint.toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", pageSize.toString())

            if (filter.query.isNullOrBlank()) {
                val sort = when (order) {
                    SortOrder.UPDATED, SortOrder.UPDATED_ASC -> "latest"
                    SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "popular"
                    SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "newest"
                    SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "alphabetical"
                    else -> "latest"
                }
                addQueryParameter("sort", sort)

                filter.states.firstOrNull()?.let {
                    addQueryParameter("status", when (it) {
                        MangaState.ONGOING -> "ONGOING"
                        MangaState.FINISHED -> "COMPLETED"
                        MangaState.PAUSED -> "HIATUS"
                        MangaState.ABANDONED -> "DROPPED"
                        else -> ""
                    })
                }
                filter.types.firstOrNull()?.let {
                    addQueryParameter("type", when (it) {
                        ContentType.MANGA -> "MANGA"
                        ContentType.MANHWA -> "MANHWA"
                        ContentType.MANHUA -> "MANHUA"
                        else -> ""
                    })
                }
                filter.tags.firstOrNull()?.key?.let { genreSlug ->
                    if (genreSlug.isNotBlank()) {
                        addQueryParameter("genre", genreSlug)
                    }
                }
            } else {
                addQueryParameter("q", filter.query)
            }
        }.build()

        val json = webClient.httpGet(url).parseJson()
        return parseSeriesList(json)
    }

    private fun parseSeriesList(json: org.json.JSONObject): List<Manga> {
        val data = json.getJSONArray("data")
        return data.mapJSON { obj ->
            obj.toManga(domain, source, isNsfwSource)
        }
    }

    companion object {
        private val GENRES = listOf(
            "" to "All",
            "acting" to "Acting",
            "action" to "Action",
            "action-582" to "Action (Alt)",
            "adventure" to "Adventure",
            "adventure-589" to "Adventure (Alt)",
            "apocalypce" to "Apocalypse",
            "comedy" to "Comedy",
            "cooking" to "Cooking",
            "crazy-mc" to "Crazy MC",
            "cultivation" to "Cultivation",
            "drama" to "Drama",
            "ecchi" to "Ecchi",
            "fantasy" to "Fantasy",
            "fantasy-747" to "Fantasy (Alt)",
            "fight" to "Fight",
            "gender-bender" to "Gender Bender",
            "harem" to "Harem",
            "hidden" to "Hidden",
            "historical" to "Historical",
            "horror" to "Horror",
            "josei" to "Josei",
            "live" to "Live",
            "magic" to "Magic",
            "manhua" to "Manhua",
            "martial-arts" to "Martial Arts",
            "mature" to "Mature",
            "mecha" to "Mecha",
            "medieval-area" to "Medieval Area",
            "munchkin" to "Munchkin",
            "murim" to "Murim",
            "mystery" to "Mystery",
            "myth" to "Myth",
            "politics" to "Politics",
            "psychological" to "Psychological",
            "reincarnation" to "Reincarnation",
            "revenge" to "Revenge",
            "romance" to "Romance",
            "school-life" to "School Life",
            "sci-fi" to "Sci-Fi",
            "seinen" to "Seinen",
            "shounen" to "Shounen",
            "slice-of-life" to "Slice of Life",
            "sports" to "Sports",
            "supernatural" to "Supernatural",
            "superpower" to "Superpower",
            "system" to "System",
            "taming" to "Taming",
            "tower" to "Tower",
            "tragedy" to "Tragedy",
            "urban" to "Urban",
            "vampiers" to "Vampires",
            "virtual-reality" to "Virtual Reality",
            "wuxia" to "Wuxia",
        )
    }
}
