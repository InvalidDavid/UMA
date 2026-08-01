package tsuki.site.galleryadults.all

import org.jsoup.nodes.Element
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.model.ContentType
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag
import tsuki.site.galleryadults.GalleryAdultsParser
import tsuki.site.galleryadults.GalleryAdultsSelectors
import tsuki.site.galleryadults.GalleryAdultsSiteConfig
import tsuki.util.mapToSet
import tsuki.util.removeSuffix
import java.util.Locale

@MangaSourceParser("ASMHENTAI", "AsmHentai", type = ContentType.HENTAI)
internal class AsmHentai(context: MangaLoaderContext) :
    GalleryAdultsParser(
        context = context,
        source = MangaParserSource.ASMHENTAI,
        siteConfig = GalleryAdultsSiteConfig(
            domain = "asmhentai.com",
            popularTagsPath = "/tags/?page=",
            selectors = GalleryAdultsSelectors(
                gallery = ".preview_item",
                galleryLink = ".image a",
                galleryImage = ".image img",
                tagsRoot = ".tags_page ul.tags",
                detailsAuthor = "div.tags:contains(Artists:) .tag_list a span.tag",
                pageImage = "#fimg",
            ),
            availableLocales = setOf(
                Locale.ENGLISH,
                Locale.JAPANESE,
                Locale.CHINESE,
                Locale("tr"),
            ),
        ),
    ) {

    override fun Element.parseTags() = select("a").mapToSet {
        val key = it.attr("href").removeSuffix('/').substringAfterLast('/')
        val name = it.selectFirst(".tag")?.html()?.substringBefore("<") ?: it.html().substringBefore("<")
        MangaTag(
            key = key,
            title = name,
            source = source,
        )
    }
}
