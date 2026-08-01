package org.koitharu.kotatsu.parsers.ksp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceCatalogTest {

    @Test
    fun `catalog order is deterministic by source identifier`() {
        val sources = listOf(
            source(name = "ZETA", className = "example.ZetaParser"),
            source(name = "ALPHA", className = "example.AlphaParser"),
        )

        val catalog = validateSourceCatalog(sources)

        assertEquals(listOf("ALPHA", "ZETA"), catalog.map { it.name })
    }

    @Test
    fun `duplicate source identifiers are rejected with both declarations`() {
        val sources = listOf(
            source(name = "DUPLICATE", className = "example.FirstParser"),
            source(name = "DUPLICATE", className = "example.SecondParser"),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateSourceCatalog(sources)
        }

        assertTrue(exception.message.orEmpty().contains("DUPLICATE"))
        assertTrue(exception.message.orEmpty().contains("example.FirstParser"))
        assertTrue(exception.message.orEmpty().contains("example.SecondParser"))
    }

    @Test
    fun `invalid source identifier is rejected`() {
        val invalid = source(name = "bad-name", className = "example.InvalidParser")

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateSourceCatalog(listOf(invalid))
        }

        assertTrue(exception.message.orEmpty().contains("bad-name"))
        assertTrue(exception.message.orEmpty().contains("example.InvalidParser"))
    }

    @Test
    fun `invalid source locale is rejected`() {
        val invalid = source(
            name = "INVALID_LOCALE",
            className = "example.InvalidLocaleParser",
            locale = "not-a-locale",
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateSourceCatalog(listOf(invalid))
        }

        assertTrue(exception.message.orEmpty().contains("not-a-locale"))
        assertTrue(exception.message.orEmpty().contains("example.InvalidLocaleParser"))
    }

    @Test
    fun `parser constructor must require one MangaLoaderContext`() {
        val invalid = source(
            name = "INVALID_CONSTRUCTOR",
            className = "example.InvalidConstructorParser",
            requiredConstructorParameterTypes = listOf("java.lang.String"),
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            validateSourceCatalog(listOf(invalid))
        }

        assertTrue(exception.message.orEmpty().contains("tsuki.MangaLoaderContext"))
        assertTrue(exception.message.orEmpty().contains("example.InvalidConstructorParser"))
    }

    @Test
    fun `one descriptor renders matching enum and factory entries`() {
        val source = source(name = "ALPHA", className = "example.AlphaParser")

        assertEquals(
            "\tALPHA(\"Alpha\", \"en\", ContentType.MANGA, false),\n",
            source.renderEnumEntry(),
        )
        assertEquals(
            "\tMangaParserSource.ALPHA -> example.AlphaParser(context)\n",
            source.renderFactoryEntry(),
        )
    }

    @Test
    fun `catalog summary contains bundle identity count and locale distribution`() {
        val sources = listOf(
            source(name = "ALPHA", className = "example.AlphaParser", locale = "en"),
            source(name = "GLOBAL", className = "example.GlobalParser", locale = ""),
            source(name = "ZETA", className = "example.ZetaParser", locale = "en"),
        )

        val summary = renderCatalogSummary("en-ru", validateSourceCatalog(sources))

        assertEquals(
            """
            bundle: en-ru
            total: 3
            locales:
              all: 1
              en: 2

            """.trimIndent(),
            summary,
        )
    }

    @Test
    fun `invalid plugin identifier is rejected`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            renderCatalogSummary("EN RU", emptyList())
        }

        assertTrue(exception.message.orEmpty().contains("EN RU"))
    }

    private fun source(
        name: String,
        className: String,
        locale: String = "en",
        requiredConstructorParameterTypes: List<String> = listOf("tsuki.MangaLoaderContext"),
    ) = SourceDescriptor(
        name = name,
        title = name.lowercase().replaceFirstChar(Char::uppercase),
        locale = locale,
        typeExpression = "ContentType.MANGA",
        isBroken = false,
        className = className,
        requiredConstructorParameterTypes = requiredConstructorParameterTypes,
        deprecationReason = null,
    )
}
