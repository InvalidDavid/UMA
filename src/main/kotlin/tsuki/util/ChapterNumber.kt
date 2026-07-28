package tsuki.util

import tsuki.model.MangaChapter

private val CHAPTER_REGEX = Regex("""(?i)\b(?:chapter|ch\.?|chapitre|capítulo|cap\.?|episode|ep\.?)\s*(\d+(?:\.\d+)?)""")
private val FALLBACK_REGEX = Regex("""(\d+(?:\.\d+)?)""")

/**
 * Extracts the most likely chapter number from a string.
 * Fast – no object allocations beyond the regex result.
 * Returns [Float] or 0f if no number found.
 */
fun String.extractChapterNumber(): Float {
    CHAPTER_REGEX.find(this)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
    return FALLBACK_REGEX.find(this)?.value?.toFloatOrNull() ?: 0f
}

/**
 * Sorts a list of [MangaChapter] by their chapter number (ascending).
 * The number is extracted from the chapter title using [extractChapterNumber].
 * Chapters without a recognisable number appear first (treated as 0).
 *
 * Usage: `.sortChapters()`
 */
fun List<MangaChapter>.sortChapters(): List<MangaChapter> {
    return map { it to (it.title?.extractChapterNumber() ?: 0f) }
        .sortedBy { it.second }
        .map { it.first }
}
