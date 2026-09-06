package tsuki.site.id.nsfw

import tsuki.MangaLoaderContext
import tsuki.MangaSourceParser
import tsuki.parsers.MangaThemesia

import tsuki.model.ContentType
import tsuki.model.MangaParserSource

import java.text.SimpleDateFormat
import java.util.Locale

@MangaSourceParser("SASANGEYOUNET", "Sasangeyou.net", "id", ContentType.HENTAI)
internal class Sasangeyou(context: MangaLoaderContext) :
    MangaThemesia(context, MangaParserSource.SASANGEYOUNET, "sasangeyou.net") {
    override val dateFormat = SimpleDateFormat("MM/dd/yyyy", Locale("id"))
}
