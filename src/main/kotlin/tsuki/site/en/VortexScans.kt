package tsuki.site.en

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.IkenParser

import tsuki.model.MangaParserSource

@MangaSourceParser("VORTEXSCANS", "Vortex Scans", "en")
internal class VortexScans(context: MangaLoaderContext) :
    IkenParser(context, MangaParserSource.VORTEXSCANS, "vortexscans.org")
