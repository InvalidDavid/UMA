package org.koitharu.kotatsu.parsers.site.tachiyomi.en.hiperdex

import android.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import org.koitharu.kotatsu.parsers.site.tachiyomi.parsers.Madara
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.getPreferences
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.TachiyomiSource
import java.text.SimpleDateFormat
import java.util.Locale
import eu.kanade.tachiyomi.source.model.SChapter
import okhttp3.Response

@TachiyomiSource("HIPERDEX", "Hiperdex", "en")
class Hiperdex :
    Madara(
        "Hiperdex",
        "https://hiperdex.com",
        "en",
        dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US),
    ),
    ConfigurableSource {

    override val mangaDetailsSelectorStatus = "div.summary-heading:contains(Status) + div.summary-content"

    private val preferences = getPreferences()

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val client = super.client.newBuilder()
        .addNetworkInterceptor(ClearanceInterceptor())
        .addInterceptor { chain ->
            val request = chain.request()

            if (request.url.host == "hiperdex.com") {
                Thread.sleep(1000)
            }

            chain.proceed(request)
        }
        .build()

    private val userAgents = arrayOf(
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Xiaomi 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; OnePlus 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
    )

    private val sessionUserAgent by lazy {
        userAgents.random()
    }

    override fun headersBuilder() = super.headersBuilder().apply {
        set("User-Agent", sessionUserAgent)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return super.chapterListParse(response)
            .sortedBy {
                extractChapterNumber(it.name) ?: Double.MAX_VALUE
            }
    }

    private fun extractChapterNumber(name: String): Double? {
        return Regex(
            """(?:chapter|chap|ch)\s*\.?\s*(\d+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        )
            .find(name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val pageListParseSelector = "div.page-break:not([style*='display:none'])"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            dialogTitle = BASE_URL_PREF_TITLE
            setDefaultValue(super.baseUrl)
        }.also { screen.addPreference(it) }

        CheckBoxPreference(screen.context).apply {
            key = "${REMOVE_TITLE_VERSION_PREF}_$lang"
            title = "Remove version information from entry titles"
            summary = "This removes version tags like '(Official)' or '(Uncensored)' from entry titles " +
                    "and helps identify duplicate entries in your library. " +
                    "To update existing entries, remove them from your library (unfavorite) and refresh manually. " +
                    "You might also want to clear the database in advanced settings."
            setDefaultValue(false)
        }

        EditTextPreference(screen.context).apply {
            key = "${REMOVE_TITLE_CUSTOM_PREF}_$lang"
            title = "Custom regex to be removed from title"
            summary = customRemoveTitle()
            setDefaultValue("")
        }.also { screen.addPreference(it) }
    }

    override fun popularMangaFromElement(element: Element): SManga = super.popularMangaFromElement(element).apply {
        title = title.cleanTitleIfNeeded()
    }

    override fun latestUpdatesFromElement(element: Element): SManga = super.latestUpdatesFromElement(element).apply {
        title = title.cleanTitleIfNeeded()
    }

    override fun searchMangaFromElement(element: Element): SManga = super.searchMangaFromElement(element).apply {
        title = title.cleanTitleIfNeeded()
    }

    override fun searchMangaSelector() = "#loop-content div.page-listing-item"

    override val chapterUrlSuffix = ""

    override fun mangaDetailsParse(document: Document): SManga = super.mangaDetailsParse(document).apply {
        val cleanedTitle = title.cleanTitleIfNeeded()
        if (cleanedTitle != title.trim()) {
            description = listOfNotNull(title, description)
                .joinToString("\n\n")
            title = cleanedTitle
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    private fun String.cleanTitleIfNeeded(): String {
        var tempTitle = this
        customRemoveTitle().takeIf { it.isNotEmpty() }?.let { customRegex ->
            runCatching {
                tempTitle = tempTitle.replace(Regex(customRegex), "")
            }
        }
        if (isRemoveTitleVersion()) {
            tempTitle = tempTitle.replace(titleRegex, "")
        }
        return tempTitle.trim()
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, super.baseUrl)!!
    private fun isRemoveTitleVersion(): Boolean = preferences.getBoolean("${REMOVE_TITLE_VERSION_PREF}_$lang", false)
    private fun customRemoveTitle(): String = preferences.getString("${REMOVE_TITLE_CUSTOM_PREF}_$lang", "")!!

    init {
        preferences.getString(DEFAULT_BASE_URL_PREF, null).let { defaultBaseUrl ->
            if (defaultBaseUrl != super.baseUrl) {
                preferences.edit()
                    .putString(BASE_URL_PREF, super.baseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, super.baseUrl)
                    .apply()
            }
        }
    }

    companion object {
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Edit source URL (requires restart)"
        private const val BASE_URL_PREF_SUMMARY = "The default settings will be applied when the extension is next updated"
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val REMOVE_TITLE_VERSION_PREF = "REMOVE_TITLE_VERSION"
        private const val REMOVE_TITLE_CUSTOM_PREF = "REMOVE_TITLE_CUSTOM"

        private val titleRegex: Regex by lazy {
            Regex(
                """^(?:\s*(?:\([^()]*\)|\{[^{}]*\}|\[(?:(?!]).)*]|«[^»]*»|〘[^〙]*〙|「[^」]*」|『[^』]*』|≪[^≫]*≫|﹛[^﹜]*﹜|〖[^〖〗]*〗|𖤍.+?𖤍|《[^》]*》|⌜.+?⌝|⟨[^⟩]*⟩)\s*)+|(?:\s*(?:\([^()]*\)|\{[^{}]*\}|\[(?:(?!]).)*]|«[^»]*»|〘[^〙]*〙|「[^」]*」|『[^』]*』|≪[^≫]*≫|﹛[^﹜]*﹜|〖[^〖〗]*〗|𖤍.+?𖤍|《[^》]*》|⌜.+?⌝|⟨[^⟩]*⟩|/\s*Official)\s*)+$""",
                RegexOption.IGNORE_CASE,
            )
        }
    }
}