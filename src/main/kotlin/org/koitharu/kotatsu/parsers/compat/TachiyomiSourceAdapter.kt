@file:Suppress("warnings", "unused")

package org.koitharu.kotatsu.parsers.compat

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import uy.kohesive.injekt.Injekt

/**
 * Adapter that wraps a Tachiyomi [HttpSource] and exposes it as a parser via [PagedMangaParser].
 * Enables Tachiyomi extension sources to be used within Usagi and Kotatsu ecosystem (if possible).
 */
@OptIn(InternalParsersApi::class)
open class TachiyomiSourceAdapter(
    context: MangaLoaderContext,
    source: MangaParserSource,
    protected val tachiyomiSource: HttpSource,
) : PagedMangaParser(context, source, DEFAULT_PAGE_SIZE) {

    /** Disable login button, it's useless */
    override val authorizationProvider: MangaParserAuthProvider? = null

    /** Default for source settings. */
    protected open val splitByTranslationsKey = ConfigKey.SplitByTranslations(false)
    protected open val showSuspiciousContentKey = ConfigKey.ShowSuspiciousContent(false)
    protected open val preferredImageServerKey = ConfigKey.PreferredImageServer(emptyMap(), null)

    init {
        /** Register Application context so Injekt.get<Application>() works. */
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            val app = currentApplicationMethod.invoke(null) as? Application
            if (app != null) {
                Injekt.addSingleton<Application>(app)
            } else {
                Injekt.addSingleton<Application>(Application())
            }
            Injekt.addSingleton<MangaLoaderContext>(context)
        }.onFailure {
            Injekt.addSingleton<Application>(Application())
            Injekt.addSingleton<MangaLoaderContext>(context)
        }

        /** Setup intercepted client for domain and User-Agent swapping */
        val originalDomain = runCatching { java.net.URI(tachiyomiSource.baseUrl).host }.getOrNull()
        val interceptedClient = context.httpClient.newBuilder()
            .addInterceptor { chain ->
                var request = chain.request()
                val builder = request.newBuilder()
                val customUa = config[userAgentKey]
                if (originalDomain != null && request.url.host == originalDomain) {
                    val newUrl = request.url.newBuilder()
                        .host(domain)
                        .build()
                    builder.url(newUrl)
                }
                if (customUa.isNotBlank()) {
                    builder.header("User-Agent", customUa)
                }
                chain.proceed(builder.build())
            }
            .build()

        runCatching {
            tachiyomiSource.javaClass.getMethod("setClient", okhttp3.OkHttpClient::class.java)
                .invoke(tachiyomiSource, interceptedClient)
        }
        
        NetworkHelper.setClient(interceptedClient)
    }

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val configKeyDomain: ConfigKey.Domain by lazy {
        val host = runCatching {
            java.net.URI(tachiyomiSource.baseUrl).host
                ?: tachiyomiSource.baseUrl.toHostOnly()
        }.getOrDefault(tachiyomiSource.baseUrl.toHostOnly())
        ConfigKey.Domain(host)
    }

    // ========================== Sort / Order ===============================
    override val availableSortOrders: Set<SortOrder> = buildSet {
        add(SortOrder.UPDATED)
        add(SortOrder.POPULARITY)
        if (tachiyomiSource.supportsLatest) {
            add(SortOrder.NEWEST)
        }
    }

    /**
     * Lazily computed: true if the source's filter list contains any Filter.Text entry,
     * which typically means author/artist/year text inputs are supported.
     */
    private val hasTextFilter: Boolean by lazy {
        tachiyomiSource.getFilterList().any { it is eu.kanade.tachiyomi.source.model.Filter.Text }
    }

    /** Mapping filters, allow to search + use all filters through tags */
    @InternalParsersApi
    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isAuthorSearchSupported = hasTextFilter,
        )


    // ============================== List ===================================

    open override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val query = filter.query

        val mangasPage: MangasPage = withContext(Dispatchers.IO) {
            when {
                !query.isNullOrEmpty() || filter.hasNonSearchOptions() -> {
                    val tachiyomiFilters = tachiyomiSource.getFilterList()

                    // --- Map Kotatsu tag filters → Tachiyomi CheckBox / Group<CheckBox> ---
                    filter.tags.forEach { tag ->
                        tachiyomiFilters.forEach { f ->
                            if (f is eu.kanade.tachiyomi.source.model.Filter.CheckBox && f.name == tag.title) {
                                f.state = true
                            } else if (f is eu.kanade.tachiyomi.source.model.Filter.Group<*>) {
                                f.state.forEach { sub ->
                                    if (sub is eu.kanade.tachiyomi.source.model.Filter.CheckBox && sub.name == tag.title) {
                                        sub.state = true
                                    }
                                }
                            } else if (f is eu.kanade.tachiyomi.source.model.Filter.Select<*> && !isStatusFilterSelect(f)) {
                                // For generic Select filters, select the matching option by name
                                val idx = f.values.indexOfFirst { it.toString() == tag.title }
                                if (idx >= 0) f.state = idx
                            }
                        }
                    }

                    // --- Map Kotatsu state filters → Tachiyomi StatusFilter (Group or Select) ---
                    val states = filter.states
                    if (states.isNotEmpty()) {
                        tachiyomiFilters.forEach { f ->
                            when {
                                f is eu.kanade.tachiyomi.source.model.Filter.Group<*> && isStatusFilterGroup(f) -> {
                                    f.state.forEach { sub ->
                                        if (sub is eu.kanade.tachiyomi.source.model.Filter.CheckBox) {
                                            val mappedState = mapNameToState(sub.name)
                                            if (mappedState != null && mappedState in states) {
                                                sub.state = true
                                            }
                                        }
                                    }
                                }
                                f is eu.kanade.tachiyomi.source.model.Filter.Select<*> && isStatusFilterSelect(f) -> {
                                    // Pick the first matching option for single-select status
                                    val values = f.values
                                    for (i in 1 until values.size) {
                                        val mappedState = mapNameToState(values[i].toString())
                                        if (mappedState != null && mappedState in states) {
                                            f.state = i
                                            break
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }

                    tachiyomiSource.fetchSearchManga(page, query ?: "", tachiyomiFilters)
                        .toBlocking().single()
                }
                order == SortOrder.NEWEST && tachiyomiSource.supportsLatest -> {
                    tachiyomiSource.fetchLatestUpdates(page).toBlocking().single()
                }
                else -> {
                    tachiyomiSource.fetchPopularManga(page).toBlocking().single()
                }
            }
        }

        return mangasPage.mangas.map { it.toManga() }
    }

    // ============================== Details ================================

    open override suspend fun getDetails(manga: Manga): Manga {
        val sManga = manga.toSManga()

        return withContext(Dispatchers.IO) {
            val details = tachiyomiSource.fetchMangaDetails(sManga).toBlocking().single()
            val chapters = tachiyomiSource.fetchChapterList(sManga).toBlocking().single()

            /** Auto-detect chapter ordering: Tachiyomi default is newest-first,
            but some sources return oldest-first. Detect by comparing chapter_number
            of first two entries. If not available, fall back to date comparison. */
            val isNewestFirst = detectNewestFirst(chapters)

            manga.copy(
                title = details.title.ifEmpty { manga.title },
                altTitles = emptySet(),
                coverUrl = details.thumbnail_url ?: manga.coverUrl,
                largeCoverUrl = details.thumbnail_url,
                description = details.description,
                authors = setOfNotNull(details.author, details.artist)
                    .filter { it.isNotBlank() }.toSet(),
                tags = parseGenreTags(details.genre),
                state = mapStatus(details.status),
                chapters = chapters.mapChapters { i, sChapter ->
                    sChapter.toMangaChapter(i, chapters.size, isNewestFirst)
                }.sortedBy { it.number },
            )
        }
    }

    /**
     * Detects whether [chapters] are ordered newest-first (index 0 = latest).
     * Priority:
     * 1. chapter_number: if first > second → newest-first
     * 2. date_upload: if first > second → newest-first
     * 3. Default: true (Tachiyomi convention)
     * It helps rearrange the chapter list in asc order, avoiding confusion.
     */
    private fun detectNewestFirst(chapters: List<SChapter>): Boolean {
        if (chapters.size < 2) return true
        val a = chapters[0]
        val b = chapters[1]
        if (a.chapter_number > 0f && b.chapter_number > 0f) {
            return a.chapter_number > b.chapter_number
        }
        if (a.date_upload > 0L && b.date_upload > 0L) {
            return a.date_upload > b.date_upload
        }
        return true
    }

    // ============================== Pages ==================================

    open override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val sChapter = SChapter.create().apply {
            url = chapter.url
            name = chapter.title ?: ""
            chapter_number = chapter.number
        }

        val pages = withContext(Dispatchers.IO) {
            tachiyomiSource.fetchPageList(sChapter).toBlocking().single()
        }

        return pages.map { page ->
            val imgUrl = page.imageUrl ?: page.url
            MangaPage(
                id = generateUid(imgUrl.ifEmpty { "${chapter.url}#${page.index}" }),
                url = imgUrl,
                preview = null,
                source = source,
            )
        }
    }

    open override suspend fun getPageUrl(page: MangaPage): String {
        val url = page.url
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return "https://$domain$url"
    }

    // ============================== Filter Options =========================

    open override suspend fun getFilterOptions(): MangaListFilterOptions = withContext(Dispatchers.IO) {
        val tachiyomiFilters = tachiyomiSource.getFilterList()
        val tags = mutableSetOf<MangaTag>()
        val states = mutableSetOf<MangaState>()

        tachiyomiFilters.forEach { filter ->
            when (filter) {
                is eu.kanade.tachiyomi.source.model.Filter.CheckBox -> {
                    tags.add(MangaTag(title = filter.name, key = filter.name.lowercase(), source = source))
                }

                // --- Group (multi-checkbox list, e.g. GenreList or StatusFilter) ---
                is eu.kanade.tachiyomi.source.model.Filter.Group<*> -> {
                    if (isStatusFilterGroup(filter)) {
                        // Map each child checkbox to a MangaState
                        filter.state.forEach { sub ->
                            if (sub is eu.kanade.tachiyomi.source.model.Filter.CheckBox) {
                                mapNameToState(sub.name)?.let { states.add(it) }
                            }
                        }
                    } else {
                        // Generic group → flatten children as tags
                        filter.state.forEach { sub ->
                            when (sub) {
                                is eu.kanade.tachiyomi.source.model.Filter.CheckBox -> {
                                    tags.add(
                                        MangaTag(
                                            title = sub.name,
                                            key = sub.name.lowercase(),
                                            source = source,
                                        ),
                                    )
                                }

                                else -> {} // ignore nested selects / text inside groups
                            }
                        }
                    }
                }

                // --- Select (single-choice dropdown, e.g. GenreFilter / StatusFilter in fastscan) ---
                is eu.kanade.tachiyomi.source.model.Filter.Select<*> -> {
                    if (isStatusFilterSelect(filter)) {
                        // Map each option to MangaState (skip index 0 which is usually "All")
                        val values = filter.values
                        for (i in 1 until values.size) {
                            mapNameToState(values[i].toString())?.let { states.add(it) }
                        }
                    } else {
                        // Generic select → extract non-"All" options as tags
                        val values = filter.values
                        for (i in 1 until values.size) {
                            val name = values[i].toString()
                            if (name.isNotBlank()) {
                                tags.add(MangaTag(title = name, key = name.lowercase(), source = source))
                            }
                        }
                    }
                }

                // Filter.Text / Header / Separator — intentionally ignored for tag/state population
                else -> {}
            }
        }

        MangaListFilterOptions(availableTags = tags, availableStates = states)
    }

    /**
     * Heuristic: returns true when a [eu.kanade.tachiyomi.source.model.Filter.Group] represents a status/state selector.
     * Matches group names containing words like "status", "tình trạng", "trang thai", etc.
     */
    private fun isStatusFilterGroup(filter: eu.kanade.tachiyomi.source.model.Filter.Group<*>): Boolean {
        val name = filter.name.lowercase()
        return STATUS_KEYWORDS.any { name.contains(it) }
    }

    /**
     * Heuristic: returns true when a [eu.kanade.tachiyomi.source.model.Filter.Select] represents a status/state selector.
     */
    private fun isStatusFilterSelect(filter: eu.kanade.tachiyomi.source.model.Filter.Select<*>): Boolean {
        val name = filter.name.lowercase()
        return STATUS_KEYWORDS.any { name.contains(it) }
    }

    /**
     * Maps common status label strings (in multiple languages) to a [MangaState].
     * Returns null if no mapping is found.
     */
    protected open fun mapNameToState(name: String): MangaState? {
        val lower = name.lowercase()
        return when {
            ONGOING_KEYWORDS.any { lower.contains(it) } -> MangaState.ONGOING
            FINISHED_KEYWORDS.any { lower.contains(it) } -> MangaState.FINISHED
            PAUSED_KEYWORDS.any { lower.contains(it) } -> MangaState.PAUSED
            ABANDONED_KEYWORDS.any { lower.contains(it) } -> MangaState.ABANDONED
            else -> null
        }
    }

    // ============================== Headers ================================

    open override fun getRequestHeaders(): Headers = tachiyomiSource.headers

    // ============================== Interceptor ============================

    open override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())

    // ============================== Private Helpers ========================

    protected open fun SManga.toManga(): Manga {
        val mangaUrl = this.url
        val publicUrl = if (mangaUrl.startsWith("http")) mangaUrl
        else "${tachiyomiSource.baseUrl}$mangaUrl"

        return Manga(
            id = generateUid(mangaUrl),
            title = this.title,
            altTitles = emptySet(),
            url = mangaUrl,
            publicUrl = publicUrl,
            rating = RATING_UNKNOWN,
            contentRating = null,
            coverUrl = this.thumbnail_url,
            tags = parseGenreTags(this.genre),
            state = mapStatus(this.status),
            authors = setOfNotNull(this.author, this.artist)
                .filter { it.isNotBlank() }.toSet(),
            source = source,
        )
    }

    protected open fun Manga.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.url
        title = this@toSManga.title
        thumbnail_url = this@toSManga.coverUrl
    }

    protected open fun SChapter.toMangaChapter(index: Int, total: Int, isNewestFirst: Boolean = true): MangaChapter {
        // When chapter_number is set by the source, use it directly.
        // Otherwise derive from position: newest-first → total-index, oldest-first → index+1
        val number = if (this.chapter_number > 0f) {
            this.chapter_number
        } else if (isNewestFirst) {
            (total - index).toFloat()   // index 0 = newest = highest number
        } else {
            (index + 1).toFloat()       // index 0 = oldest = chapter 1
        }
        return MangaChapter(
            id = generateUid(this.url),
            title = this.name.ifBlank { null },
            number = number,
            volume = 0,
            url = this.url,
            scanlator = this.scanlator,
            uploadDate = this.date_upload,
            branch = null,
            source = source,
        )
    }

    protected open fun parseGenreTags(genreStr: String?): Set<MangaTag> {
        if (genreStr.isNullOrBlank()) return emptySet()
        return genreStr.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapTo(mutableSetOf()) { genre ->
                MangaTag(title = genre, key = genre.lowercase(), source = source)
            }
    }

    protected open fun mapStatus(status: Int): MangaState? = when (status) {
        SManga.ONGOING -> MangaState.ONGOING
        SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> MangaState.FINISHED
        SManga.ON_HIATUS -> MangaState.PAUSED
        SManga.CANCELLED -> MangaState.ABANDONED
        else -> null
    }

    protected open fun String.toHostOnly(): String =
        removePrefix("https://").removePrefix("http://").substringBefore("/")

    companion object {
        const val DEFAULT_PAGE_SIZE = 20

        /** Filter names (lowercased) that indicate a status/state filter. */
        private val STATUS_KEYWORDS = listOf(
            "status",
            "tình trạng",
            "tinh trang",
            "trạng thái",
            "trang thai",
            "durum",
            "état",
            "estado",
            "zustand",
        )

        /** Label fragments (lowercased) that map to [MangaState.ONGOING]. */
        private val ONGOING_KEYWORDS = listOf(
            "ongoing",
            "on-going",
            "on going",
            "updating",
            "publishing",
            "airing",
            "đang",          // Vietnamese "đang tiến hành"
            "dang tien hanh",
            "devam",         // Turkish
            "en cours",      // French
            "en curso",      // Spanish
            "lançando",      // Portuguese
            "выходит",       // Russian
            "продолжается",
        )

        /** Label fragments (lowercased) that map to [MangaState.FINISHED]. */
        private val FINISHED_KEYWORDS = listOf(
            "completed",
            "complete",
            "finished",
            "end",
            "full",
            "hoàn",          // Vietnamese "hoàn thành" / "hoàn tất"
            "hoan thanh",
            "trọn bộ",
            "tron bo",
            "tamamland",     // Turkish
            "achevé",
            "terminé",
            "завершено",     // Russian
        )

        /** Label fragments (lowercased) that map to [MangaState.PAUSED]. */
        private val PAUSED_KEYWORDS = listOf(
            "hiatus",
            "on hold",
            "on-hold",
            "paused",
            "beklemede",     // Turkish
            "durduruldu",
            "en pause",
            "заморожено",    // Russian
        )

        /** Label fragments (lowercased) that map to [MangaState.ABANDONED]. */
        private val ABANDONED_KEYWORDS = listOf(
            "canceled",
            "cancelled",
            "dropped",
            "drop",
            "abandoned",
            "discontinued",
            "iptal",         // Turkish
            "đã hủy",
            "da huy",
            "abandonné",
            "заброшено",     // Russian
        )
    }
}

