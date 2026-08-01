package tsuki.site.galleryadults.all

import tsuki.ErrorMessages
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.*
import tsuki.site.galleryadults.GalleryAdultsParser
import tsuki.site.galleryadults.GalleryAdultsSelectors
import tsuki.site.galleryadults.GalleryAdultsSiteConfig
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.urlEncoded
import java.util.*

@MangaSourceParser("HENTAIENVY", "HentaiEnvy", type = ContentType.HENTAI)
internal class HentaiEnvy(context: MangaLoaderContext) :
    GalleryAdultsParser(
        context = context,
        source = MangaParserSource.HENTAIENVY,
        siteConfig = GalleryAdultsSiteConfig(
            domain = "hentaienvy.com",
            pageSize = 24,
            selectors = GalleryAdultsSelectors(
                galleryLink = "a",
                galleryTitle = "div.title",
                tagsRoot = ".tags_items",
                detailsTags = ".gt_right_tags ul:contains(Tags:)",
                detailsAuthor = ".gt_right_tags ul:contains(Artists:) a",
                detailsLanguage = ".gt_right_tags ul:contains(Languages:) a",
                pageImage = "#fimg",
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
                Locale("pt"),
            ),
            availableSortOrders = setOf(SortOrder.UPDATED, SortOrder.POPULARITY),
        ),
    ) {

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !filter.query.isNullOrEmpty() -> {
                    append("/search/?s_key=")
                    append(filter.query.urlEncoded())
                    append("&")
                }

                else -> {
                    if (filter.tags.isNotEmpty()) {
                        if (filter.locale != null) {
                            throw IllegalArgumentException(ErrorMessages.FILTER_BOTH_LOCALE_GENRES_NOT_SUPPORTED)
                        }
                        filter.tags.oneOrThrowIfMany()?.let {
                            append("/tag/")
                            append(it.key)
                            if (order == SortOrder.POPULARITY) {
                                append("/popular")
                            }
                            append("/?")
                        }
                    } else if (filter.locale != null) {
                        val locale = filter.locale
                        append("/language/")
                        append(locale?.toLanguagePath())
                        append("/?")
                    } else {
                        append("/?")
                    }
                }
            }
            append("page=")
            append(page)

        }
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }
}
