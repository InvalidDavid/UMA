package tsuki.util

/*
Extracts the first integer or decimal number from a string.
Returns the parsed [Float], or 0f if no number is found.
Useful for chapter titles like "Chapter 10 / Vol.1 / Ch.5.5 / 12.3"
 */
fun String.extractChapterNumber(): Float {
    return Regex("""(\d+(?:\.\d+)?)""").find(this)?.value?.toFloatOrNull() ?: 0f
}
