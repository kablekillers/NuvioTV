package com.nuvio.tv.ui.screens.sports

/**
 * Premium sports add-ons are listed first everywhere sports links are shown:
 * Sports Streams always first, then Flix-Streams, StremVerse and Nebula Sports,
 * then every other add-on in its usual order.
 */
internal object SportsAddonPriority {
    fun rank(vararg names: String?): Int {
        val identity = names.filterNotNull().joinToString(" ").lowercase().filter(Char::isLetterOrDigit)
        return when {
            "sportsstreams" in identity || "sportsfly" in identity -> 0
            "flixstream" in identity -> 1
            "stremverse" in identity -> 2
            "nebula" in identity -> 3
            else -> 4
        }
    }

    fun <T> sorted(items: List<T>, name: (T) -> String?): List<T> = items.sortedBy { rank(name(it)) }
}
