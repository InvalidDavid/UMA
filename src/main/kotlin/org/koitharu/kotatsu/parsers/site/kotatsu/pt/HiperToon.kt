package org.koitharu.kotatsu.parsers.site.kotatsu.pt

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.parsers.HiperParser

@MangaSourceParser("HIPERTOON", "Hipertoon", "pt")
internal class HiperToon(context: MangaLoaderContext) :
    HiperParser(context, MangaParserSource.HIPERTOON, "hipertoon.com")