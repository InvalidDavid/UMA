package org.koitharu.kotatsu.parsers.parsers

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

// TODO
// add filter by genres / type / status / rating
// cant be called via API json

abstract class HiperParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    private val domainName: String,
) : PagedMangaParser(context, source, pageSize = 30) {

    protected open val mangaPath = "manga"

    override val configKeyDomain = ConfigKey.Domain(domainName)
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.NEWEST,
        SortOrder.NEWEST_ASC, SortOrder.RATING, SortOrder.ALPHABETICAL, SortOrder.RELEVANCE
    )
    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
    )

    private suspend fun executeAuthenticatedRequest(url: String): okhttp3.Response {
        val res = webClient.httpGet(url, getRequestHeaders())
        if (res.code == 401) {
            res.close()
            val acceptHeaders = Headers.Builder()
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            webClient.httpGet("https://$domainName/", acceptHeaders).close()
            return webClient.httpGet(url, getRequestHeaders())
        }
        return res
    }

    private fun apiUrl(trpcPath: String, input: String) =
        "https://$domainName/api/trpc/$trpcPath?batch=1&input=${input.urlEncoded()}"

    private fun searchPayload(query: String, sort: String, limit: Int, offset: Int): String {
        return JSONObject().apply {
            put("0", JSONObject().apply {
                put("json", JSONObject().apply {
                    put("q", query)
                    put("sort", sort)
                    put("filters", JSONObject())          // empty {}
                    put("limit", limit)
                    put("offset", offset)
                    put("maxRating", "pornographic")
                })
                put("meta", JSONObject().apply {
                    put("values", JSONObject().apply {
                        put("filters.genres", JSONArray().put("undefined"))
                        put("filters.type", JSONArray().put("undefined"))
                        put("filters.status", JSONArray().put("undefined"))
                        put("filters.contentRating", JSONArray().put("undefined"))
                        put("filters.author", JSONArray().put("undefined"))
                        put("filters.artist", JSONArray().put("undefined"))
                        put("filters.year", JSONArray().put("undefined"))
                    })
                })
            })
        }.toString()
    }

    private fun detailsPayload(slug: String): String {
        return JSONObject().apply {
            put("0", JSONObject().apply {
                // "json": null omitted
                put("meta", JSONObject().apply {
                    put("values", JSONArray().put("undefined"))
                })
            })
            put("1", JSONObject().apply {
                put("json", JSONObject().apply {
                    put("slug", slug)
                })
            })
        }.toString()
    }

    private fun chaptersPayload(mangaId: Long): String {
        return JSONObject().apply {
            put("0", JSONObject().apply {
                put("json", JSONObject().apply {
                    put("values", JSONArray().put("undefined"))
                })
            })
            put("1", JSONObject().apply {
                put("json", JSONObject().apply {
                    put("seriesId", mangaId)
                    put("sort", "best")
                    put("page", 1)
                    put("limit", 200)
                })
                put("meta", JSONObject().apply {
                    put("values", JSONObject().apply {
                        put("chapterId", JSONArray().put("undefined"))
                    })
                })
            })
            put("2", JSONObject().apply {
                put("json", JSONObject().apply {
                    put("seriesId", mangaId)
                })
            })
        }.toString()
    }

    private fun pagesPayload(slug: String, number: Float): String {
        return JSONObject().apply {
            put("0", JSONObject().apply {
                put("meta", JSONObject().apply {
                    put("values", JSONArray().put("undefined"))
                })
            })
            put("1", JSONObject().apply {
                put("json", JSONObject().apply {
                    put("slug", slug)
                })
            })
            put("2", JSONObject().apply {
                put("json", JSONObject().apply {
                    put("seriesSlug", slug)
                    put("chapterNumber", number.toDouble())
                })
            })
            put("3", JSONObject().apply {
                put("json", JSONObject().apply {
                    put("position", "footer_bottom")
                })
            })
        }.toString()
    }

    private fun parseLastItemJsonArray(body: String): JSONArray? = try {
        val root = JSONArray(body)
        root.optJSONObject(root.length() - 1)
            ?.optJSONObject("result")
            ?.optJSONObject("data")
            ?.optJSONArray("json")
    } catch (_: Exception) { null }

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
    )
    
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val q = filter.query?.trim().orEmpty()
        val sort = when (order) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.UPDATED -> "recent"
            SortOrder.NEWEST -> "newest"
            SortOrder.NEWEST_ASC -> "oldest"
            SortOrder.RATING -> "score"
            SortOrder.ALPHABETICAL -> "alphabetical"
            SortOrder.RELEVANCE -> "relevance"
            else -> "newest"
        }
        val input = searchPayload(q, sort, pageSize, (page - 1) * pageSize)
        val res = executeAuthenticatedRequest(apiUrl("search.query", input))
        val body = res.body?.string() ?: return emptyList()
        val root = JSONArray(body)
        val first = root.optJSONObject(0) ?: return emptyList()
        val hits = first.optJSONObject("result")
            ?.optJSONObject("data")
            ?.optJSONObject("json")
            ?.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            hits.optJSONObject(i)?.let { obj ->
                val id = obj.optLong("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
                val slug = obj.optString("slug", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val title = obj.optString("title", "")
                val cover = obj.optString("coverUrl", "")
                Manga(
                    id = generateUid("/$mangaPath/$slug#$id"),
                    url = "/$mangaPath/$slug#$id",
                    publicUrl = "https://$domainName/$mangaPath/$slug#$id",
                    title = title, coverUrl = cover,
                    altTitles = emptySet(), authors = emptySet(), tags = emptySet(),
                    rating = RATING_UNKNOWN, state = null,
                    contentRating = ContentRating.ADULT, source = source
                )
            }
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfterLast("$mangaPath/").substringBefore("#")
            .takeIf { it.isNotBlank() } ?: return manga
        val res = executeAuthenticatedRequest(apiUrl("auth.me,series.bySlugWithGenres", detailsPayload(slug)))
        val body = res.body?.string() ?: return manga
        val detailJson = try {
            val arr = JSONArray(body)
            arr.getJSONObject(arr.length() - 1)
                .optJSONObject("result")?.optJSONObject("data")?.optJSONObject("json")
        } catch (_: Exception) { null } ?: return manga

        val title = detailJson.optString("title", manga.title)
        val desc = detailJson.optString("synopsis", manga.description)
        val cover = detailJson.optString("coverUrl", manga.coverUrl)
        val authors = detailJson.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        val genres = detailJson.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        val status = detailJson.optString("status", null)
        val chapters = loadChapters(manga)

        return manga.copy(
            title = title, description = desc, coverUrl = cover,
            authors = authors.toSet(),
            tags = genres.map { MangaTag(it.lowercase(), it, source) }.toSet(),
            state = when (status?.lowercase()) {
                "ongoing" -> MangaState.ONGOING
                "completed" -> MangaState.FINISHED
                "hiatus" -> MangaState.PAUSED
                "cancelled" -> MangaState.ABANDONED
                else -> null
            },
            chapters = chapters,
        )
    }

    private suspend fun loadChapters(manga: Manga): List<MangaChapter> {
        val mangaId = manga.url.substringAfterLast("#").toLongOrNull() ?: return emptyList()
        val slug = manga.url.substringAfterLast("$mangaPath/").substringBefore("#").ifBlank { return emptyList() }
        val res = executeAuthenticatedRequest(apiUrl("auth.me,comments.list,series.chapters", chaptersPayload(mangaId)))
        val body = res.body?.string() ?: return emptyList()
        val chaptersArray = parseLastItemJsonArray(body) ?: return emptyList()
        return (0 until chaptersArray.length()).mapNotNull { i ->
            chaptersArray.optJSONObject(i)?.let { obj ->
                val number = obj.optDouble("number", 0.0).toFloat()
                val createdAt = obj.optString("createdAt", null)
                val uploadDate = createdAt?.let { dateFormat.parse(it)?.time } ?: 0L
                MangaChapter(
                    id = generateUid("/$mangaPath/$slug/$number"),
                    url = "/$mangaPath/$slug/$number",
                    title = "Chapter ${number.formatForTitle()}",
                    number = number, volume = 0, uploadDate = uploadDate,
                    scanlator = null, branch = null, source = source
                )
            }
        }.sortedBy { it.number }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val path = chapter.url.removePrefix("/$mangaPath/")
        val slug = path.substringBefore("/")
        val num = path.substringAfter("/").toFloatOrNull() ?: return emptyList()
        val input = pagesPayload(slug, num)

        var res = executeAuthenticatedRequest(apiUrl("auth.me,series.bySlug,reader.chapterPages", input))
        var body = res.body?.string() ?: return emptyList()
        var pages = parsePages(body)
        if (pages != null) return pages

        res.close()
        val chapterUrl = "https://$domainName/$mangaPath/$slug/$num"
        webClient.httpGet(chapterUrl, getRequestHeaders()).close()
        res = executeAuthenticatedRequest(apiUrl("auth.me,series.bySlug,reader.chapterPages", input))
        body = res.body?.string() ?: return emptyList()
        pages = parsePages(body)
        return pages ?: emptyList()
    }

    private fun parsePages(body: String): List<MangaPage>? {
        val elements = try { JSONArray(body) } catch (_: Exception) { return null }
        for (i in 0 until elements.length()) {
            if (elements.optJSONObject(i)?.has("error") == true) return null
        }
        val last = elements.optJSONObject(elements.length() - 1) ?: return null
        val items = last.optJSONObject("result")
            ?.optJSONObject("data")
            ?.optJSONArray("json") ?: return null
        return (0 until items.length()).mapNotNull { i ->
            val page = items.optJSONObject(i) ?: return@mapNotNull null
            val url = page.optString("webpUrl", "").ifEmpty {
                page.optString("avifUrl", "")
            }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    override suspend fun getPageUrl(page: MangaPage) = page.url

    private fun Float.formatForTitle() = if (this == toLong().toFloat()) toLong().toString() else toString()

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    }
}
