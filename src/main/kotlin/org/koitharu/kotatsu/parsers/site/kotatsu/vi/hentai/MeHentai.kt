package org.koitharu.kotatsu.parsers.site.kotatsu.vi.hentai

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.ManhwaZ

@MangaSourceParser("MEHENTAI", "MeHentai", "vi", ContentType.HENTAI)
internal class MeHentai(context: MangaLoaderContext) :
    ManhwaZ(context, MangaParserSource.MEHENTAI, "mehentai.blog") {

    override val searchPath = "tim-kiem"
    override val tagPath = "the-loai"
}
