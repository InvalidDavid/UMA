package tsuki.site.id.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.ContentType
import tsuki.model.Manga
import tsuki.model.MangaChapter
import tsuki.model.MangaListFilterCapabilities
import tsuki.model.MangaParserSource
import tsuki.model.MangaTag

import tsuki.util.attrAsRelativeUrl
import tsuki.util.attrOrNull
import tsuki.util.generateUid
import tsuki.util.mapNotNullToSet
import tsuki.util.parseHtml
import tsuki.util.textOrNull
import tsuki.util.toAbsoluteUrl
import tsuki.util.toTitleCase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@MangaSourceParser("YURILAB", "YuriLab", "id", ContentType.HENTAI)
internal class YuriLab(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.YURILAB, "yurilab.top", pageSize = 30) {

    override val sourceLocale: Locale = Locale.ENGLISH
    override val withoutAjax = true

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(isMultipleTagsSupported = false)

    override fun parseMangaList(doc: Document): List<Manga> {
        return super.parseMangaList(doc).map { manga ->
            manga.copy(coverUrl = manga.coverUrl?.replace(Regex("""-\d+x\d+(?=\.\w+$)"""), ""))
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val author = doc.selectFirst(".author-content a, .manga-author a")?.textOrNull()
        return super.getDetails(manga).copy(
            authors = setOfNotNull(author).ifEmpty { manga.authors }
        )
    }

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val url = "https://$domain/?s=&post_type=wp-manga"
        val docs = webClient.httpGet(url).parseHtml()
        val genreLinks = docs.select(".genres-filter .dropdown-menu a[href*='genre=']")
        return genreLinks.mapNotNullToSet { el ->
            val href = el.attrOrNull("href") ?: return@mapNotNullToSet null
            val match = Regex("""genre=([^&]+)""").find(href)
            val key = match?.groupValues?.get(1) ?: return@mapNotNullToSet null
            val title = el.textOrNull()?.trim()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null
            MangaTag(
                title = title,
                key = key,
                source = source,
            )
        }
    }

    override val selectGenre = ".genres-content a[href*='genre'], .tags-content a[href*='tag']"

    override suspend fun createMangaTag(a: Element): MangaTag? {
        val href = a.attrOrNull("href") ?: return null
        val tagKey = extractTagKey(href) ?: return null
        val title = a.textOrNull()?.trim() ?: return null
        return MangaTag(
            title = title,
            key = tagKey,
            source = source,
        )
    }

    private fun extractTagKey(href: String): String? {
        val genreMatch = Regex("""genre=([^&/?]+)""").find(href)
        if (genreMatch != null) return genreMatch.groupValues[1]
        val pattern = Regex("""series-genre/([^/?]+)|series-tag/([^/?]+)""", RegexOption.IGNORE_CASE)
        return pattern.find(href)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: pattern.find(href)?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }
    }

    override val selectChapter = "ul.version-chap li.wp-manga-chapter"

    override fun transformChapterName(element: Element, name: String): String {
        return if (element.hasClass("premium") || element.hasClass("premium-block")) {
            "🔒 ${name.trim()}"
        } else {
            name.trim()
        }
    }

    private fun String?.applyChapterNumber(index: Int): String {
        val base = this ?: "Chapter"
        return if (base == "Chapter" || base == "🔒 Chapter") {
            base.replace("Chapter", "Chapter ${index + 1}")
        } else {
            base
        }
    }

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> = coroutineScope {
        val allChapters = mutableListOf<MangaChapter>()
        var page = 1
        val batchSize = 5
        val dateFormat = SimpleDateFormat("d MMMM yyyy", sourceLocale)

        while (true) {
            val deferreds = (page until page + batchSize).map { currentPage ->
                async {
                    try {
                        val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/?t=$currentPage"
                        val ajaxDocs = webClient.httpPost(
                            ajaxUrl.toHttpUrl(),
                            emptyMap(),
                            Headers.Builder().add("X-Requested-With", "XMLHttpRequest").build(),
                        ).parseHtml()

                        val lis = ajaxDocs.select(selectChapter)
                        lis.mapNotNull { li ->
                            val a = li.selectFirst("a") ?: return@mapNotNull null
                            val rawHref = a.attrAsRelativeUrl("href")

                            val baseName = a.ownText().ifEmpty { null } ?: a.selectFirst("p")?.textOrNull()
                            ?: "Chapter"

                            val finalName = transformChapterName(li, baseName)

                            var dateText = li.selectFirst("a.c-new-tag")?.attr("title") ?: li.selectFirst(selectDate)?.text()
                            if (dateText != null && !dateText.contains("ago", true) && !dateText.contains(Regex("""\d{4}"""))) {
                                val year = Calendar.getInstance().get(Calendar.YEAR)
                                dateText = "$dateText $year"
                            }

                            MangaChapter(
                                id = generateUid(rawHref + finalName),
                                url = rawHref,
                                title = finalName,
                                number = 0f,
                                volume = 0,
                                branch = null,
                                uploadDate = parseChapterDate(dateFormat, dateText),
                                scanlator = null,
                                source = source,
                            )
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }

            val batches = deferreds.awaitAll()
            var emptyBatch = false
            for (pageChapters in batches) {
                if (pageChapters.isEmpty()) {
                    emptyBatch = true
                    break
                }
                allChapters.addAll(pageChapters)
            }

            if (emptyBatch) {
                break
            }
            page += batchSize
        }
        allChapters.reversed().mapIndexed { index, chapter ->
            chapter.copy(
                title = chapter.title.applyChapterNumber(index),
                number = (index + 1).toFloat()
            )
        }
    }
}
