package org.koitharu.kotatsu.parsers.site.kotatsu.vi.hentai

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.ManhwaZ

// idk how to fix image cover url
// they are using /cache-image/404
// cant rly scrape them over html

@MangaSourceParser("MEHENTAI", "MeHentai", "vi", ContentType.HENTAI)
internal class MeHentai(context: MangaLoaderContext) :
    ManhwaZ(context, MangaParserSource.MEHENTAI, "mehentai.blog") {

    override val searchPath = "tim-kiem"
    override val tagPath = "the-loai"
}
