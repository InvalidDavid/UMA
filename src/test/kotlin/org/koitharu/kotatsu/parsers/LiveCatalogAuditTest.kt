package org.koitharu.kotatsu.parsers

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.seconds

class LiveCatalogAuditTest {

    @Test
    fun `audit configured live catalog`() = runBlocking {
        assumeTrue(System.getenv(ENV_ENABLED).toBoolean(), "$ENV_ENABLED is not enabled")

        val targetStage = AuditStage.valueOf(
            System.getenv(ENV_STAGE)?.uppercase() ?: AuditStage.DETAILS.name,
        )
        val locales = System.getenv(ENV_LOCALES)
            ?.split(',')
            ?.mapTo(linkedSetOf()) { it.trim().lowercase() }
            ?.takeIf { it.isNotEmpty() }
        val includeBroken = System.getenv(ENV_INCLUDE_BROKEN)?.toBooleanStrictOrNull() ?: true
        val concurrency = System.getenv(ENV_CONCURRENCY)?.toIntOrNull()?.coerceIn(1, 12) ?: 4
        val timeout = (System.getenv(ENV_TIMEOUT_SECONDS)?.toLongOrNull()?.coerceIn(10, 600) ?: 120).seconds
        val semaphore = Semaphore(concurrency)
        val sources = MangaParserSource.entries.filter { source ->
            (includeBroken || !source.isBroken) && (locales == null || source.locale.lowercase() in locales)
        }

        val results = coroutineScope {
            sources.map { source ->
                async {
                    semaphore.withPermit {
                        val startedAt = System.nanoTime()
                        val result = try {
                            withTimeout(timeout) {
                                auditSource(source, targetStage)
                            }
                        } catch (_: TimeoutCancellationException) {
                            result(
                                source = source,
                                status = AuditStatus.TIMEOUT,
                                stage = targetStage,
                                startedAt = startedAt,
                                details = "Source audit exceeded ${timeout.inWholeSeconds} seconds",
                            )
                        }
                        println(result.consoleLine())
                        result
                    }
                }
            }.awaitAll()
        }.sortedBy { it.source.name }

        val reportScope = locales?.joinToString("-")?.ifEmpty { "all" } ?: "all"
        val reportPath = writeReport(results, targetStage, reportScope)
        printSummary(results, reportPath)

        if (System.getenv(ENV_FAIL_ON_ERROR).toBoolean()) {
            val failures = results.filter { it.status.isFailure }
            check(failures.isEmpty()) {
                "${failures.size} live sources failed; see $reportPath"
            }
        }
    }

    private suspend fun auditSource(source: MangaParserSource, targetStage: AuditStage): AuditResult {
        val startedAt = System.nanoTime()
        var currentStage = AuditStage.LIST
        return try {
            val context = LiveMangaLoaderContext()
            val parser = context.newParserInstance(source)
            val order = preferredSortOrder(parser)
            val list = parser.getList(0, order, MangaListFilter.EMPTY)
            check(list.isNotEmpty()) { "Source returned an empty first page" }
            val manga = list.first()
            if (targetStage == AuditStage.LIST) {
                return passed(source, targetStage, startedAt, "items=${list.size}; title=${manga.title}")
            }

            currentStage = AuditStage.DETAILS
            var details = parser.getDetails(manga)
            if (targetStage == AuditStage.DETAILS) {
                return passed(
                    source,
                    targetStage,
                    startedAt,
                    "items=${list.size}; title=${details.title}; chapters=${details.chapters.orEmpty().size}",
                )
            }

            if (details.chapters.isNullOrEmpty()) {
                for (candidate in list.drop(1).take(MAX_CHAPTER_CANDIDATES - 1)) {
                    val candidateDetails = parser.getDetails(candidate)
                    if (!candidateDetails.chapters.isNullOrEmpty()) {
                        details = candidateDetails
                        break
                    }
                }
            }

            currentStage = AuditStage.CHAPTERS
            val chapter = details.chapters.orEmpty().firstOrNull()
                ?: return result(
                    source,
                    AuditStatus.NO_CHAPTERS,
                    currentStage,
                    startedAt,
                    "title=${details.title}",
                )
            if (targetStage == AuditStage.CHAPTERS) {
                return passed(source, targetStage, startedAt, "title=${details.title}; chapter=${chapter.url}")
            }

            currentStage = AuditStage.PAGES
            val pages = parser.getPages(chapter)
            check(pages.isNotEmpty()) { "Chapter returned no pages" }
            val pageUrl = parser.getPageUrl(pages.first())
            passed(source, targetStage, startedAt, "pages=${pages.size}; first=$pageUrl")
        } catch (error: Throwable) {
            result(
                source = source,
                status = classify(source, error),
                stage = currentStage,
                startedAt = startedAt,
                details = "${error::class.simpleName}: ${error.message.orEmpty()}".singleLine(),
            )
        }
    }

    private fun preferredSortOrder(parser: MangaParser): SortOrder = listOf(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.RATING,
    ).firstOrNull { it in parser.availableSortOrders }
        ?: parser.availableSortOrders.first()

    private fun classify(source: MangaParserSource, error: Throwable): AuditStatus {
        if (source.isBroken) return AuditStatus.EXPECTED_BROKEN
        val message = generateSequence(error) { it.cause }
            .joinToString(" | ") { "${it::class.simpleName}: ${it.message.orEmpty()}" }
        val normalizedMessage = message.lowercase()
        return when {
            "requires an Android consumer runtime" in message ||
                "Browser is not available" in message -> AuditStatus.ANDROID_RUNTIME_REQUIRED
            "AuthRequiredException" in message -> AuditStatus.AUTH_REQUIRED
            "SocketTimeoutException" in message || "TimeoutCancellationException" in message -> AuditStatus.TIMEOUT
            "source returned an empty first page" in normalizedMessage -> AuditStatus.EMPTY_LIST
            "ssl" in normalizedMessage || "certificate" in normalizedMessage -> AuditStatus.TLS_ERROR
            "HttpStatusException" in message || "NotFoundException" in message || "status=" in normalizedMessage ->
                AuditStatus.HTTP_ERROR
            "ParseException" in message || "JSONException" in message || "parse" in normalizedMessage ->
                AuditStatus.PARSE_ERROR
            "HttpException" in message || "UnknownHostException" in message || "StreamResetException" in message ->
                AuditStatus.NETWORK_ERROR
            else -> AuditStatus.ERROR
        }
    }

    private fun passed(
        source: MangaParserSource,
        stage: AuditStage,
        startedAt: Long,
        details: String,
    ): AuditResult = result(
        source = source,
        status = if (source.isBroken) AuditStatus.BROKEN_MARKER_STALE else AuditStatus.PASSED,
        stage = stage,
        startedAt = startedAt,
        details = details,
    )

    private fun result(
        source: MangaParserSource,
        status: AuditStatus,
        stage: AuditStage,
        startedAt: Long,
        details: String,
    ) = AuditResult(
        source = source,
        status = status,
        stage = stage,
        durationMs = (System.nanoTime() - startedAt) / 1_000_000,
        details = details.singleLine(),
    )

    private fun writeReport(results: List<AuditResult>, targetStage: AuditStage, reportScope: String): Path {
        val report = Path.of(
            "build/reports/live-source-audit-${targetStage.name.lowercase()}-$reportScope.tsv",
        )
        Files.createDirectories(report.parent)
        val rows = buildString {
            appendLine("source\ttitle\tlocale\tbroken\ttarget\tstatus\tstage\tduration_ms\tdetails")
            results.forEach { result ->
                appendLine(
                    listOf(
                        result.source.name,
                        result.source.title,
                        result.source.locale.ifEmpty { "all" },
                        result.source.isBroken,
                        targetStage,
                        result.status,
                        result.stage,
                        result.durationMs,
                        result.details,
                    ).joinToString("\t"),
                )
            }
        }
        Files.writeString(
            report,
            rows,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return report
    }

    private fun printSummary(results: List<AuditResult>, reportPath: Path) {
        val counts = results.groupingBy { it.status }.eachCount().toSortedMap(compareBy { it.name })
        println("[LIVE-AUDIT] report=$reportPath")
        println("[LIVE-AUDIT] total=${results.size}; ${counts.entries.joinToString { "${it.key}=${it.value}" }}")
    }

    private fun String.singleLine(): String = replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    private data class AuditResult(
        val source: MangaParserSource,
        val status: AuditStatus,
        val stage: AuditStage,
        val durationMs: Long,
        val details: String,
    ) {
        fun consoleLine(): String =
            "[LIVE-AUDIT] ${source.name}\t$status\t$stage\t${durationMs}ms\t$details"
    }

    private enum class AuditStage {
        LIST,
        DETAILS,
        CHAPTERS,
        PAGES,
    }

    private enum class AuditStatus(val isFailure: Boolean) {
        PASSED(false),
        EXPECTED_BROKEN(false),
        BROKEN_MARKER_STALE(true),
        NO_CHAPTERS(false),
        ANDROID_RUNTIME_REQUIRED(false),
        AUTH_REQUIRED(false),
        TIMEOUT(true),
        EMPTY_LIST(true),
        HTTP_ERROR(true),
        TLS_ERROR(true),
        PARSE_ERROR(true),
        NETWORK_ERROR(true),
        ERROR(true),
    }

    private companion object {
        const val ENV_ENABLED = "LIVE_AUDIT"
        const val ENV_STAGE = "LIVE_AUDIT_STAGE"
        const val ENV_LOCALES = "LIVE_AUDIT_LOCALES"
        const val ENV_INCLUDE_BROKEN = "LIVE_AUDIT_INCLUDE_BROKEN"
        const val ENV_CONCURRENCY = "LIVE_AUDIT_CONCURRENCY"
        const val ENV_TIMEOUT_SECONDS = "LIVE_AUDIT_TIMEOUT_SECONDS"
        const val ENV_FAIL_ON_ERROR = "LIVE_AUDIT_FAIL_ON_ERROR"
        const val MAX_CHAPTER_CANDIDATES = 5
    }
}
