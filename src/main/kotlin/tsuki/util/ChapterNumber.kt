package tsuki.util

private val CHAPTER_REGEX = Regex("""(?i)\b(?:chapter|ch\.?|chapitre|capítulo|cap\.?|episode|ep\.?)\s*(\d+(?:\.\d+)?)""")
private val FALLBACK_REGEX = Regex("""(\d+(?:\.\d+)?)""")

/**
 * Extract from string
 * Returns [Float] or 0f if no number found.
 */
fun String.extractChapterNumber(): Float {
    CHAPTER_REGEX.find(this)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
    return FALLBACK_REGEX.find(this)?.value?.toFloatOrNull() ?: 0f
}
