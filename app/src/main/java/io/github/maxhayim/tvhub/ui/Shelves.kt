package io.github.maxhayim.tvhub.ui

import io.github.maxhayim.tvhub.model.Channel

/**
 * Home's category rows. Playlists name categories in group-title ("News;General"); each shelf
 * collects a few of them. Channels from a source whose name says Hindi come first on every shelf.
 */
object Shelves {
    data class Shelf(val en: String, val hi: String, val accent: Int, val cats: Set<String>) {
        /** Row title: "News" in English, "Samachar" in Hinglish. */
        val title get() = L.t(en, hi)
        /** Pill text: "News", or "Samachar · News". */
        val label get() = if (hi == en) en else L.t(en, "$hi · $en")
    }

    val all = listOf(
        Shelf("News", "Samachar", 0xFFE5484D.toInt(), setOf("News", "Business", "Weather", "Legislative")),
        Shelf("Music", "Gaane", 0xFFB06CFF.toInt(), setOf("Music")),
        Shelf("Devotional", "Bhakti", 0xFFFFB020.toInt(), setOf("Religious")),
        Shelf("Movies", "Movies", 0xFFFF6A3D.toInt(), setOf("Movies", "Classic")),
        Shelf("Entertainment", "Manoranjan", 0xFF3DD6C6.toInt(), setOf("Entertainment", "General", "Comedy", "Family", "Series", "Lifestyle", "Cooking", "Travel", "Outdoor", "Culture")),
        Shelf("Kids", "Bachche", 0xFF5BC782.toInt(), setOf("Kids", "Animation")),
        Shelf("Sports", "Khel", 0xFF4FA3E0.toInt(), setOf("Sports")),
        Shelf("Knowledge", "Gyan", 0xFF9AA4FF.toInt(), setOf("Education", "Documentary", "Science")),
    )

    fun cats(ch: Channel): Set<String> = ch.group?.split(';', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** "Republic Bharat (1080p) [Geo-blocked]" -> "Republic Bharat". */
    fun clean(name: String) = name.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").trim().ifEmpty { name }

    fun isHindi(ch: Channel) = ch.sourceNames.any { it.contains("hindi", ignoreCase = true) }

    /** Hindi first, then A–Z. */
    fun rank(list: List<Channel>) = list.sortedWith(compareBy<Channel>({ !isHindi(it) }, { it.name.lowercase() }))

    fun shelfOf(ch: Channel): Shelf? { val c = cats(ch); return all.firstOrNull { s -> s.cats.any { it in c } } }
}
