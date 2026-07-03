package org.koitharu.kotatsu.parsers.site.madara.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("UTOON", "UToon", "en")
internal class UToon(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.UTOON, pageSize = 28) {

	override val configKeyDomain = ConfigKey.Domain("utoon.net")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = genreTags,
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/manga/")
			if (page > 1) {
				append("page/")
				append(page)
				append('/')
			}
		}.toHttpUrl().newBuilder().apply {
			filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
			when (order) {
				SortOrder.POPULARITY -> addQueryParameter("orderby", "popular")
				SortOrder.NEWEST -> addQueryParameter("orderby", "new")
				SortOrder.ALPHABETICAL -> addQueryParameter("orderby", "alphabet")
				else -> {}
			}
			filter.states.firstOrNull()?.let { state ->
				val value = when (state) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					else -> null
				}
				if (value != null) addQueryParameter("status", value)
			}
			filter.tags.firstOrNull()?.key?.let { addQueryParameter("genre", it) }
		}.build()

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select(".agrid .acard").map { it.toManga() }
	}

	private fun Element.toManga(): Manga {
		val href = attrAsRelativeUrl("href")
		val status = selectFirst(".ac-status")?.text()
		return Manga(
			id = generateUid(href),
			title = selectFirst(".ac-t")?.text().orEmpty(),
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = selectFirst(".ac-rate")?.text()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
			contentRating = null,
			coverUrl = selectFirst("img")?.src(),
			tags = emptySet(),
			state = parseState(status),
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		var author: String? = null
		var artist: String? = null
		var state: MangaState? = null
		doc.select(".sinfo-grid .sir").forEach { row ->
			val label = row.selectFirst(".l")?.text()?.trim().orEmpty()
			val value = row.selectFirst(".v")?.text()?.trim().orEmpty()
			when (label.lowercase(Locale.ROOT)) {
				"author" -> author = value.nullIfEmpty()
				"artist" -> artist = value.nullIfEmpty()
				"status" -> state = parseState(value)
			}
		}
		if (state == null) {
			val hinfo = doc.select(".hinfo .hi").joinToString(" ") { it.text() }
			state = parseState(hinfo)
		}

		return manga.copy(
			title = doc.selectFirst(".htitle")?.text() ?: manga.title,
			coverUrl = doc.selectFirst(".poster img")?.src() ?: manga.coverUrl,
			description = doc.selectFirst(".syn")?.text(),
			tags = doc.select(".genres .genre").mapNotNullToSet { genre ->
				val title = genre.text().trim().nullIfEmpty() ?: return@mapNotNullToSet null
				val key = genre.attr("href").trimEnd('/').substringAfterLast('/').nullIfEmpty()
					?: return@mapNotNullToSet null
				MangaTag(key = key, title = title, source = source)
			},
			authors = setOfNotNull(author ?: artist),
			state = state,
			chapters = parseChapters(doc),
		)
	}

	private fun parseChapters(doc: Document): List<MangaChapter> {
		val script = doc.select("script").firstOrNull { it.data().contains("var CH=") }?.data()
			?: return emptyList()
		val json = script.substringAfter("var CH=").substringBefore(";").trim()
		val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()

		return (0 until array.length()).mapNotNull { i ->
			val obj = array.optJSONObject(i) ?: return@mapNotNull null
			if (obj.optBoolean("locked", false)) return@mapNotNull null // skip premium/locked chapters
			val chapterUrl = obj.optString("url").nullIfEmpty()?.toRelativeUrl(domain) ?: return@mapNotNull null
			val label = obj.optString("label").nullIfEmpty() ?: return@mapNotNull null
			MangaChapter(
				id = generateUid(chapterUrl),
				title = label,
				number = obj.optDouble("num", 0.0).toFloat(),
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = parseChapterDate(obj.optString("ago")),
				branch = null,
				source = source,
			)
		}.reversed() // CH is newest-first; Kotatsu expects oldest-first
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		if (doc.selectFirst(".zx-locked__card") != null) {
			throw IllegalStateException("This is a premium chapter that requires coins to read on the website")
		}
		return doc.select("div.reading-content img").mapNotNull { img ->
			val imageUrl = img.src() ?: return@mapNotNull null
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseState(status: String?): MangaState? = when {
		status.isNullOrEmpty() -> null
		status.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
		status.contains("completed", ignoreCase = true) -> MangaState.FINISHED
		status.contains("on hold", ignoreCase = true) ||
			status.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
		status.contains("canceled", ignoreCase = true) ||
			status.contains("cancelled", ignoreCase = true) -> MangaState.ABANDONED
		else -> null
	}

	private fun parseChapterDate(date: String?): Long {
		val value = date?.trim()?.lowercase(Locale.ROOT)?.nullIfEmpty() ?: return 0L
		if (!value.endsWith("ago")) return 0L
		val amount = Regex("""\d+""").find(value)?.value?.toIntOrNull() ?: return 0L
		val calendar = Calendar.getInstance()
		when {
			value.contains("second") -> calendar.add(Calendar.SECOND, -amount)
			value.contains("minute") -> calendar.add(Calendar.MINUTE, -amount)
			value.contains("hour") -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
			value.contains("day") -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
			value.contains("week") -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
			value.contains("month") -> calendar.add(Calendar.MONTH, -amount)
			value.contains("year") -> calendar.add(Calendar.YEAR, -amount)
			else -> return 0L
		}
		return calendar.timeInMillis
	}

	private val genreTags: Set<MangaTag>
		get() = setOf(
			"fantasy" to "Fantasy",
			"drama" to "Drama",
			"adventure" to "Adventure",
			"action" to "Action",
			"comedy" to "Comedy",
			"shounen" to "Shounen",
			"comic" to "Comic",
			"manhwa" to "Manhwa",
			"fight" to "Fight",
			"magic" to "Magic",
			"supernatural" to "Supernatural",
			"manga" to "Manga",
			"romance" to "Romance",
			"martial-arts" to "Martial Arts",
			"crime" to "Crime",
			"hunters" to "Hunters",
			"mystery" to "Mystery",
			"isekai" to "Isekai",
			"historical" to "Historical",
			"mangatoon" to "Mangatoon",
			"reincarnation" to "Reincarnation",
			"shoujo" to "Shoujo",
			"slice-of-life" to "Slice of Life",
			"manhua" to "Manhua",
		).mapToSet { (key, title) -> MangaTag(key = key, title = title, source = source) }
}
