package org.koitharu.kotatsu.parsers.site.kotatsu.ru

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
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
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.parseSafe
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("WAMANGA", "WaManga", "ru", type = ContentType.MANGA)
internal class WaMangaParser(
	context: MangaLoaderContext,
) : SinglePageMangaParser(context, MangaParserSource.WAMANGA) {

	override val configKeyDomain = ConfigKey.Domain("wamanga.ru")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.ALPHABETICAL)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchCatalog()
			.flatMapTo(linkedSetOf()) { it.parseWaMangaTags() },
	)

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
		val requiredGenres = filter.tags.mapTo(mutableSetOf()) { it.key }

		return fetchCatalog()
			.asSequence()
			.filter { item ->
				query == null || item.parseWaMangaSearchText().any { value ->
					value.contains(query, ignoreCase = true)
				}
			}
			.filter { item ->
				requiredGenres.isEmpty() || item.parseWaMangaGenres().containsAll(requiredGenres)
			}
			.map(::parseManga)
			.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
			.toList()
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val details = webClient.httpGet(apiUrl("manga/${manga.url}")).parseJson()
		val chapters = webClient.httpGet(apiUrl("manga/${manga.url}/chapters"))
			.parseJsonArray()
			.mapJSON(::parseChapter)
			.sortedByDescending { it.number }

		return parseManga(details).copy(
			id = manga.id,
			url = manga.url,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return webClient.httpGet(apiUrl("chapters/${chapter.url}"))
			.parseJson()
			.getJSONArray("files")
			.asTypedList<JSONObject>()
			.sortedBy { it.getString("position").toDouble() }
			.map { file ->
				val imageUrl = file.getString("diskFile").toWaMangaAbsoluteUrl(domain)
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
	}

	private suspend fun fetchCatalog(): List<JSONObject> = webClient
		.httpGet(apiUrl("manga"))
		.parseJsonArray()
		.asTypedList()

	private fun parseManga(item: JSONObject): Manga {
		val id = item.getString("id")
		val slug = item.getString("slug")
		return Manga(
			id = generateUid(id),
			url = id,
			title = item.getString("title"),
			altTitles = item.parseWaMangaAlternativeTitles(),
			publicUrl = "https://$domain/manga/$slug",
			rating = RATING_UNKNOWN,
			coverUrl = item.getString("coverUrl").toWaMangaAbsoluteUrl(domain),
			largeCoverUrl = item.getString("imageUrl").toWaMangaAbsoluteUrl(domain),
			tags = item.parseWaMangaTags(),
			state = item.getString("statusTitle").toWaMangaState(),
			authors = item.getJSONArray("authors").asTypedList<String>().toSet(),
			source = source,
			contentRating = if (item.getBoolean("isAdult")) ContentRating.ADULT else ContentRating.SAFE,
			description = item.getStringOrNull("description"),
		)
	}

	private fun parseChapter(item: JSONObject): MangaChapter {
		val id = item.getString("id")
		return MangaChapter(
			id = generateUid(id),
			url = id,
			source = source,
			number = item.getDouble("position").toFloat(),
			volume = 0,
			title = item.getStringOrNull("title")?.takeIf { it.isNotBlank() },
			scanlator = null,
			uploadDate = WA_MANGA_DATE_FORMAT.get().parseSafe(item.getString("createdAt")),
			branch = null,
		)
	}

	private fun apiUrl(path: String): String = "https://$domain/api/v1/$path"
}

internal fun JSONObject.parseWaMangaAlternativeTitles(): Set<String> = buildSet {
	getStringOrNull("titleEnglish")?.takeIf { it.isNotBlank() }?.let(::add)
	getJSONArray("alternateTitles").asTypedList<String>().filterTo(this) { it.isNotBlank() }
}

internal fun JSONObject.parseWaMangaGenres(): Set<String> =
	getJSONArray("genres").asTypedList<String>().toSet()

internal fun JSONObject.parseWaMangaSearchText(): Set<String> = buildSet {
	add(getString("title"))
	addAll(parseWaMangaAlternativeTitles())
}

private fun JSONObject.parseWaMangaTags(): Set<MangaTag> = parseWaMangaGenres().mapTo(linkedSetOf()) { genre ->
	MangaTag(
		title = genre,
		key = genre,
		source = MangaParserSource.WAMANGA,
	)
}

private fun String.toWaMangaState(): MangaState = when (this) {
	"ongoing" -> MangaState.ONGOING
	"completed" -> MangaState.FINISHED
	"abandoned" -> MangaState.ABANDONED
	else -> error("Unsupported WaManga status: $this")
}

private fun String.toWaMangaAbsoluteUrl(domain: String): String = when {
	startsWith("https://") || startsWith("http://") -> this
	startsWith('/') -> "https://$domain$this"
	else -> error("Unsupported WaManga asset URL: $this")
}

private val WA_MANGA_DATE_FORMAT = ThreadLocal.withInitial {
	SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)
}
