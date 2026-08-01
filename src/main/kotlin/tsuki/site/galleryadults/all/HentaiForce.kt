package tsuki.site.galleryadults.all

import org.jsoup.internal.StringUtil
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.*
import tsuki.site.galleryadults.GalleryAdultsParser
import tsuki.site.galleryadults.GalleryAdultsSelectors
import tsuki.site.galleryadults.GalleryAdultsSiteConfig
import tsuki.util.*
import java.util.*

@MangaSourceParser("HENTAIFORCE", "HentaiForce", type = ContentType.HENTAI)
internal class HentaiForce(context: MangaLoaderContext) :
    GalleryAdultsParser(
        context = context,
        source = MangaParserSource.HENTAIFORCE,
        siteConfig = GalleryAdultsSiteConfig(
            domain = "hentaiforce.net",
            pageSize = 30,
            popularTagsPath = "/tags/popular/",
            selectors = GalleryAdultsSelectors(
                gallery = ".gallery",
                galleryLink = "a.gallery-thumb",
                tagsRoot = ".tag-listing",
                detailsTags = "div.tag-container:contains(Tags:)",
                detailsAuthor = "div.tag-container:contains(Artists:) a",
                detailsLanguage = "div.tag-container:contains(Languages:) a",
                detailsChapterUrl = "#gallery-main-cover a",
                pageImage = ".gallery-reader-img-wrapper img",
            ),
            availableLocales = setOf(
                Locale.ENGLISH,
                Locale.FRENCH,
                Locale.JAPANESE,
                Locale.CHINESE,
                Locale("es"),
                Locale("ru"),
                Locale("ko"),
                Locale.GERMAN,
                Locale("id"),
                Locale.ITALIAN,
                Locale("pt"),
                Locale("th"),
                Locale("vi"),
            ),
            availableSortOrders = setOf(SortOrder.UPDATED, SortOrder.POPULARITY),
            supportsMultipleTags = true,
        ),
    ) {

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !filter.query.isNullOrEmpty() -> {
                    append("/search?q=")
                    append(filter.query.urlEncoded())
                    append("&page=")
                }

                else -> {
                    if (filter.tags.size > 1 || (filter.tags.isNotEmpty() && filter.locale != null)) {
                        append("/search?q=")
                        append(buildQuery(filter.tags, filter.locale))
                        if (order == SortOrder.POPULARITY) {
                            append("&sort=popular")
                        }
                        append("&page=")
                    } else if (filter.tags.isNotEmpty()) {
                        filter.tags.oneOrThrowIfMany()?.let {
                            append("/tag/")
                            append(it.key)
                        }
                        append("/")

                        if (order == SortOrder.POPULARITY) {
                            append("popular/")
                        }
                        append("?")
                    } else if (filter.locale != null) {
                        val locale = filter.locale
                        append("/language/")
                        append(locale?.toLanguagePath())
                        append("/")

                        if (order == SortOrder.POPULARITY) {
                            append("popular/")
                        }
                        append("?")
                    } else {
                        append("/page/")
                    }
                }
            }
            append(page.toString())
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    private fun buildQuery(tags: Collection<MangaTag>, language: Locale?): String {
        val joiner = StringUtil.StringJoiner(" ")
        tags.forEach { tag ->
            joiner.add("tag:\"")
            joiner.append(tag.key)
            joiner.append("\"")
        }
        language?.let { lc ->
            joiner.add("language:\"")
            joiner.append(lc.toLanguagePath())
            joiner.append("\"")
        }
        return joiner.complete()
    }
}
