package tsuki.site.ru

import tsuki.parsers.LibSocialParser
import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.config.ConfigKey
import tsuki.exception.AuthRequiredException
import tsuki.exception.NotFoundException

import tsuki.model.MangaChapter
import tsuki.model.MangaPage
import tsuki.model.MangaParserSource

@MangaSourceParser("MANGALIB", "MangaLib", "ru")
internal class MangaLib(context: MangaLoaderContext, ) :
    LibSocialParser(context, MangaParserSource.MANGALIB, 1, "mangalib.org") {

    override val configKeyDomain = ConfigKey.Domain("mangalib.org", "mangalib.me")

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = try {
        super.getPages(chapter)
    } catch (e: NotFoundException) {
        throw AuthRequiredException(source, e)
    }
}
