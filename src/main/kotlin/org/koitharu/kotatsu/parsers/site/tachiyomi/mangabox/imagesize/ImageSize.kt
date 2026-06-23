package org.koitharu.kotatsu.parsers.site.tachiyomi.mangabox.imagesize

class ImageSize(
    var w: Int,
    var h: Int,
) {
    operator fun component1(): Int = w
    operator fun component2(): Int = h
    override fun toString(): String = "$w:$h"
}