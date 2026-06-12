package org.koitharu.kotatsu.parsers.site.tachiyomi.en.mangak

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat

// ======================== Browse / Search ================================

@Serializable
data class SearchResponseDto(
    val items: List<MangaItemDto>,
    @SerialName("has_next") val hasNext: Boolean = false,
)

// DIESE STRUKTUR WIRD VON DER PARSER-FUNKTION BENÖTIGT
@Serializable
data class NextJsRootDto(
    val props: NextJsDto
)

// ======================== Next.js Shared Models ==========================

@Serializable
data class NextJsDto(
    val pageProps: PagePropsDto? = null,
)

@Serializable
data class PagePropsDto(
    val initialManga: MangaDetailDto? = null,
    val initialChapter: ChapterDetailDto? = null,
)

// ======================== Manga Item (list card) =========================

@Serializable
data class MangaItemDto(
    val id: String,
    val title: String,
    // ... restlicher Code wie gehabt ...
) {
    fun toSManga(): SManga = SManga.create().apply { /* ... */ }
}

// ======================== Manga Details ===================================

@Serializable
data class MangaDetailDto(
    val id: String,
    val title: String,
    @SerialName("other_names") val otherNames: List<String>? = null,
    val cover: String? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val type: String? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    val demographic: String? = null,
    val slug: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply { /* ... */ }
}

// ======================== Chapter Detail & List ===========================

@Serializable
data class ChapterDetailDto(
    val id: String? = null,
    val images: List<String>? = null,
)

@Serializable
data class ChapterListResponseDto(
    val chapters: List<ChapterDto>,
)

@Serializable
data class ChapterDto(
    val id: String,
    @SerialName("chapter_number") val chapterNumber: Float? = null,
    val title: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val url: String? = null,
    val slug: String? = null,
) {
    fun toSChapter(dateFormat: SimpleDateFormat): SChapter = SChapter.create().apply { /* ... */ }
}

// ======================== Helpers ========================================

private fun Float.toChapterString(): String =
    if (this == kotlin.math.floor(this)) this.toInt().toString() else this.toString()