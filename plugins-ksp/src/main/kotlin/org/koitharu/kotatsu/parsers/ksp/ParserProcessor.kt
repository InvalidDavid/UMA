package org.koitharu.kotatsu.parsers.ksp

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import java.io.File
import java.io.Writer
import java.nio.file.FileAlreadyExistsException

class ParserProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("tsuki.MangaSourceParser").toList()
        val deferred = symbols.filterNot(KSAnnotated::validate)
        if (symbols.isEmpty() || generated || deferred.isNotEmpty()) {
            return deferred
        }

        val descriptors = symbols.mapNotNull { symbol ->
            val declaration = symbol as? KSClassDeclaration
            if (declaration == null) {
                logger.error("Only classes can be annotated with @MangaSourceParser", symbol)
                null
            } else {
                declaration.toSourceDescriptor()
            }
        }
        val catalog = try {
            validateSourceCatalog(descriptors)
        } catch (exception: IllegalArgumentException) {
            logger.error(exception.message ?: "Invalid manga source catalog")
            return deferred
        }
        warnAboutDuplicateTitles(catalog)

        val dependencies = Dependencies.ALL_FILES
        val factoryFile = createGeneratedFile(dependencies, "tsuki", "MangaParserFactory")
        val sourcesFile = createGeneratedFile(dependencies, "tsuki.model", "MangaSource")
        sourcesFile?.writer().use { sourcesWriter ->
            factoryFile?.writer().use { factoryWriter ->
                writeContent(sourcesWriter, factoryWriter, catalog)
            }
        }
        writeSummary(catalog)
        generated = true
        return deferred
    }

    private fun createGeneratedFile(
        dependencies: Dependencies,
        packageName: String,
        fileName: String,
    ) = try {
        codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = packageName,
            fileName = fileName,
        )
    } catch (exception: FileAlreadyExistsException) {
        logger.warn(exception.toString(), null)
        null
    }

    private fun KSClassDeclaration.toSourceDescriptor(): SourceDescriptor? {
        if (classKind != ClassKind.CLASS || isAbstract()) {
            logger.error("Only non-abstract classes can be annotated with @MangaSourceParser", this)
            return null
        }
        val annotation = annotations.single { it.shortName.asString() == "MangaSourceParser" }
        val deprecation = annotations.singleOrNull { it.shortName.asString() == "Deprecated" }
        val constructorParameterTypes = primaryConstructor
            ?.parameters
            ?.filterNot { it.hasDefault }
            ?.map { parameter ->
                val type = parameter.type.resolve()
                type.declaration.qualifiedName?.asString() ?: type.toString()
            }
            .orEmpty()
        return SourceDescriptor(
            name = annotation.argument("name") as String,
            title = annotation.argument("title") as String,
            locale = annotation.argument("locale") as String,
            typeExpression = annotation.argument("type").toString(),
            isBroken = annotations.any { it.shortName.asString() == "Broken" },
            className = checkNotNull(qualifiedName?.asString()) { "Class name is null" },
            requiredConstructorParameterTypes = constructorParameterTypes,
            deprecationReason = deprecation
                ?.arguments
                ?.find { it.name?.asString() == "message" }
                ?.value
                ?.toString(),
        )
    }

    private fun com.google.devtools.ksp.symbol.KSAnnotation.argument(name: String): Any =
        requireNotNull(arguments.single { it.name?.asString() == name }.value) {
            "Missing @MangaSourceParser argument: $name"
        }

    private fun warnAboutDuplicateTitles(catalog: List<SourceDescriptor>) {
        catalog.groupBy(SourceDescriptor::title)
            .filterValues { it.size > 1 }
            .forEach { (title, descriptors) ->
                logger.warn(
                    "Source title duplication: \"$title\" is assigned to " +
                        descriptors.joinToString { it.className },
                )
            }
    }

    private fun writeContent(
        sourcesWriter: Writer?,
        factoryWriter: Writer?,
        catalog: List<SourceDescriptor>,
    ): Int {
        if (sourcesWriter == null && factoryWriter == null) {
            return 0
        }
        factoryWriter?.write(
            """
            package tsuki

            import tsuki.model.MangaParserSource
            import tsuki.core.MangaParserWrapper

            internal fun MangaParserSource.newParser(context: MangaLoaderContext): MangaParser = when (this) {

            """.trimIndent(),
        )
        sourcesWriter?.write(
            """
            package tsuki.model

            public enum class MangaParserSource(
                override val title: String,
                override val locale: String,
                override val contentType: ContentType,
                override val isBroken: Boolean,
            ): MangaSource {

            """.trimIndent(),
        )
        catalog.forEach { source ->
            factoryWriter?.write(source.renderFactoryEntry())
            sourcesWriter?.write(source.renderEnumEntry())
        }
        factoryWriter?.write(
            $$"""
            }.let {
                require(it.source == this) {
                    "Cannot instantiate manga parser: $name mapped to ${it.source}"
                }
                MangaParserWrapper(it)
            }
            """.trimIndent(),
        )
        sourcesWriter?.write(
            """
                ;
            }
            """.trimIndent(),
        )
        return catalog.size
    }

    private fun writeSummary(catalog: List<SourceDescriptor>) {
        val file = File(options["summaryOutputDir"] ?: return, "summary.yaml")
        val pluginId = requireNotNull(options["pluginId"]) { "Missing KSP option: pluginId" }
        file.writeText(renderCatalogSummary(pluginId, catalog))
    }
}
