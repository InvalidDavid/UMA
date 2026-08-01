package org.koitharu.kotatsu.parsers.ksp

import java.util.Locale

internal data class SourceDescriptor(
    val name: String,
    val title: String,
    val locale: String,
    val typeExpression: String,
    val isBroken: Boolean,
    val className: String,
    val requiredConstructorParameterTypes: List<String>,
    val deprecationReason: String?,
) {
    fun renderEnumEntry(): String {
        val deprecation = deprecationReason
            ?.let { "@Deprecated(\"${it.escapeKotlinString()}\") " }
            .orEmpty()
        return "\t$deprecation$name(\"${title.escapeKotlinString()}\", " +
            "\"${locale.escapeKotlinString()}\", $typeExpression, $isBroken),\n"
    }

    fun renderFactoryEntry(): String =
        "\tMangaParserSource.$name -> $className(context)\n"
}

internal fun validateSourceCatalog(sources: Iterable<SourceDescriptor>): List<SourceDescriptor> {
    val sourceList = sources.toList()
    sourceList.forEach { source ->
        require(SOURCE_NAME_PATTERN.matches(source.name)) {
            "Invalid source identifier ${source.name} on ${source.className}"
        }
        require(source.locale.isEmpty() || source.locale.isSupportedLocaleTag()) {
            "Invalid source locale ${source.locale} on ${source.className}"
        }
        require(source.requiredConstructorParameterTypes == listOf(MANGA_LOADER_CONTEXT_TYPE)) {
            "Parser ${source.className} must require exactly one $MANGA_LOADER_CONTEXT_TYPE constructor parameter"
        }
    }
    val duplicate = sourceList.groupBy(SourceDescriptor::name)
        .entries
        .firstOrNull { it.value.size > 1 }
    require(duplicate == null) {
        val declarations = duplicate!!.value.joinToString { it.className }
        "Duplicate source identifier ${duplicate.key}: $declarations"
    }
    return sourceList.sortedBy(SourceDescriptor::name)
}

internal fun renderCatalogSummary(
    pluginId: String,
    catalog: List<SourceDescriptor>,
): String {
    require(PLUGIN_ID_PATTERN.matches(pluginId)) { "Invalid plugin identifier: $pluginId" }
    return buildString {
        appendLine("bundle: $pluginId")
        appendLine("total: ${catalog.size}")
        appendLine("locales:")
        catalog.groupingBy { it.locale.ifEmpty { "all" } }
            .eachCount()
            .toSortedMap()
            .forEach { (locale, count) ->
                appendLine("  $locale: $count")
            }
    }
}

private val SOURCE_NAME_PATTERN = Regex("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*")
private val PLUGIN_ID_PATTERN = Regex("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
private val ISO_LANGUAGES = Locale.getISOLanguages().toSet()
private const val MANGA_LOADER_CONTEXT_TYPE = "tsuki.MangaLoaderContext"

private fun String.isSupportedLocaleTag(): Boolean {
    val locale = Locale.forLanguageTag(this)
    return locale.language in ISO_LANGUAGES
}

private fun String.escapeKotlinString(): String = buildString(length) {
    this@escapeKotlinString.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}
