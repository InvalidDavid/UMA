package org.koitharu.kotatsu.parsers.site.kotatsu.mangabox.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.kotatsu.mangabox.NatoParser

@MangaSourceParser("MANGANATO", "Manganto", "en", ContentType.MANGA)
internal class Manganato(context: MangaLoaderContext) :
    NatoParser(context, MangaParserSource.MANGANATO, "www.natomanga.com")
