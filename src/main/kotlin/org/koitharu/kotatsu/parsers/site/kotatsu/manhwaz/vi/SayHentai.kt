package org.koitharu.kotatsu.parsers.site.kotatsu.manhwaz.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.kotatsu.manhwaz.ManhwaZ

@MangaSourceParser("SAYHENTAI", "SayHentai", "vi", ContentType.HENTAI)
internal class SayHentai(context: MangaLoaderContext) :
	ManhwaZ(context, MangaParserSource.SAYHENTAI, "sayhentai.sh")
