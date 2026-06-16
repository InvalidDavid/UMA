package org.koitharu.kotatsu.parsers.site.kotatsu.vi.yurigarden

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.kotatsu.vi.YuriGardenParser

@MangaSourceParser("YURIGARDEN", "Yuri Garden", "vi")
internal class YuriGarden(context: MangaLoaderContext) :
	YuriGardenParser(context, MangaParserSource.YURIGARDEN, "yurigarden.com", false)
