package org.koitharu.kotatsu.parsers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaParserSource

class RussianSourceCatalogTest {

    @Test
    fun `catalog exposes Russian NineManga successor`() {
        assertTrue(
            MangaParserSource.entries.any {
                it.name == "NINEMANGA_RU" && it.locale == "ru"
            },
            "NINEMANGA_RU must be present in the generated Russian catalog",
        )
    }

    @Test
    fun `NineManga Russian parser uses the live successor domain`() {
        val parser = MangaParserSource.NINEMANGA_RU.newParser(FixtureMangaLoaderContext.builder().build())

        assertEquals("ru.niadd.com", parser.domain)
    }

    @Test
    fun `catalog exposes Russian Madara sources`() {
        val expectedSources = setOf(
            "BEST_MANGA",
            "MANGAMAMMY",
            "MANGAONELOVE",
            "MANGAZAVR",
        )

        val actualSources = MangaParserSource.entries
            .filter { it.locale == "ru" }
            .mapTo(mutableSetOf()) { it.name }

        assertTrue(
            actualSources.containsAll(expectedSources),
            "Missing Russian Madara sources: ${expectedSources - actualSources}",
        )
    }

    @Test
    fun `catalog exposes Russian Chan sources`() {
        val expectedSources = setOf("HENCHAN", "MANGACHAN", "YAOICHAN")
        val actualSources = MangaParserSource.entries
            .filter { it.locale == "ru" }
            .mapTo(mutableSetOf()) { it.name }

        assertTrue(
            actualSources.containsAll(expectedSources),
            "Missing Russian Chan sources: ${expectedSources - actualSources}",
        )
    }

    @Test
    fun `catalog exposes Russian Grouple sources`() {
        val expectedSources = setOf(
            "ALLHENTAI",
            "MINTMANGA",
            "READMANGA_RU",
            "SEIMANGA",
            "SELFMANGA",
            "USAGI",
        )
        val actualSources = MangaParserSource.entries
            .filter { it.locale == "ru" }
            .mapTo(mutableSetOf()) { it.name }

        assertTrue(
            actualSources.containsAll(expectedSources),
            "Missing Russian Grouple sources: ${expectedSources - actualSources}",
        )
    }

    @Test
    fun `catalog exposes Russian LibSocial sources`() {
        val expectedSources = setOf("HENTAILIB", "MANGALIB", "YAOILIB")
        val actualSources = MangaParserSource.entries
            .filter { it.locale == "ru" }
            .mapTo(mutableSetOf()) { it.name }

        assertTrue(
            actualSources.containsAll(expectedSources),
            "Missing Russian LibSocial sources: ${expectedSources - actualSources}",
        )
    }

    @Test
    fun `catalog exposes independent Russian sources`() {
        val expectedSources = setOf(
            "ACOMICS",
            "COMX",
            "DESUME",
            "MANGA_WTF",
            "NUDEMOON",
            "REMANGA",
            "WAMANGA",
            "ZENMANGA",
        )
        val actualSources = MangaParserSource.entries
            .filter { it.locale == "ru" }
            .mapTo(mutableSetOf()) { it.name }

        assertTrue(
            actualSources.containsAll(expectedSources),
            "Missing independent Russian sources: ${expectedSources - actualSources}",
        )
    }

    @Test
    fun `catalog exposes the complete Russian source set`() {
        val expectedSources = setOf(
            "ACOMICS",
            "ALLHENTAI",
            "BEST_MANGA",
            "COMX",
            "DESUME",
            "HENCHAN",
            "HENTAILIB",
            "MANGABALL_RU",
            "MANGACHAN",
            "MANGALIB",
            "MANGAMAMMY",
            "MANGAONELOVE",
            "MANGAPLUSPARSER_RU",
            "MANGAZAVR",
            "MANGA_WTF",
            "MINTMANGA",
            "NINEMANGA_RU",
            "NUDEMOON",
            "READMANGA_RU",
            "REMANGA",
            "SEIMANGA",
            "SELFMANGA",
            "USAGI",
            "WAMANGA",
            "YAOICHAN",
            "YAOILIB",
            "ZENMANGA",
        )
        val actualSources = MangaParserSource.entries
            .filter { it.locale == "ru" }
            .mapTo(mutableSetOf()) { it.name }

        assertEquals(expectedSources, actualSources)
    }

    @Test
    fun `only currently unavailable Russian sources remain marked broken`() {
        val brokenSources = MangaParserSource.entries
            .filter { it.locale == "ru" && it.isBroken }
            .mapTo(mutableSetOf()) { it.name }

        assertEquals(setOf("BEST_MANGA", "MANGAZAVR"), brokenSources)
    }
}
