package org.koitharu.kotatsu.parsers.site.kotatsu.en

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("ATSUMARU", "Atsumaru", "en")
internal class Atsumaru(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ATSUMARU, pageSize = 40) {

    override val configKeyDomain = ConfigKey.Domain("atsu.moe")
    private val baseUrl = "https://atsu.moe"

    private val apiHeaders: Headers by lazy {
        Headers.Builder()
            .add("Accept", "*/*")
            .add("Referer", baseUrl)
            .add("Content-Type", "application/json")
            .build()
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY,    // trending (views)
        SortOrder.UPDATED,       // recently added (dateAdded)
        SortOrder.ADDED,         // recently new added mangas
        SortOrder.RELEVANCE      // chapters count
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
    )

    private val allGenres = listOf(
        Genre("Action", "39"), Genre("Adult", "46"), Genre("Adventure", "37"),
        Genre("Boys Love", "180"), Genre("Comedy", "6"), Genre("Drama", "31"),
        Genre("Fantasy", "36"), Genre("Girls Love", "4"), Genre("Hentai", "10"),
        Genre("Historical", "45"), Genre("Horror", "44"), Genre("Martial Arts", "29"),
        Genre("Mystery", "32"), Genre("Psychological", "18"), Genre("Romance", "9"),
        Genre("Sci-Fi", "1"), Genre("Slice of Life", "7"), Genre("Smut", "41"),
        Genre("Supernatural", "22"), Genre("Thriller", "19"), Genre("Tragedy", "5")
    )

    private val allTags = listOf(
        Tag("Blackmail", "285"), Tag("Cooking", "669"), Tag("Crimes", "288"),
        Tag("Crossdressing", "167"), Tag("Murder", "250"), Tag("Prostitution", "366"),
        Tag("Swordplay", "337"), Tag("Working", "248"),
        Tag("Josei", "43"), Tag("Seinen", "8"), Tag("Shoujo", "40"), Tag("Shounen", "38"),
        Tag("Otaku", "264"), Tag("Tsundere", "313"), Tag("Yandere", "315"),
        Tag("Animal Characteristics", "274"), Tag("Beautiful Female Lead", "72"),
        Tag("Big Breasts", "123"), Tag("Flat Chest", "320"), Tag("Glasses-Wearing Male Lead", "71"),
        Tag("Handsome Male Lead", "68"), Tag("Kemonomimi", "279"), Tag("MILF", "339"),
        Tag("Small Breasts", "124"), Tag("Young Male Lead", "787"),
        Tag("Adult Cast", "159"), Tag("Bisexual", "382"), Tag("Ensemble Cast", "362"),
        Tag("Female Lead", "59"), Tag("Male Lead", "58"), Tag("Non-Human Protagonist", "247"),
        Tag("Primarily Adult Cast", "158"), Tag("Primarily Female Cast", "333"),
        Tag("Primarily Male Cast", "335"), Tag("Primarily Teen Cast", "334"),
        Tag("Strong Female Lead", "69"), Tag("Strong Male Lead", "67"),
        Tag("Adapted to Anime", "166"), Tag("Based on a Light Novel", "76"),
        Tag("Based on a Novel", "75"), Tag("Based on a Video Game", "77"),
        Tag("Based on a Web Novel", "74"), Tag("College", "257"), Tag("Company", "1205"),
        Tag("Countryside", "415"), Tag("Europe", "405"), Tag("Foreign", "336"),
        Tag("High School", "162"), Tag("Hospital", "760"), Tag("Japan", "225"),
        Tag("School", "107"), Tag("School Clubs", "356"),
        Tag("Amnesia", "283"), Tag("Appearance Different from Personality", "651"),
        Tag("Caught in the Act", "874"), Tag("Dead Family Member", "831"),
        Tag("Family Drama", "848"), Tag("Flashbacks", "449"), Tag("Gender Bender", "12"),
        Tag("Love Triangle", "125"), Tag("Male Lead Falls in Love First", "653"),
        Tag("Misunderstandings", "647"), Tag("Past Plays a Big Role", "648"),
        Tag("Reincarnation", "126"), Tag("Secret Identity", "260"),
        Tag("Time Manipulation", "311"), Tag("Time Skip", "172"), Tag("Time Travel", "249"),
        Tag("Tragic Past", "898"), Tag("Weak to Strong", "1064"),
        Tag("Delinquents", "239"), Tag("Detectives", "240"), Tag("Idols", "281"),
        Tag("Maids", "116"), Tag("Office Lady", "312"), Tag("Office Worker", "429"),
        Tag("School Girl", "788"), Tag("Teachers", "175"),
        Tag("Age Gap", "106"), Tag("Childhood Friends", "97"), Tag("Coworkers", "286"),
        Tag("Female Harem", "163"), Tag("Friends to Lovers", "243"), Tag("Friendship", "242"),
        Tag("Harem", "20"), Tag("Heterosexual", "108"), Tag("Incest", "174"),
        Tag("Infidelity", "231"), Tag("Interspecies Relationship", "308"),
        Tag("Love-Hate Relationship", "889"), Tag("Master-Servant Relationship", "406"),
        Tag("Older Female Younger Male", "114"), Tag("Older Male Younger Female", "649"),
        Tag("Older Uke Younger Seme", "880"), Tag("Siblings", "254"),
        Tag("Student-Student Relationship", "573"), Tag("Student-Teacher Relationship", "177"),
        Tag("Twins", "253"),
        Tag("Chinese Ambience", "588"), Tag("European Ambience", "450"),
        Tag("Fantasy World", "642"), Tag("Feudal Japan", "606"), Tag("Game Elements", "399"),
        Tag("Game World", "641"), Tag("Isekai", "94"), Tag("Isekaied Into a Novel", "258"),
        Tag("Mecha", "11"), Tag("Mythology", "259"), Tag("Urban", "338"),
        Tag("Urban Fantasy", "261"),
        Tag("Anal Intercourse", "100"), Tag("Bondage", "280"), Tag("Boobjob", "381"),
        Tag("Borderline H", "448"), Tag("Cunnilingus", "171"), Tag("Defloration", "306"),
        Tag("Dubious Consent", "985"), Tag("Ecchi", "21"), Tag("Erotica", "14"),
        Tag("Exhibitionism", "287"), Tag("Group Intercourse", "373"),
        Tag("Handjob", "303"), Tag("Lolicon", "28"), Tag("Masturbation", "161"),
        Tag("Mature", "15"), Tag("Nakadashi", "169"), Tag("Netorare", "232"),
        Tag("Nudity", "109"), Tag("Oral Intercourse", "99"), Tag("Outdoor Intercourse", "307"),
        Tag("Public Intercourse", "103"), Tag("Rape", "95"), Tag("Sex Addict", "650"),
        Tag("Sex Toys", "289"), Tag("Shotacon", "35"), Tag("Teens Love", "374"),
        Tag("Threesome", "173"), Tag("Virginity", "369"),
        Tag("Animals", "278"), Tag("Cats", "284"), Tag("Demons", "160"),
        Tag("Ghosts", "229"), Tag("Gods", "176"), Tag("Monsters", "395"),
        Tag("Non-human", "547"), Tag("Vampires", "252"),
        Tag("21st century", "132"), Tag("Betrayal", "403"), Tag("Bullying", "235"),
        Tag("Cohabitation", "228"), Tag("Coming of Age", "117"), Tag("Danmei", "305"),
        Tag("Depression", "1090"), Tag("Family Life", "282"), Tag("Female Empowerment", "1816"),
        Tag("Forbidden Love", "699"), Tag("Gore", "262"), Tag("Gourmet", "2"),
        Tag("Harlequin", "304"), Tag("Jealousy", "881"), Tag("LGBTQ+", "326"),
        Tag("Love Confession", "882"), Tag("Marriage", "360"), Tag("Mature Romance", "241"),
        Tag("Medical", "350"), Tag("Military", "230"), Tag("Music", "27"),
        Tag("Nobility", "127"), Tag("Obsessive Love", "893"), Tag("Orphans", "237"),
        Tag("Religion", "498"), Tag("Reunion", "984"), Tag("Revenge", "227"),
        Tag("Royalty", "128"), Tag("School Life", "42"), Tag("Shoujo Ai", "47"),
        Tag("Shounen Ai", "23"), Tag("Special Ability", "883"), Tag("Sports", "30"),
        Tag("Suicide", "309"), Tag("Super Powers", "236"), Tag("Unrequited Love", "226"),
        Tag("Violence", "830"), Tag("War", "238"), Tag("Yaoi", "16"), Tag("Yuri", "33"),
        Tag("4-Koma", "105"), Tag("Anthology", "113"), Tag("Chinese Novels", "1112"),
        Tag("Collection of Stories", "111"), Tag("Doujinshi", "24"), Tag("Episodic", "115"),
        Tag("Full color", "57"), Tag("Korean Novels", "1111"), Tag("Light Novel", "466"),
        Tag("Longstrip", "93"), Tag("One Shot", "110"), Tag("Web Comic", "428"),
        Tag("Web Novel", "427"), Tag("Magic", "121")
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = (allGenres.map { (name, id) -> MangaTag(name, "genre:$id", source) } +
                allTags.map { (name, id) -> MangaTag(name, "tag:$id", source) }).toSet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED, MangaState.ABANDONED),
        availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA, ContentType.OTHER),
        availableContentRating = EnumSet.of(ContentRating.SAFE, ContentRating.ADULT),
    )

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> = getSearchPage(page, order, filter)

    private suspend fun getSearchPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query ?: "*"
        val url = "$baseUrl/collections/manga/documents/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", query)
            if (query != "*") {
                addQueryParameter("query_by", "title,englishTitle,otherNames,authors")
                addQueryParameter("query_by_weights", "4,3,2,1")
                addQueryParameter("num_typos", "4,3,2,1")
            }
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", pageSize.toString())

            val filterParts = mutableListOf("hidden:!=true", "views:>0")

            val ratings = filter.contentRating
            when {
                ratings.contains(ContentRating.ADULT) && !ratings.contains(ContentRating.SAFE) -> {
                    filterParts.add("isAdult:=true")
                }
                ratings.contains(ContentRating.SAFE) && !ratings.contains(ContentRating.ADULT) -> {
                    filterParts.add("isAdult:=false")
                }
                // both or none: no filter shows all
                else -> { /* no isAdult filter */ }
            }
            filterParts.add("(mbContentRating:=[`Safe`,`Suggestive`,`Erotica`] || mbContentRating:!=*)")

            val types = filter.types.mapNotNull { ct ->
                when (ct) {
                    ContentType.MANGA -> "Manga"
                    ContentType.MANHWA -> "Manwha"
                    ContentType.MANHUA -> "Manhua"
                    ContentType.OTHER -> "OEL"
                    else -> null
                }
            }
            if (types.isNotEmpty()) {
                filterParts.add("type:=[${types.joinToString(",") { "`$it`" }}]")
            }

            val statusVals = filter.states.mapNotNull { st ->
                when (st) {
                    MangaState.ONGOING -> "Ongoing"
                    MangaState.FINISHED -> "Completed"
                    MangaState.PAUSED -> "Hiatus"
                    MangaState.ABANDONED -> "Canceled"
                    else -> null
                }
            }
            if (statusVals.isNotEmpty()) {
                filterParts.add("status:=[${statusVals.joinToString(",") { "`$it`" }}]")
            }

            val includedGenres = mutableListOf<String>()
            val excludedGenres = mutableListOf<String>()
            val includedTags = mutableListOf<String>()
            val excludedTags = mutableListOf<String>()

            for (tag in filter.tags) {
                val key = tag.key
                when {
                    key.startsWith("genre:") -> includedGenres.add(key.removePrefix("genre:"))
                    key.startsWith("tag:") -> includedTags.add(key.removePrefix("tag:"))
                }
            }
            for (tag in filter.tagsExclude) {
                val key = tag.key
                when {
                    key.startsWith("genre:") -> excludedGenres.add(key.removePrefix("genre:"))
                    key.startsWith("tag:") -> excludedTags.add(key.removePrefix("tag:"))
                }
            }

            if (includedGenres.isNotEmpty()) {
                filterParts.add(includedGenres.joinToString(" && ") { "genreIds:=`$it`" })
            }
            if (excludedGenres.isNotEmpty()) {
                filterParts.add("genreIds:!=[${excludedGenres.joinToString(",") { "`$it`" }}]")
            }
            if (includedTags.isNotEmpty()) {
                filterParts.add(includedTags.joinToString(" && ") { "tagIds:=`$it`" })
            }
            if (excludedTags.isNotEmpty()) {
                filterParts.add("tagIds:!=[${excludedTags.joinToString(",") { "`$it`" }}]")
            }

            addQueryParameter("filter_by", filterParts.joinToString(" && "))

            val sort = when (order) {
                SortOrder.POPULARITY -> "trending:desc"
                SortOrder.UPDATED -> "released:desc"
                SortOrder.ADDED -> "dateAdded:desc"
                SortOrder.RELEVANCE -> "chapterCount:desc"
                else -> "released:desc"
            }
            addQueryParameter("sort_by", sort)
            addQueryParameter("sort_by", sort)
        }.build()

        val json = webClient.httpGet(url, apiHeaders).parseJson()
        val hits = json.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).map { i ->
            hits.getJSONObject(i).getJSONObject("document").toManga()
        }
    }

    // limit tags to 20
    override suspend fun getDetails(manga: Manga): Manga {
        val mangaId = manga.url.substringAfterLast("/")
        val pageJson = webClient.httpGet("$baseUrl/api/manga/page?id=$mangaId", apiHeaders).parseJson()
        val mangaPage = pageJson.optJSONObject("mangaPage") ?: return manga

        val title = mangaPage.optString("title").ifEmpty { mangaPage.optString("englishTitle", manga.title) }
        val description = mangaPage.optString("synopsis") ?: manga.description

        val posterObj = mangaPage.optJSONObject("poster")
        val posterImage = posterObj?.optString("mediumImage")
        val coverUrl = if (!posterImage.isNullOrEmpty()) {
            "https://$domain/static/$posterImage"
        } else manga.coverUrl

        val authors = mangaPage.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.toSet()
        } ?: emptySet()

        val statusText = mangaPage.optString("status").orEmpty()
        val state = when (statusText.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            "canceled" -> MangaState.ABANDONED
            else -> manga.state
        }

        val tagSet = mutableSetOf<MangaTag>()
        mangaPage.optJSONArray("genres")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 10)) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: continue
                tagSet.add(MangaTag(name, "genre:${obj.optString("id")}", source))
            }
        }
        mangaPage.optJSONArray("tags")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 10)) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: continue
                tagSet.add(MangaTag(name, "tag:${obj.optString("id")}", source))
            }
        }

        val chaptersJson = webClient.httpGet("$baseUrl/api/manga/allChapters?mangaId=$mangaId", apiHeaders).parseJson()
        val chaptersArray = chaptersJson.optJSONArray("chapters") ?: JSONArray()
        val scanlators = mutableMapOf<String, String>()
        mangaPage.optJSONArray("scanlators")?.let { scanArr ->
            for (i in 0 until scanArr.length()) {
                val sc = scanArr.optJSONObject(i) ?: continue
                val id = sc.optString("id").takeIf { it.isNotEmpty() } ?: continue
                val name = sc.optString("name").takeIf { it.isNotEmpty() } ?: continue
                scanlators[id] = name
            }
        }

        val chapters = (0 until chaptersArray.length()).map { i ->
            val ch = chaptersArray.getJSONObject(i)
            val chId = ch.getString("id")
            val number = ch.optDouble("number", 0.0).toFloat()
            val chTitle = ch.optString("title")
            val scanId = ch.optString("scanlationMangaId").nullIfEmpty()
            val scanName = scanId?.let { scanlators[it] }
            val createdAt = ch.optLong("createdAt", 0L)

            MangaChapter(
                id = generateUid("$mangaId/$chId"),
                title = chTitle.ifBlank { "Chapter $number" },
                number = number,
                volume = 0,
                url = "$mangaId/$chId",
                uploadDate = createdAt,
                source = source,
                scanlator = scanName,
                branch = scanName,
            )
        }.reversed()

        val groupedChapters = if (chapters.map { it.branch }.distinct().size > 1) {
            chapters.map { it.copy(branch = it.branch ?: "Unknown") }
        } else {
            chapters.map { it.copy(branch = null, scanlator = null) }
        }

        val altTitles = mangaPage.optJSONArray("otherNames")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it) }.toSet()
        } ?: emptySet()

        return manga.copy(
            title = title,
            description = description,
            coverUrl = coverUrl,
            authors = authors,
            state = state,
            tags = tagSet,
            chapters = groupedChapters,
            altTitles = altTitles,
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> {
        val mangaId = seed.url.substringAfterLast("/")
        val pageJson = webClient.httpGet("$baseUrl/api/manga/page?id=$mangaId", apiHeaders).parseJson()
        val mangaPage = pageJson.optJSONObject("mangaPage") ?: return emptyList()
        val similar = mangaPage.optJSONArray("similarManga") ?: return emptyList()
        return (0 until similar.length()).map { i ->
            similar.getJSONObject(i).toManga()
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val (mangaId, chapterId) = chapter.url.split("/")
        val url = "$baseUrl/api/read/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("mangaId", mangaId)
            .addQueryParameter("chapterId", chapterId)
            .build()
        val json = webClient.httpGet(url, apiHeaders).parseJson()
        val pagesArray = json.getJSONObject("readChapter").getJSONArray("pages")
        return (0 until pagesArray.length()).map { i ->
            val page = pagesArray.getJSONObject(i)
            val imagePath = page.getString("image")
            val fullUrl = when {
                imagePath.startsWith("http") -> imagePath
                imagePath.startsWith("//") -> "https:$imagePath"
                else -> "https://$domain/static/${imagePath.removePrefix("/").removePrefix("static/")}"
            }
            MangaPage(id = generateUid(fullUrl), url = fullUrl, preview = null, source = source)
        }
    }

    private fun JSONObject.toManga(): Manga {
        val id = getString("id")
        val title = optString("title").ifEmpty { optString("englishTitle", "Unknown") }

        val imageRaw: Any? = when {
            has("poster") -> get("poster")
            has("image")  -> getString("image")
            else -> null
        }

        val imagePath: String? = when (imageRaw) {
            is String -> imageRaw.takeIf { it.isNotBlank() }
            is JSONObject -> {
                imageRaw.optString("image").nullIfEmpty()
                    ?: imageRaw.optString("mediumImage").nullIfEmpty()
                    ?: imageRaw.optString("smallImage").nullIfEmpty()
            }
            else -> null
        }?.removePrefix("/")?.removePrefix("static/")

        val coverUrl = if (!imagePath.isNullOrBlank()) {
            when {
                imagePath.startsWith("http") -> imagePath
                imagePath.startsWith("//") -> "https:$imagePath"
                else -> "https://$domain/static/$imagePath"
            }
        } else null

        return Manga(
            id = generateUid(id),
            title = title,
            altTitles = emptySet(),
            url = "/manga/$id",
            publicUrl = "$baseUrl/manga/$id",
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = coverUrl,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = source,
        )
    }

    private data class Genre(val name: String, val id: String)
    private data class Tag(val name: String, val id: String)
}
