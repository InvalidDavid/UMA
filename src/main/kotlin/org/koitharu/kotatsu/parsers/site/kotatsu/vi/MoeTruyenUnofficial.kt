package org.koitharu.kotatsu.parsers.site.kotatsu.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONToSet
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.urlBuilder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("BFANGTEAM", "Moè Truyện (Unofficial)", "vi")
internal class MoeTruyenUnofficial (context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.BFANGTEAM, 100) {

	override val configKeyDomain = ConfigKey.Domain("moetruyen.net")

	/**
	 * Public API by SuiCaoDex (Unofficial)
	 * This API uses the same database with MoeTruyen source
	**/
	private val apiSuffix = "v2"
	private val apiDomain = "moe.suicaodex.com"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.remove(userAgentKey)
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add(CommonHeaders.ORIGIN, "https://$domain")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED, // updated_at
		SortOrder.ALPHABETICAL, // title
		SortOrder.POPULARITY, // popular
		SortOrder.POPULARITY_TODAY, // views, 24h
		SortOrder.POPULARITY_WEEK, // views, 1w
		SortOrder.POPULARITY_MONTH, // views, 1m
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = availableTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().host(apiDomain).addPathSegments("$apiSuffix/manga").apply {
			// default values, fixed
			addQueryParameter("page", page.toString())
			addQueryParameter("limit", pageSize.toString())
			addEncodedQueryParameter("include", "genres")

			if (!filter.query.isNullOrEmpty() || filter.tags.isNotEmpty() ||
				filter.states.isNotEmpty() || filter.tagsExclude.isNotEmpty()) {

				// keyword
				if (!filter.query.isNullOrEmpty()) {
					addEncodedQueryParameter("q", filter.query)
				}

				// genre
				if (filter.tags.isNotEmpty()) {
					addEncodedQueryParameter("genre", filter.tags.joinToString(",") { it.key })
				}

				// status
				if (filter.states.isNotEmpty()) {
					filter.states.oneOrThrowIfMany()?.let {
						when (it) {
							MangaState.ONGOING -> addQueryParameter("status", "ongoing")
							MangaState.FINISHED -> addQueryParameter("status", "completed")
							MangaState.PAUSED -> addQueryParameter("status", "hiatus")
							MangaState.ABANDONED -> addQueryParameter("status", "cancelled")
							else -> addQueryParameter("status", "unknown")
						}
					}
				}

				// exclude genre
				if (filter.tagsExclude.isNotEmpty()) {
					addEncodedQueryParameter("genrex", filter.tagsExclude.joinToString(",") { it.key })
				}

				// order
				when (order) {
					SortOrder.POPULARITY, SortOrder.POPULARITY_TODAY,
					SortOrder.POPULARITY_WEEK, SortOrder.POPULARITY_MONTH -> addQueryParameter("sort", "popular")
					SortOrder.ALPHABETICAL -> addQueryParameter("sort", "title")
					else -> addQueryParameter("sort", "updated_at") // default, updated
				}
			} else {
				// top manga for 3 special orders
				addPathSegment("top")
				when (order) {
					SortOrder.POPULARITY_TODAY -> {
						addQueryParameter("sort_by", "views")
						addQueryParameter("time", "24h")
					}
					SortOrder.POPULARITY_WEEK -> {
						addQueryParameter("sort_by", "views")
						addQueryParameter("time", "7d")
					}
					SortOrder.POPULARITY_MONTH -> {
						addQueryParameter("sort_by", "views")
						addQueryParameter("time", "30d")
					}
					else -> {
						addQueryParameter("sort_by", "views")
						addQueryParameter("time", "all_time")
					}
				}
			}
		}

		val request = webClient.httpGet(url.build()).parseJson()
		return request.getJSONArray("data").mapJSONNotNull { jo ->
			val id = jo.getLong("id")
			Manga(
				id = generateUid(id),
				url = id.toString(),
				publicUrl = "https://$domain/manga/$id",
				title = jo.getString("title"),
				altTitles = jo.getJSONArray("altTitles").asTypedList<String>().mapToSet { it },
				coverUrl = jo.getString("coverUrl"),
				largeCoverUrl = null,
				authors = setOf(jo.getString("author")),
				tags = jo.getJSONArray("genres").mapJSONToSet {
					MangaTag(
						title = it.getString("name"),
						key = it.getInt("id").toString(),
						source = source,
					)
				},
				state = when (jo.getString("status")) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"hiatus" -> MangaState.PAUSED
					"cancelled" -> MangaState.ABANDONED
					else -> null
				},
				description = jo.getString("description"),
				contentRating = null,
				source = source,
				rating = RATING_UNKNOWN,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = urlBuilder().host(apiDomain)
			.addEncodedPathSegments("$apiSuffix/manga/${manga.url}")
			.addEncodedQueryParameter("include", "genres")

		val json = webClient.httpGet(url.build()).parseJson()
		val tags = json.getJSONObject("data").optJSONArray("genres")?.mapJSONToSet {
			MangaTag(
				title = it.getString("name"),
				key = it.getInt("id").toString(),
				source = source,
			)
		} ?: manga.tags

		val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC+7")
		}

		val chapters = mutableListOf<MangaChapter>()
		var chapPage = 1
		var nextPage = true

		do {
			val url = urlBuilder().host(apiDomain)
				.addEncodedPathSegments("$apiSuffix/manga/${manga.url}/chapters")
				.addQueryParameter("page", chapPage.toString())
				.addQueryParameter("limit", pageSize.toString())

			val response = webClient.httpGet(url.build()).parseJson()
			val pageChapters = response.getJSONObject("data").getJSONArray("chapters").mapChapters(true) { i, jo ->
				val id = jo.getLong("id")
				MangaChapter(
					id = generateUid(id),
					title = jo.getStringOrNull("title"),
					number = jo.getFloatOrDefault("number", ((chapPage - 1) * 100 + i + 1).toFloat()),
					volume = 0,
					url = id.toString(),
					scanlator = jo.getJSONArray("groups").mapJSON { scan ->
						scan.getString("name")
					}.joinToString(", ") { it },
					uploadDate = chapterDateFormat.parseSafe(jo.getString("date")),
					branch = null,
					source = source,
				)
			}

			chapters.addAll(pageChapters)
			val metaObj = response.optJSONObject("meta")
			if (metaObj != null) {
				val pagination = metaObj.getJSONObject("pagination")
				val totalPages = pagination.getInt("totalPages")
				val page = pagination.getInt("page")
				if (page < totalPages) { chapPage++ } else { nextPage = false }
			} else { nextPage = false }
		} while (nextPage)

		val allChaps = chapters.sortedBy { it.number }
		return manga.copy(chapters = allChaps, tags = tags)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = urlBuilder().host(apiDomain)
			.addEncodedPathSegments("$apiSuffix/chapters/${chapter.url}")
		val response = webClient.httpGet(url.build()).parseJson()
		val data = response.getJSONObject("data")
		return data.getJSONArray("pageUrls").asTypedList<String>().map {
			MangaPage(
				id = generateUid(it),
				url = it,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun availableTags(): Set<MangaTag> {
		val url = urlBuilder().host(apiDomain)
			.addEncodedPathSegments("$apiSuffix/genres").build()
		val response = webClient.httpGet(url).parseJson()
		return response.getJSONArray("data").mapJSON {
			MangaTag(
				title = it.getString("name"),
				key = it.getInt("id").toString(),
				source = source,
			)
		}.toSet()
	}
}

