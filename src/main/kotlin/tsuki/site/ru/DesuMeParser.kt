package tsuki.site.ru

import okhttp3.Headers
import okhttp3.HttpUrl
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.core.PagedMangaParser
import tsuki.model.ContentRating
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
import tsuki.network.UserAgents
import tsuki.util.LinkResolver
import tsuki.util.attrAsRelativeUrl
import tsuki.util.generateUid
import tsuki.util.parseHtml
import tsuki.util.selectFirstOrThrow
import tsuki.util.toAbsoluteUrl
import tsuki.util.toTitleCase
import java.util.EnumSet

@MangaSourceParser("DESUME", "Desu", "ru")
internal class DesuMeParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DESUME, 20) {

	override val configKeyDomain = ConfigKey.Domain("desu.uno")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchCatalogDocument().parseDesuTags(),
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", UserAgents.KOTATSU)
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim()?.takeIf { it.isNotEmpty() }
		if (query != null) {
			if (page != searchPaginator.firstPage) return emptyList()
			val document = webClient.httpPost(
				"https://$domain/manga/search/",
				mapOf("q" to query),
			).parseHtml()
			return document.parseDesuSearchResults().map(::parseSearchManga)
		}

		val url = buildCatalogUrl(page, order, filter)
		return webClient.httpGet(url)
			.parseHtml()
			.parseDesuCatalogItems()
			.map(::parseCatalogManga)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val root = webClient.httpGet(manga.url.toAbsoluteUrl(domain))
			.parseHtml()
			.selectFirstOrThrow("#animeView")
		val title = root.selectFirstOrThrow("meta[itemprop=headline]").attr("content")
		val alternativeTitles = buildSet {
			root.selectFirst("meta[itemprop=alternativeHeadline]")
				?.attr("content")
				?.takeIf { it.isNotBlank() }
				?.let(::add)
			root.selectFirst(".alternativeHeadline")
				?.text()
				?.split(',')
				?.map(String::trim)
				?.filterTo(this) { it.isNotEmpty() }
		}
		val chapters = root.select("ul.chlist > li").map { item ->
			val link = item.selectFirstOrThrow("h4 > a")
			val chapterUrl = link.attrAsRelativeUrl("href")
			val match = DESU_CHAPTER_PATH.find(chapterUrl)
				?: error("Unsupported Desu chapter URL: $chapterUrl")
			MangaChapter(
				id = generateUid(chapterUrl),
				url = chapterUrl,
				source = source,
				number = match.groupValues[2].toFloat(),
				volume = match.groupValues[1].toInt(),
				title = link.selectFirst(".title")?.text()?.removePrefix("- ")?.takeIf { it.isNotBlank() },
				scanlator = null,
				uploadDate = 0L,
				branch = null,
			)
		}

		return manga.copy(
			title = title,
			altTitles = alternativeTitles,
			publicUrl = root.selectFirstOrThrow("link[itemprop=url]").attr("href"),
			largeCoverUrl = root.selectFirstOrThrow("img[itemprop=image]").attr("src"),
			description = root.selectFirst("#description .russian")?.html(),
			tags = root.select("a[itemprop=genre]").mapTo(linkedSetOf()) { link ->
				MangaTag(
					key = link.attr("href").substringAfter("genres="),
					title = link.text().removePrefix("# ").toTitleCase(),
					source = source,
				)
			},
			state = root.selectFirst(".b-anime_status_tag")?.classNames()?.toDesuState(),
			rating = root.selectFirst("meta[itemprop=ratingValue]")
				?.attr("content")
				?.toFloatOrNull()
				?.div(10f)
				?: RATING_UNKNOWN,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val html = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml().html()
		val directory = DESU_READER_DIRECTORY.find(html)?.groupValues?.get(1)
			?: error("Cannot find Desu reader directory")
		val imagesJson = DESU_READER_IMAGES.find(html)?.groupValues?.get(1)
			?: error("Cannot find Desu reader images")
		val images = JSONArray(imagesJson)

		return (0 until images.length()).map { index ->
			val fileName = images.getJSONArray(index).getString(0)
			val imageUrl = "https:${directory}${fileName}"
			MangaPage(
				id = generateUid(imageUrl),
				preview = null,
				source = chapter.source,
				url = imageUrl,
			)
		}
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val document = webClient.httpGet(link).parseHtml()
		val root = document.selectFirst("#animeView") ?: return null
		val title = root.selectFirst("meta[itemprop=headline]")?.attr("content") ?: return null
		val url = root.selectFirst("link[itemprop=url]")?.attr("href") ?: return null
		return resolver.resolveManga(this, id = generateUid(url), url = url, title = title)
	}

	private suspend fun fetchCatalogDocument(): Document = webClient
		.httpGet("https://$domain/manga/")
		.parseHtml()

	private fun buildCatalogUrl(page: Int, order: SortOrder, filter: MangaListFilter): String = buildString {
		append("https://")
		append(domain)
		append("/manga/?page=")
		append(page)
		val sortKey = when (order) {
			SortOrder.UPDATED -> "updated"
			SortOrder.POPULARITY -> "popular"
			SortOrder.NEWEST -> "id"
			SortOrder.ALPHABETICAL -> "name"
			else -> error("Unsupported Desu sort order: $order")
		}
		if (sortKey != "updated") {
			append("&order_by=")
			append(sortKey)
		}
		if (filter.tags.isNotEmpty()) {
			append("&genres=")
			append(filter.tags.joinToString(",") { it.key })
		}
	}

	private fun parseCatalogManga(item: Element): Manga {
		val link = item.selectFirstOrThrow("a.animeTitle")
		val url = link.attrAsRelativeUrl("href")
		val cover = item.selectFirstOrThrow("a.avatar .img")
			.attr("style")
			.let { style -> DESU_BACKGROUND_IMAGE.find(style)?.groupValues?.get(1) }
			?: error("Cannot find Desu catalog cover")
		val russianTitle = item.selectFirstOrThrow(".dimmed.oTitle [itemprop=title]").text()
		val originalTitle = link.text()
		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			source = source,
			title = russianTitle,
			altTitles = setOf(originalTitle),
			coverUrl = cover,
			largeCoverUrl = null,
			state = null,
			rating = item.parseDesuCatalogRating(),
			contentRating = null,
			tags = emptySet(),
			authors = emptySet(),
			description = null,
		)
	}

	private fun parseSearchManga(item: Element): Manga {
		val url = item.attrAsRelativeUrl("href")
		val russianTitle = item.selectFirstOrThrow(".itemSubTitle").text()
		val originalTitle = item.selectFirstOrThrow(".itemTitle").text()
		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			source = source,
			title = russianTitle,
			altTitles = setOf(originalTitle),
			coverUrl = item.selectFirstOrThrow("img").attr("src"),
			largeCoverUrl = null,
			state = null,
			rating = RATING_UNKNOWN,
			contentRating = null,
			tags = emptySet(),
			authors = emptySet(),
			description = null,
		)
	}
}

internal fun Document.parseDesuCatalogItems(): List<Element> =
	select("ol.memberList > li.memberListItem")

internal fun Document.parseDesuSearchResults(): List<Element> =
	select("ol.section > li > a[href^=manga/]")

internal fun Document.parseDesuTags(): Set<MangaTag> =
	select("#animeFilter input[data-genre-id][data-genre-slug][data-genre-name]").mapTo(linkedSetOf()) { input ->
		MangaTag(
			key = "${input.attr("data-genre-id")}-${input.attr("data-genre-slug")}",
			title = input.attr("data-genre-name").toTitleCase(),
			source = MangaParserSource.DESUME,
		)
	}

private fun Element.parseDesuCatalogRating(): Float {
	val rating = select(".animeInfo dl").asSequence()
		.flatMap { it.children().asSequence() }
		.windowed(2)
		.firstOrNull { it[0].tagName() == "dt" && it[0].text() == "Рейтинг:" && it[1].tagName() == "dd" }
		?.get(1)
		?.text()
		?.toFloatOrNull()
	return rating?.div(10f) ?: RATING_UNKNOWN
}

private fun Set<String>.toDesuState(): MangaState? = when {
	"ongoing" in this -> MangaState.ONGOING
	"released" in this -> MangaState.FINISHED
	"anons" in this -> MangaState.UPCOMING
	isEmpty() -> null
	else -> error("Unsupported Desu status classes: $this")
}

private val DESU_BACKGROUND_IMAGE = Regex("url\\(['\"]?([^'\")]+)")
private val DESU_CHAPTER_PATH = Regex("/vol(\\d+)/ch([0-9.]+)/")
private val DESU_READER_DIRECTORY = Regex("dir:\\s*\"([^\"]+/)\"")
private val DESU_READER_IMAGES = Regex("images:\\s*(\\[\\[.*?]])\\s*,\\s*page:", RegexOption.DOT_MATCHES_ALL)
