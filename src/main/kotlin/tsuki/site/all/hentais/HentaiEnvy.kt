package tsuki.site.all.hentais

import tsuki.ErrorMessages
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.GalleryAdultsParser

import tsuki.model.MangaParserSource
import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaListFilter
import tsuki.model.SortOrder

import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.urlEncoded

import java.util.EnumSet
import java.util.Locale


@MangaSourceParser("HENTAIENVY", "HentaiEnvy", type = ContentType.HENTAI)
internal class HentaiEnvy(context: MangaLoaderContext) :
    GalleryAdultsParser(context, MangaParserSource.HENTAIENVY, "hentaienvy.com", pageSize = 24) {
    override val selectGalleryLink = "a"
    override val selectGalleryTitle = "div.title"
    override val selectTags = ".tags_items"
    override val selectTag = ".gt_right_tags ul:contains(Tags:)"
    override val selectAuthor = ".gt_right_tags ul:contains(Artists:) a"
    override val selectLanguageChapter = ".gt_right_tags ul:contains(Languages:) a"
    override val idImg = "fimg"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY)

    override suspend fun getFilterOptions() = super.getFilterOptions().copy(
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
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !filter.query.isNullOrEmpty() -> {
                    val query = filter.query
                    append("/search/?s_key=")
                    append(query?.urlEncoded())
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