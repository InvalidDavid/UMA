package tsuki.site.all.nsfw

import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.GalleryAdultsParser

import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.model.SortOrder

import tsuki.util.mapToSet
import tsuki.util.oneOrThrowIfMany
import tsuki.util.parseHtml
import tsuki.util.removeSuffix
import tsuki.util.selectFirstOrThrow
import tsuki.util.toAbsoluteUrl
import tsuki.util.urlEncoded

import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("HENTAIERA", "HentaiEra", type = ContentType.HENTAI)
internal class HentaiEra(context: MangaLoaderContext) :
    GalleryAdultsParser(context, MangaParserSource.HENTAIERA, "hentaiera.com", 25) {
    override val selectTags = ".tags_section"
    override val selectTag = ".galleries_info li:contains(Tags) div.info_tags"
    override val selectAuthor = ".galleries_info li:contains(Artists) span.item_name"
    override val selectLanguageChapter = ".galleries_info li:contains(Languages) div.info_tags .item_name"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = super.getFilterOptions().copy(
        availableLocales = setOf(
            Locale.ENGLISH,
            Locale.FRENCH,
            Locale.JAPANESE,
            Locale("es"),
            Locale("ru"),
            Locale("ko"),
            Locale.GERMAN,
        ),
    )

    override fun Element.parseTags() = select("a.tag, .gallery_title a").mapToSet {
        val key = it.attr("href").removeSuffix('/').substringAfterLast('/')
        val name = it.selectFirst(".item_name")?.text() ?: it.text()
        MangaTag(
            key = key,
            title = name,
            source = source,
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            when {
                !filter.query.isNullOrEmpty() -> {
                    append("/search/?key=")
                    append(filter.query.urlEncoded())
                    append("&")
                }
                else -> {
                    if (filter.tags.size > 1 || (filter.tags.isNotEmpty() && filter.locale != null)) {
                        append("/search/?key=")
                        append(buildQuery(filter.tags, filter.locale))

                        append("&")
                    } else if (filter.tags.isNotEmpty()) {
                        filter.tags.oneOrThrowIfMany()?.let {
                            append("/tag/")
                            append(it.key)
                        }
                        append("/")

                        append("?")
                    } else if (filter.locale != null) {
                        append("/language/")
                        append(filter.locale?.toLanguagePath())
                        append("/")

                        append("?")
                    } else {
                        append("/?")
                    }
                }
            }
            append("page=")
            append(page.toString())
        }

        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    private fun buildQuery(tags: Collection<MangaTag>, locale: Locale?): String {
        val queryDefault =
            "&search=&mg=1&dj=1&ws=1&is=1&ac=1&gc=1&en=0&jp=0&es=0&fr=0&kr=0&de=0&ru=0&lt=1&dl=0&pp=0&tr=0"
        val tag = tags.joinToString(" ", postfix = " ") { it.key }
        val queryMod = when (val lp = locale?.toLanguagePath()) {
            "english" -> queryDefault.replace("en=0", "en=1")
            "japanese" -> queryDefault.replace("jp=0", "jp=1")
            "spanish" -> queryDefault.replace("es=0", "es=1")
            "french" -> queryDefault.replace("fr=0", "fr=1")
            "korean" -> queryDefault.replace("kr=0", "kr=1")
            "russian" -> queryDefault.replace("ru=0", "ru=1")
            "german" -> queryDefault.replace("de=0", "de=1")
            null -> "&search=&mg=1&dj=1&ws=1&is=1&ac=1&gc=1&en=1&jp=1&es=1&fr=1&kr=1&de=1&ru=1&lt=1&dl=0&pp=0&tr=0"
            else -> throw IllegalArgumentException("Unsupported locale: $lp")
        }
        return tag + queryMod
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val urlChapters = doc.selectFirstOrThrow("#cover a, .cover a, .left_cover a").attr("href")
        val tag = doc.selectFirst(selectTag)?.parseTags()
        val branch = doc.select(selectLanguageChapter).joinToString(separator = " / ") {
            it.text()
        }
        val author = doc.selectFirst(selectAuthor)?.text()
        return manga.copy(
            tags = tag.orEmpty(),
            authors = setOfNotNull(author),
            chapters = listOf(
                MangaChapter(
                    id = manga.id,
                    title = manga.title,
                    number = 1f,
                    volume = 0,
                    url = urlChapters,
                    scanlator = null,
                    uploadDate = 0,
                    branch = branch,
                    source = source,
                ),
            ),
        )
    }
}
