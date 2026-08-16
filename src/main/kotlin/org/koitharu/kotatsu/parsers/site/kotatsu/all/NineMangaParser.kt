package org.koitharu.kotatsu.parsers.site.kotatsu.all

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

internal abstract class NineMangaParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	defaultDomain: String,
) : PagedMangaParser(context, source, pageSize = 26), Interceptor {

	override val configKeyDomain = ConfigKey.Domain(defaultDomain)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	init {
		context.cookieJar.insertCookies(domain, "ninemanga_template_desk=yes")
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Accept-Language", "en-US;q=0.7,en;q=0.3")
		.build()

	override val availableSortOrders: Set<SortOrder> = Collections.singleton(
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchWithFiltersSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getOrCreateTagMap().values.toSet(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
		),
	)

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val newRequest = if (request.url.host == domain) {
			request.newBuilder().removeHeader("Referer").build()
		} else {
			request
		}
		return chain.proceed(newRequest)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			if (filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() || filter.states.isNotEmpty() || !filter.query.isNullOrEmpty()) {
				append("/search/")
				append("?page=")
				append(page.toString())

				filter.query?.let {
					append("&name_sel=contain&name=")
					append(it.urlEncoded())
				}

				append("&category_id=")
				append(filter.tags.joinToString(separator = ",") { it.key })

				append("&out_category_id=")
				append(filter.tagsExclude.joinToString(separator = ",") { it.key })

				filter.states.oneOrThrowIfMany()?.let {
					append("&completed_series=")
					when (it) {
						MangaState.ONGOING -> append("NO")
						MangaState.FINISHED -> append("YES")
						else -> Unit
					}
				}

			} else {
				append("/category/index_")
				append(page.toString())
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		val root = doc.body().selectFirstOrThrow("div.manga-list")
		val baseHost = root.baseUri().toHttpUrl().host
		return root.select("div.manga-item").map { node ->
			val a = node.selectFirstOrThrow("a:has(div.manga-name)")
			val href = a.attrAsAbsoluteUrl("href")
			val relUrl = href.toRelativeUrl(baseHost)
			Manga(
				id = generateUid(relUrl),
				url = relUrl,
				publicUrl = href,
				title = a.selectFirstOrThrow("div.manga-name").text(),
				altTitles = emptySet(),
				coverUrl = node.selectFirst("div.manga-img img")?.src(),
				rating = RATING_UNKNOWN,
				authors = emptySet(),
				contentRating = null,
				tags = emptySet(),
				state = null,
				source = source,
				description = node.selectFirst("div.manga-intro")?.html(),
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val detailsUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(detailsUrl).parseHtml()
		val root = doc.body().selectFirstOrThrow("div.book-info-parts")
		val tagMap = getOrCreateTagMap()
		val tags = root.select("[itemprop=genre]").mapNotNullToSet { tagMap[it.text()] }
		val authors = root.select("[itemprop=author] [itemprop=name]").mapToSet { it.text() }
		val chaptersUrl = detailsUrl.removeSuffix(".html") + "/chapters.html"
		val chaptersRoot = webClient.httpGet(chaptersUrl).parseHtml().body()
		return manga.copy(
			title = doc.selectFirst("h1.book-headline-name[itemprop=name]")?.textOrNull()
				?: manga.title,
			tags = tags,
			authors = authors,
			state = parseStatus(doc.selectFirst("span.book-status")?.text().orEmpty()),
			description = root.select("section.detail-synopsis").last()?.html(),
			chapters = chaptersRoot.select("ul.chapter-list > a:has(li.chp-item)")
				.mapChapters(reversed = true) { i, a ->
					val href = a.attrAsRelativeUrl("href").replace("%20", " ")
					val li = a.selectFirstOrThrow("li.chp-item")
					MangaChapter(
						id = generateUid(href),
						title = li.selectFirst("span.chp-title")?.textOrNull(),
						number = i + 1f,
						volume = 0,
						url = href,
						uploadDate = parseChapterDateByLang(li.selectFirst("span.chp-time")?.text().orEmpty()),
						source = source,
						scanlator = null,
						branch = null,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.body().select("div.chp-page-trigger[option_val]").map { option ->
			val url = option.attr("option_val")
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.body().selectFirstOrThrow("section.mangaread-img img.manga_pic")
			.attrAsAbsoluteUrl("src")
	}

	private var tagCache: ArrayMap<String, MangaTag>? = null
	private val mutex = Mutex()

	private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
		tagCache?.let { return@withLock it }
		val tagMap = ArrayMap<String, MangaTag>()
		val tagElements = webClient.httpGet("https://${domain}/search/?type=high").parseHtml().select("li.genre-item")
		for (el in tagElements) {
			if (el.text().isEmpty()) continue
			val a = el.selectFirstOrThrow("a")
			val cateId = a.attr("onclick").substringAfter("clickGenre(this, '").substringBefore("'")
			tagMap[el.text()] = MangaTag(
				title = a.text().toTitleCase(sourceLocale),
				key = cateId,
				source = source,
			)
		}
		tagCache = tagMap
		return@withLock tagMap
	}

	private fun parseStatus(status: String) = when {
		// en
		status.contains("Ongoing") -> MangaState.ONGOING
		status.contains("Completed") -> MangaState.FINISHED
		//es
		status.contains("En curso") -> MangaState.ONGOING
		status.contains("Completado") -> MangaState.FINISHED
		//ru
		status.contains("постоянный") -> MangaState.ONGOING
		status.contains("завершенный") -> MangaState.FINISHED
		//de
		status.contains("Laufende") -> MangaState.ONGOING
		status.contains("Abgeschlossen") -> MangaState.FINISHED
		//pt
		status.contains("Completo") -> MangaState.ONGOING
		status.contains("Em tradução") -> MangaState.FINISHED
		//it
		status.contains("In corso") -> MangaState.ONGOING
		status.contains("Completato") -> MangaState.FINISHED
		//fr
		status.contains("En cours") -> MangaState.ONGOING
		status.contains("Complété") -> MangaState.FINISHED
		else -> null
	}

	private fun parseChapterDateByLang(date: String): Long {
		val dateWords = date.split(" ")

		if (dateWords.size == 3) {
			if (dateWords[1].contains(",")) {
				return SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH).parseSafe(date)
			} else {
				val timeAgo = Integer.parseInt(dateWords[0])
				return Calendar.getInstance().apply {
					when (dateWords[1]) {
						"minutes" -> Calendar.MINUTE // EN-FR
						"hours" -> Calendar.HOUR // EN

						"minutos" -> Calendar.MINUTE // ES
						"horas" -> Calendar.HOUR

						// "minutos" -> Calendar.MINUTE // BR
						"hora" -> Calendar.HOUR

						"минут" -> Calendar.MINUTE // RU
						"часа" -> Calendar.HOUR

						"Stunden" -> Calendar.HOUR // DE

						"minuti" -> Calendar.MINUTE // IT
						"ore" -> Calendar.HOUR

						"heures" -> Calendar.HOUR // FR ("minutes" also French word)
						else -> null
					}?.let {
						add(it, -timeAgo)
					}
				}.timeInMillis
			}
		}
		return 0L
	}

	@MangaSourceParser("NINEMANGA_RU", "NineManga Русский", "ru")
	class Russian(context: MangaLoaderContext) : NineMangaParser(
		context,
		MangaParserSource.NINEMANGA_RU,
		"ru.niadd.com",
	)

}
