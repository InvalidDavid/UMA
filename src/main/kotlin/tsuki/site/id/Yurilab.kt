package tsuki.site.id

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MadaraParser

import tsuki.model.*
import tsuki.util.*

import kotlinx.coroutines.async
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.*

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

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> = kotlinx.coroutines.coroutineScope {
        val allChapters = mutableListOf<MangaChapter>()
        var t = 1
        val batchSize = 5

        while (true) {
            val deferreds = (t until t + batchSize).map { page ->
                async {
                    try {
                        val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix('/') + "/ajax/chapters/?t=$page"
                        val ajaxDocs = webClient.httpPost(
                            ajaxUrl.toHttpUrl(),
                            emptyMap<String, String>(),
                            Headers.Builder().add("X-Requested-With", "XMLHttpRequest").build(),
                        ).parseHtml()

                        val lis = ajaxDocs.select(selectChapter)
                        lis.mapNotNull { li ->
                            val a = li.selectFirst("a") ?: return@mapNotNull null
                            val rawHref = a.attrAsRelativeUrl("href")
                            
                            val baseName = a.ownText().ifEmpty { null } ?: a.selectFirst("p")?.textOrNull()
                                ?: "Chapter"
                            
                            val isLocked = rawHref.isBlank() || rawHref == "#" || 
                                    li.hasClass("premium") || li.hasClass("premium-block") || 
                                    li.selectFirst(".fa-lock, .vip-lock") != null
                            
                            val finalHref = if (rawHref.isBlank() || rawHref == "#") mangaUrl else rawHref
                            val finalName = if (isLocked && !baseName.contains("🔒")) "🔒 ${baseName.trim()}" else baseName.trim()

                            MangaChapter(
                                id = generateUid(finalHref + finalName),
                                url = finalHref,
                                title = finalName,
                                number = 0f,
                                volume = 0,
                                branch = null,
                                uploadDate = 0L,
                                scanlator = null,
                                source = source,
                            )
                        }
                    } catch (e: Exception) {
                        emptyList<MangaChapter>()
                    }
                }
            }

            val batches = deferreds.map { it.await() }
            for (pageChapters in batches) {
                if (pageChapters.isEmpty()) {
                    return@coroutineScope allChapters.reversed().mapIndexed { index, chapter ->
                        val currentTitle = chapter.title
                        val finalTitle = if (currentTitle == "Chapter" || currentTitle == "🔒 Chapter") {
                            currentTitle.replace("Chapter", "Chapter ${index + 1}")
                        } else {
                            currentTitle
                        }
                        chapter.copy(
                            title = finalTitle,
                            number = (index + 1).toFloat()
                        )
                    }
                }
                allChapters.addAll(pageChapters)
            }
            t += batchSize
        }
        return@coroutineScope emptyList()
    }
}
