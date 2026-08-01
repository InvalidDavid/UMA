package tsuki

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tsuki.model.MangaParserSource

class MangaSourceCatalogTest {

    @Test
    fun `expanded catalog has the approved size and locale distribution`() {
        val sources = MangaParserSource.entries

        assertEquals(90, sources.size)
        assertEquals(
            mapOf("" to 11, "en" to 51, "ru" to 27, "zh" to 1),
            sources.groupingBy { it.locale }.eachCount(),
        )
    }

    @Test
    fun `every catalog entry constructs its parser`() {
        val context = FixtureMangaLoaderContext.builder().build()

        MangaParserSource.entries.forEach { source ->
            try {
                source.newParser(context)
            } catch (error: Exception) {
                throw AssertionError("Cannot construct parser for ${source.name}", error)
            }
        }
    }

    @Test
    fun `en-ru catalog excludes Vietnamese sources`() {
        val vietnameseSources = MangaParserSource.entries.filter { it.locale == "vi" }

        assertTrue(vietnameseSources.isEmpty()) {
            "Vietnamese sources remain in en-ru catalog: " +
                vietnameseSources.joinToString { it.name }
        }
    }

    @Test
    fun `catalog exposes approved Manga Plus and Manga Ball locales`() {
        val expected = setOf(
            "MANGABALL_EN",
            "MANGABALL_RU",
            "MANGAPLUSPARSER_EN",
            "MANGAPLUSPARSER_RU",
        )
        val actual = MangaParserSource.entries
            .mapTo(mutableSetOf()) { it.name }
            .intersect(expected)

        assertEquals(expected, actual)
    }

    @Test
    fun `catalog exposes approved English Madara family`() {
        val expected = setOf(
            "ALLPORNCOMIC",
            "AQUAMANGA",
            "COFFEEMANGA",
            "HENTAIXCOMIC",
            "MADARADEX",
            "MANGADISTRICT",
            "MANGAGG",
            "MANGAREAD",
            "MANHUAUS",
            "MANHWA18CC",
            "MANHWANEX",
            "MANHWATOP",
            "MANHWAZ",
            "NOVELCROW",
            "TOPMANHUA",
            "TWENTYFOURHNOVEL",
            "ZINMANGA",
        )
        val actual = MangaParserSource.entries
            .mapTo(mutableSetOf()) { it.name }
            .intersect(expected)

        assertEquals(expected, actual)
    }

    @Test
    fun `catalog exposes approved reusable English parser families`() {
        val expected = setOf(
            "ENTHUNDERSCANS",
            "KALISCAN",
            "KINGOFSHOJO",
            "LIKEMANGA",
            "MANGA18",
            "MANGABAT",
            "MANGAJINX",
            "MANGANATOGG",
            "OMEGASCANS",
        )
        val actual = MangaParserSource.entries
            .mapTo(mutableSetOf()) { it.name }
            .intersect(expected)

        assertEquals(expected, actual)
    }

    @Test
    fun `catalog exposes approved independent English sources`() {
        val expected = setOf(
            "ASURASCANS",
            "ATSUMARU",
            "BATCAVE",
            "COMICLAND",
            "DEMONICSCANS",
            "FLAMECOMICS",
            "HEYTOON",
            "MANGADOTNET",
            "MANGAFIRE_EN",
            "MANGAFOX",
            "MANGAGO",
            "MANGAK",
            "MANGAKATANA",
            "MANGAKEKO",
            "MANGATAROORG",
            "MANGATOWN",
            "MANHWA18",
            "MANHWA210",
            "MGREADIO",
            "WEBTOONS_EN",
            "WEEBCENTRAL",
        )
        val actual = MangaParserSource.entries
            .mapTo(mutableSetOf()) { it.name }
            .intersect(expected)

        assertEquals(expected, actual)
    }

    @Test
    fun `catalog exposes active Russian LibSocial sources`() {
        val expected = setOf("MANGALIB", "YAOILIB")
        val actual = MangaParserSource.entries
            .mapTo(mutableSetOf()) { it.name }
            .intersect(expected)

        assertEquals(expected, actual)
    }

    @Test
    fun `catalog exposes active Russian Grouple sources`() {
        val expected = setOf(
            "ALLHENTAI",
            "MINTMANGA",
            "READMANGA_RU",
            "SEIMANGA",
            "SELFMANGA",
            "USAGI",
        )
        val actual = MangaParserSource.entries
            .mapTo(mutableSetOf()) { it.name }
            .intersect(expected)

        assertEquals(expected, actual)
    }

    @Test
    fun `catalog exposes active Russian Chan sources`() {
        val expected = setOf("HENCHAN", "MANGACHAN", "YAOICHAN")
        val actual = MangaParserSource.entries
            .mapTo(mutableSetOf()) { it.name }
            .intersect(expected)

        assertEquals(expected, actual)
    }

    @Test
    fun `catalog exposes active Russian Madara sources`() {
        val expected = setOf("MANGAMAMMY", "MANGAONELOVE", "MANGAZAVR")
        val actual = MangaParserSource.entries
            .mapTo(mutableSetOf()) { it.name }
            .intersect(expected)

        assertEquals(expected, actual)
    }

    @Test
    fun `catalog exposes active independent Russian sources`() {
        val expected = setOf(
            "COMX",
            "DESUME",
            "MANGA_WTF",
            "NINEMANGA_RU",
            "NUDEMOON",
            "REMANGA",
            "WAMANGA",
        )
        val actual = MangaParserSource.entries
            .mapTo(mutableSetOf()) { it.name }
            .intersect(expected)

        assertEquals(expected, actual)
    }

    @Test
    fun `catalog preserves upstream broken status for unavailable Russian sources`() {
        val expected = setOf(
            "ACOMICS",
            "ALLHENTAI",
            "BEST_MANGA",
            "HENTAILIB",
            "MINTMANGA",
            "ZENMANGA",
        )
        val actual = MangaParserSource.entries
            .filter { it.name in expected && it.isBroken }
            .mapTo(mutableSetOf()) { it.name }

        assertEquals(expected, actual)
    }
}
